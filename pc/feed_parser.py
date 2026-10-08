"""IslandBridge event parser.

Takes raw __caplog records from island_hook.js and emits normalized events:
    chat.start   {t, mid, cid?, cname?}   new bot reply begins (STREAM_MSG_NOTIFY)
    chat.delta   {t, mid, kind, text, ...} appended text (kind=text|think|tool)
    chat.end     {t, mid, cid?, cname?}   SSE_REPLY_END / patch_value.ext.is_finish
    chat.reply   {t, mid, text, cid?, cname?}  full reply text (not sent to island)
    chat.conv    {t, cid, cname}          conversation title learned
    chat.async   {t, mid, task_id}        reply moved to a background job
    plan.start   {t, tid, title, kind}    complex_task_block / thread seen running
    plan.progress{t, tid, name, status, done, total}
    plan.end     {t, tid, success, text}  all tracked threads finished

Event names handled (SSE `event:` field), all observed on the Doubao Windows
desktop client (v2.30.4) unless noted:
    STREAM_MSG_NOTIFY      reply header   — capture/last_stream.txt
    STREAM_CHUNK           block patches  — capture/chunk_stream_all.txt
    CHUNK_DELTA            bare text inc. — pc/raw_cap.jsonl
    ASYNC_CHUNK_SNAPSHOT   block snapshot — capture/chunk_stream_all.txt
    FULL_MSG_NOTIFY        whole message (user echo) — capture/last_stream.txt
    SSE_ACK                send ack + conversation_info — capture/last_stream.txt
    SSE_REPLY_END          end_type 1/2/3 + msg_finish_attr — pc/raw_cap.jsonl
    SSE_HEARTBEAT, STREAM_TIMEOUT_CONTROL   ignored (no payload we act on)

Field paths taken from D:\\aiwork\\apk\\REVERSE_NOTES.md §3 and verified against
the captures above; see pc/ADAPTATION.md for the item-by-item mapping and for
the report paths that are *not* covered (no evidence in any capture).
"""
import base64
import json
from collections import deque


def _b64(s):
    try:
        return base64.b64decode(s)
    except Exception:
        return b""


def _search_line(s):
    """Status line for block_type 10025 (search_query_result_block).

    Report paths: /queries/*, /suggest_questions_v2/*/content,
    /text_card/{title,summary,url}.
    """
    parts = []
    summary = s.get("summary") or ""
    if summary:
        parts.append(summary)
    queries = s.get("queries") or []
    q0 = queries[0] if queries else ""
    if q0 and q0 not in summary:
        parts.append("「" + str(q0) + "」")
    for r in (s.get("results") or [])[:1]:
        tc = (r or {}).get("text_card") or {}
        if tc.get("title"):
            parts.append(str(tc["title"]))
            break
    if not parts:
        return "搜索中"
    return " · ".join(parts)[:200]


# content-key -> human status line. Keyed off the content object rather than
# block_type: the same payload appears under several block_type values across
# versions (e.g. thinking_block under 10040/10090).
def _block_line(btype, content):
    tb = content.get("thinking_block") or {}
    if tb:
        # /thinking_block/{streaming_title,finish_title,unfold_streaming_title,summary}
        return (tb.get("streaming_title") or tb.get("finish_title")
                or tb.get("unfold_streaming_title") or tb.get("summary") or "")
    g = content.get("generic_tool_block") or {}
    if g:
        return "工具 " + (g.get("title") or g.get("tool_name")
                        or g.get("summary") or "")
    s = content.get("search_query_result_block") or {}
    if s:
        return _search_line(s)
    f = content.get("file_operation_block") or {}
    if f:
        # 10019 — header.summary reads "正在写入文件"
        return (f.get("header") or {}).get("summary") or ""
    lf = content.get("local_file_block") or {}
    if lf:
        # 10063 — artifact file the agent produced
        return "生成文件 " + (lf.get("name") or "")
    lb = content.get("loading_block") or {}
    if lb:
        return lb.get("text") or ""
    tl = content.get("text_loading") or {}
    if tl:
        return tl.get("text") or ""
    cb = content.get("creation_block") or {}
    if cb:
        # /creation_block/creations/*/{prompt,neg_prompt,tips,description,placeholder}
        cre = (cb.get("creations") or [{}])[0] or {}
        return (cre.get("prompt") or cre.get("description")
                or cre.get("placeholder") or cre.get("tips") or "")
    if btype == 10006:
        return "正在读取网页"
    if btype == 10091:
        return ""            # elapsed_block: invisible timer, nothing to show
    return ""


class FeedParser:
    def __init__(self, emit):
        self.emit = emit            # callable(dict)
        self.buffers = {}           # req_key -> {"text":..., "url":...}
        self.messages = {}          # message_id -> {"text":str,"live":bool}
        self.cid_by_mid = {}        # message_id -> conversation_id
        self.conv_names = {}        # conversation_id -> conversation title
        self.threads = {}           # thread_id -> {"name","status"}
        self.plan_started = False
        self.plan_ended = False
        self.order = []             # thread_id discovery order
        self.last_cid = ""          # newest conversation seen (SSE_ACK fallback)
        self.last_question = {}     # SSE_ACK query_list[0]
        self.timeout_conf = {}      # SSE_ACK / STREAM_TIMEOUT_CONTROL
        self.sp_v2_seen = None      # /patch_value/ext/sp_v2 (never observed)
        self._frames_seen = deque(maxlen=400)   # recent frame texts
        # (global deque — Doubao's signer issues the same request under two
        #  urls (unsigned + msToken-signed), each wrapper pumps the body)

    # ---------- raw record entry ----------
    def feed(self, rec):
        try:
            k = rec.get("k")
            ev = rec.get("ev")
            url = rec.get("url", "")
            key = (url, rec.get("t"))
            if ev == "chunk" and rec.get("data"):
                buf = self.buffers.setdefault(key, {"text": "", "url": url})
                buf["text"] += _b64(rec["data"]).decode("utf-8", "replace")
                self._drain_sse(key, buf)
            elif ev == "chunk" and rec.get("text"):        # xhr delta
                buf = self.buffers.setdefault(key, {"text": "", "url": url})
                buf["text"] += rec["text"]
                self._drain_sse(key, buf)
            elif ev in ("done", "err", "body"):
                if rec.get("data"):
                    text = _b64(rec["data"]).decode("utf-8", "replace")
                    if "event:" in text and "data:" in text:
                        # backstop full SSE body (Network.getResponseBody)
                        b = {"text": text + "\n\n", "url": url}
                        self._drain_sse(key, b)
                    else:
                        self._drain_json(key, url, text)
                buf = self.buffers.pop(key, None)
                if buf and buf["text"]:
                    self._drain_json(key, url, buf["text"])
        except Exception:
            pass

    # ---------- SSE ----------
    def _drain_sse(self, key, buf):
        text = buf["text"]
        while True:
            i = text.find("\n\n")
            j = text.find("\r\n\r\n")
            idxs = [x for x in (i, j) if x >= 0]
            if not idxs:
                break
            idx = min(idxs)
            sep = 2 if text[idx:idx + 2] == "\n\n" else 4
            frame, text = text[:idx], text[idx + sep:]
            self._sse_frame(key, frame)
        buf["text"] = text

    def _dup_frame(self, frame):
        """Stacked hooks may deliver the same SSE frame twice (each wrapper
        reads its own cloned body). Dedupe on the frame text itself."""
        if frame in self._frames_seen:
            return True
        self._frames_seen.append(frame)
        return False

    def _sse_frame(self, key, frame):
        if self._dup_frame(frame):
            return
        ev, data, raw = "", None, None
        for line in frame.split("\n"):
            if line.startswith("event:"):
                ev = line[6:].strip()
            elif line.startswith("data:"):
                raw = line[5:].strip()
                try:
                    data = json.loads(raw) if raw else None
                except Exception:
                    data = None
        if not ev:
            # Android's stream ends with a bare `data: [DONE]` (no event line).
            # Never seen from the desktop client, kept as a cheap backstop.
            if raw == "[DONE]":
                self._on_reply_end(key, None)
            return
        if ev == "CHUNK_DELTA" and data:
            # main reply stream for longer answers: {"text": "..."} —
            # no message_id, attach to the currently live message
            self._on_delta(data.get("text") or "")
        elif ev == "STREAM_CHUNK" and data:
            self._on_chunk(data)
        elif ev == "STREAM_MSG_NOTIFY" and data:
            self._on_msg_notify(data)
        elif ev == "ASYNC_CHUNK_SNAPSHOT" and data:
            for snap in data.get("message_snapshots", []):
                self._walk_blocks(snap.get("meta", {}).get("message_id", ""),
                                  (snap.get("content") or {}).get("content_block") or [])
        elif ev == "FULL_MSG_NOTIFY" and data:
            self._on_full_msg(data)
        elif ev == "SSE_ACK" and data:
            self._on_ack(data)
        elif ev == "SSE_REPLY_END":
            self._on_reply_end(key, data)
        # SSE_HEARTBEAT / STREAM_TIMEOUT_CONTROL: no normalized event.

    # ---------- message events ----------
    def _on_ack(self, data):
        """SSE_ACK — the send acknowledgement. Real payload
        (capture/last_stream.txt):
            /ack_client_meta/{conversation_id,local_conversation_id,
                              conversation_info/{conversation_id,name},section_id}
            /query_list/*/{question_id,local_message_id,message_index}
            /timeout_conf/*
        The desktop client only announces a conversation title in
        conversation_info, so this is the earliest place cname can be learned.
        """
        acm = data.get("ack_client_meta") or {}
        cid = str(acm.get("conversation_id")
                  or acm.get("local_conversation_id") or "")
        if cid:
            self.last_cid = cid
        ql = data.get("query_list") or []
        if ql and isinstance(ql[0], dict):
            self.last_question = {
                "question_id": str(ql[0].get("question_id") or ""),
                "local_message_id": str(ql[0].get("local_message_id") or ""),
                "message_index": ql[0].get("message_index"),
            }
        if data.get("timeout_conf"):
            self.timeout_conf = data["timeout_conf"]
        # learns {conversation_id, name} -> chat.conv
        self._scan_conv_names(acm)

    def _on_full_msg(self, data):
        """FULL_MSG_NOTIFY — one whole message in a single frame. Real payload
        (capture/last_stream.txt) is the echo of the message the user just
        sent: /message/{message_id,conversation_id,user_type,content_type,
        brief,content,thinking_content,content_block,ext} plus /send_content.

        The phone-side parser ignores this event entirely because of that echo
        (MobileFeedParser.kt `if (eventName == "FULL_MSG_NOTIFY") return`).
        Here it is used for two things, neither of which can duplicate a
        reply: the user echo is only mined for ids/title, and a *bot* message
        is only expanded when its stream was never observed (daemon attached
        mid-conversation) — later patches own the text otherwise.
        """
        msg = data.get("message") or {}
        mid = str(msg.get("message_id") or "")
        if not mid:
            return
        cid = str(msg.get("conversation_id") or "")
        if cid:
            self.cid_by_mid[mid] = cid
            self.last_cid = cid
        known = mid in self.messages
        m = self.messages.setdefault(mid, {"text": "", "think": "", "live": True})
        brief = msg.get("brief") or ""
        if brief:
            m["brief"] = brief
        think = msg.get("thinking_content") or ""
        if think:
            m["think_full"] = think
        sc = msg.get("send_content")
        if sc:
            m["send_content"] = sc
        if msg.get("user_type") == 1:
            # user echo — never a reply, never live (CHUNK_DELTA attaches to
            # the newest *live* message, so a live user mid would steal it)
            m["live"] = False
            m["user"] = True
            return
        m["user"] = False
        if known:
            # text already streamed via STREAM_CHUNK patches: only mine the
            # envelope for plan/thread info, don't re-emit the text
            self._walk_blocks(mid, self._blocks_of(msg), text=False)
            return
        m["live"] = True
        self.emit({"t": "chat.start", "mid": mid, **self._conv(mid)})
        self._walk_blocks(mid, self._blocks_of(msg))

    def _blocks_of(self, msg):
        """content_block list for a message envelope.

        Report paths: /message/content (a JSON *string* on the wire),
        /content/content, /send_content.
        """
        blocks = msg.get("content_block")
        if blocks:
            return blocks
        raw = msg.get("content")
        if isinstance(raw, str) and raw.lstrip().startswith("["):
            try:
                blocks = json.loads(raw)
                if isinstance(blocks, list):
                    return blocks
            except Exception:
                return []
        return []

    def _on_msg_notify(self, data):
        meta = data.get("meta") or {}
        mid = meta.get("message_id") or ""
        if not mid:
            return
        if meta.get("user_type") == 1:      # user echo, ignore
            return
        cid = str(meta.get("conversation_id")
                  or meta.get("local_conversation_id") or "")
        if cid:
            self.cid_by_mid[mid] = cid
            self.last_cid = cid
        m = self.messages.setdefault(mid, {"text": "", "think": "", "live": True})
        m["live"] = True
        if meta.get("thread_id"):
            self._thread_seen(meta["thread_id"],
                              (data.get("content") or {}).get("ext", {}).get("agent_name") or "子任务")
        self.emit({"t": "chat.start", "mid": mid, **self._conv(mid)})
        self._walk_blocks(mid, (data.get("content") or {}).get("content_block") or [])

    def _conv(self, mid):
        """Conversation fields to attach to a chat event."""
        cid = self.cid_by_mid.get(mid, "")
        if not cid and self.messages.get(mid, {}).get("live"):
            cid = self.last_cid       # learned from SSE_ACK before the header
        out = {}
        if cid:
            out["cid"] = cid
            name = self.conv_names.get(cid)
            if name:
                out["cname"] = name
        return out

    def _on_delta(self, text):
        """CHUNK_DELTA frame: bare {"text": ...} reply increment."""
        if not text:
            return
        for mid in reversed(list(self.messages)):
            m = self.messages[mid]
            if m.get("live"):
                m["text"] += text
                self.emit({"t": "chat.delta", "mid": mid, "kind": "text",
                           "text": text, **self._conv(mid)})
                return

    def _on_chunk(self, data):
        mid = data.get("message_id") or ""
        if not mid:
            return
        self.messages.setdefault(mid, {"text": "", "think": "", "live": True})
        for op in data.get("patch_op") or []:
            pv = op.get("patch_value") or {}
            obj = op.get("patch_object")
            if obj in (1, 3):
                # /patch_value/content (content_block list)
                self._walk_blocks(mid, pv.get("content_block") or [])
            elif obj == 50:
                self._on_ext(mid, pv.get("ext") or {})
            elif obj == 111:
                # /patch_value/tts_content — spoken-text mirror of the reply
                # (251 frames in capture/last_stream.txt). Recorded, never
                # emitted: CHUNK_DELTA already carries the same characters, so
                # emitting both would double the text on the island.
                t = pv.get("tts_content") or ""
                if t:
                    m = self.messages[mid]
                    m["tts"] = (m.get("tts") or "") + t
        fr = data.get("fin_reason") or {}
        if fr.get("async_task"):
            self.emit({"t": "chat.async", "mid": mid,
                       "task_id": fr["async_task"].get("id", ""),
                       **self._conv(mid)})

    def _walk_blocks(self, mid, blocks, text=True):
        m = self.messages.setdefault(mid, {"text": "", "think": "", "live": True})
        for b in blocks:
            bt = b.get("block_type")
            c = b.get("content") or {}
            # plan card
            ctb = c.get("complex_task_block")
            if ctb:
                self._thread_seen(ctb.get("thread_id") or "",
                                  (ctb.get("header") or {}).get("name")
                                  or ctb.get("title") or "子任务",
                                  ctb)
                continue
            # visible reply text (/text_block/text)
            tb = c.get("text_block") or {}
            delta = tb.get("text") or ""
            if delta and text and not m.get("user"):
                m["text"] += delta
                self.emit({"t": "chat.delta", "mid": mid, "kind": "text",
                           "text": delta, **self._conv(mid)})
            # thinking / tool status lines -> lyrics
            line = _block_line(bt, c) or tb.get("summary") or ""
            if line and line != m.get("last_line"):
                m["last_line"] = line
                m["think"] = line
                self.emit({"t": "chat.delta", "mid": mid, "kind": "think",
                           "text": line, **self._conv(mid)})

    @staticmethod
    def async_job_status(ext):
        """ext.async_job is a JSON *string* on the wire — verified in
        capture/chunk_stream_all.txt:
            "async_job":"{\\"job_id\\":\\"56544991295959554\\",\\"status\\":1,\\"append_scene\\":6}"
            "async_job":"{\\"job_id\\":\\"56544991295959554\\",\\"status\\":2,\\"append_scene\\":6}"
        status 1 = job still running (seen in ASYNC_CHUNK_SNAPSHOT frames),
        status 2 = job finished (seen next to is_finish:"1"). A dict is
        tolerated too: the old code called .find() on the raw value and
        crashed with AttributeError if the server ever sent an object.
        """
        aj = (ext or {}).get("async_job")
        if isinstance(aj, dict):
            return aj.get("status")
        if isinstance(aj, str) and aj:
            try:
                parsed = json.loads(aj)
            except Exception:
                return None
            if isinstance(parsed, dict):
                return parsed.get("status")
        return None

    def _on_ext(self, mid, ext):
        """patch_object 50 patch_value.ext.

        Verified shapes (capture/last_stream.txt, capture/chunk_stream_all.txt):
          {"is_finish":"1","async_job":"{\\"job_id\\":\\"…\\",\\"status\\":2,…}"}
          {"general_agent_task_mode":"complex","agent_name":"Agent-GeneralTask",…}
          {"cot_ui_style":"summary","is_deep_thinking_show":"1","perf_mark":"…"}
        """
        if not isinstance(ext, dict):
            return
        if (str(ext.get("is_finish")) == "1"
                or self.async_job_status(ext) in (2, "2")):
            self._on_reply_end_mid(mid)
        tid = ext.get("thread_id")
        if tid:
            self._thread_seen(tid, ext.get("agent_name") or "子任务")
        sp = ext.get("sp_v2")
        if sp:
            # listed in REVERSE_NOTES §3 but absent from every capture —
            # kept for diagnostics only, never parsed into an event
            self.sp_v2_seen = sp

    def _on_reply_end(self, key, data=None):
        """SSE_REPLY_END.

        Real payloads (pc/raw_cap.jsonl, capture/*.txt):
          {"end_type":1,"msg_finish_attr":{"msgid":"…","brief":"…",…}}
          {"end_type":2,"answer_finish_attr":{"has_suggest":true}}
          {"end_type":3}
        end_type 2/3 are follow-up frames (suggest questions, stream close)
        and must not end anything, otherwise a reply is closed twice and the
        island card is torn down early.
        """
        end_type = (data or {}).get("end_type")
        mfa = (data or {}).get("msg_finish_attr") or {}
        mid = str(mfa.get("msgid") or "")
        if mid:
            m = self.messages.get(mid)
            if m is not None:
                if mfa.get("brief"):
                    # NOT authoritative: measured 100/300 chars — the server
                    # truncates brief, while the streamed text is complete
                    # (5531 chars in capture/last_stream.txt). Only a fallback.
                    m["brief"] = mfa["brief"]
                self._on_reply_end_mid(mid)
                return
        elif data and end_type not in (1, "1", None):
            return                          # end_type 2/3: nothing to close
        # no msgid to key on (or an unknown message id): legacy behaviour —
        # close the newest live message on this stream
        self._on_reply_end_newest()

    def _on_reply_end_newest(self):
        for mid in reversed(list(self.messages)):
            m = self.messages[mid]
            if m.get("live"):
                self._on_reply_end_mid(mid)
                return

    def _on_reply_end_mid(self, mid):
        m = self.messages.get(mid)
        if not m or not m.get("live"):
            return
        m["live"] = False
        self.emit({"t": "chat.end", "mid": mid, **self._conv(mid)})
        text = m.get("text") or ""
        if not text:
            # nothing streamed (daemon hooked after the reply started): the
            # truncated brief still beats an empty reply page
            text = m.get("brief") or ""
        self.emit({"t": "chat.reply", "mid": mid, "text": text,
                   **self._conv(mid)})

    # ---------- plan / threads ----------
    def _thread_seen(self, tid, name, ctb=None):
        if not tid:
            return
        new = tid not in self.threads
        st = self.threads.setdefault(tid, {"name": name or "子任务",
                                           "status": "running"})
        touched = new
        if name and st.get("name") != name:
            st["name"] = name
            touched = True
        if new:
            self.order.append(tid)
        # complex_task_block.status: measured in capture/hook.jsonl as
        # 4 = "已开始工作" (display_type organizer, the plan header card) and
        # 2 = "已完成工作" (sub_agent) — only 2/completed counts as finished.
        if (ctb and ctb.get("status") in (2, "2", "completed")
                and st["status"] != "completed"):
            st["status"] = "completed"
            touched = True
        if new and not self.plan_started:
            self.plan_started = True
            self.emit({"t": "plan.start", "tid": tid, "title": st["name"],
                       "kind": (ctb or {}).get("display_type") or "thread"})
        if touched:
            self._emit_progress()

    def _on_thread_info(self, tid, name, status):
        """GET_CONV_INFO-sibling /im/thread/info payload
        (downlink_body.get_thread_info_downlink_body.thread_info)."""
        if not tid:
            return
        new = tid not in self.threads
        st = self.threads.setdefault(tid, {"name": name or "子任务",
                                           "status": "running"})
        if tid not in self.order:
            self.order.append(tid)
        touched = new
        if name and st.get("name") != name:
            st["name"] = name
            touched = True
        if status and status != st["status"]:
            st["status"] = status
            touched = True
        # first sighting must announce the plan: thread_status is "running"
        # on every sample in capture/hook.jsonl, so a change-only trigger
        # never fires and plan.* stays dead
        if new and not self.plan_started:
            self.plan_started = True
            self.emit({"t": "plan.start", "tid": tid, "title": st["name"],
                       "kind": "thread"})
        if touched:
            self._emit_progress()

    def _emit_progress(self):
        total = len(self.threads)
        done = sum(1 for s in self.threads.values() if s["status"] == "completed")
        cur = next((self.threads[t]["name"] for t in self.order
                    if self.threads[t]["status"] != "completed"), "")
        self.emit({"t": "plan.progress", "done": done, "total": total,
                   "name": cur})
        if total and done == total and not self.plan_ended:
            self.plan_ended = True
            self.emit({"t": "plan.end", "success": True, "text": "任务完成"})

    # ---------- non-SSE JSON bodies (IM envelope) ----------
    def _drain_json(self, key, url, text):
        if not text or not text.lstrip().startswith("{"):
            return
        try:
            obj = json.loads(text)
        except Exception:
            return
        self._scan_conv_names(obj)
        db = obj.get("downlink_body") or {}
        ti = (db.get("get_thread_info_downlink_body") or {}).get("thread_info") or {}
        if ti:
            ext = ti.get("ext") or {}
            if isinstance(ext, str):
                try:
                    ext = json.loads(ext)
                except Exception:
                    ext = {}
            self._on_thread_info(str(ti.get("thread_id") or ""),
                                 ti.get("thread_name") or "",
                                 ext.get("thread_status") or ti.get("thread_status") or "")
        ab = db.get("pull_conversation_abstract_downlink_body")
        if ab:
            self._scan_abstract(ab)

    def _scan_abstract(self, node):
        """pull_conversation_abstract_downlink_body — real response of
        /im/conversation/abstract (capture/hook.jsonl), the endpoint the
        desktop client uses to rebuild a conversation.

        Only complex_task_block (plan/thread info) is mined: the body replays
        the whole conversation, so running its text_block history through
        _walk_blocks would re-emit every past message as chat.delta.
        """
        stack = [node]
        while stack:
            n = stack.pop()
            if isinstance(n, dict):
                ctb = n.get("complex_task_block")
                if isinstance(ctb, dict):
                    self._thread_seen(
                        str(ctb.get("thread_id") or ""),
                        (ctb.get("header") or {}).get("name")
                        or ctb.get("title") or "子任务", ctb)
                stack.extend(n.values())
            elif isinstance(n, list):
                stack.extend(n)

    def _scan_conv_names(self, node):
        """Walk a JSON body for objects that pair conversation_id with a
        title — /im/conversation/info returns
        downlink_body.get_conv_info_downlink_body.conversation_info
        {conversation_id,name} (96 real calls in capture/hook.jsonl, cmd 1110),
        SSE_ACK returns ack_client_meta.conversation_info.{conversation_id,name}.
        Learned names are announced once per cid via a chat.conv event.

        Message-shaped objects are skipped: they also carry conversation_id and
        often a title/brief, and must never overwrite a real conversation name.
        """
        stack = [node]
        while stack:
            n = stack.pop()
            if isinstance(n, dict):
                cid = str(n.get("conversation_id") or "")
                name = n.get("name") or n.get("title") or ""
                message_like = any(k in n for k in (
                    "message_id", "question_id", "block_id", "brief",
                    "content", "content_block", "local_message_id"))
                if (cid and cid != "0" and len(cid) > 5 and name
                        and not message_like
                        and self.conv_names.get(cid) != name):
                    self.conv_names[cid] = name
                    self.emit({"t": "chat.conv", "cid": cid, "cname": name})
                stack.extend(n.values())
            elif isinstance(n, list):
                stack.extend(n)
