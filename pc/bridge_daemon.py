"""IslandBridge PC daemon.

Attaches to the Doubao desktop client over CDP (--remote-debugging-port),
injects island_hook.js into every target, parses the forwarded stream
records into normalized events, and broadcasts them to the phone app over
a HTTP SSE stream (GET /events).

HTTP face (see pc/protocol.md):
    GET  /events  SSE feed of normalized events (default port 8787)
    GET  /health  {"ok":true,"proto":...,"clients":N,"bind":...,"lan":bool}
    POST /reply   {"text":"...","cid":"..."} -> real send interface in-page
    POST /delete  {"cid":"...","botId":"..."} -> real delete interface in-page
Both uplinks report back as {"t":"send.result","ok":..,"err":..,"act":..}.

Bind address: 127.0.0.1 by default. LAN exposure is an explicit opt-in
(config.json "bind": "0.0.0.0" or --bind 0.0.0.0) and prints a warning.

Usage:
    python bridge_daemon.py [--port 8787] [--cdp-port 9222] [--log events.jsonl]
                            [--bind 127.0.0.1]

Start Doubao first:
    Doubao.exe --remote-debugging-port=9222
"""
import argparse
import base64
import json
import re
import sys
import threading
import time
import uuid
from pathlib import Path

FROZEN = getattr(sys, "frozen", False)
HERE = Path(__file__).resolve().parent
# frozen onefile: bundled data lives in _MEIPASS, user files next to the exe
RES_DIR = Path(getattr(sys, "_MEIPASS", HERE))
BASE_DIR = Path(sys.executable).resolve().parent if FROZEN else HERE
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "pylibs"))

from cdp import CDP, browser_ws          # noqa: E402
from feed_parser import FeedParser       # noqa: E402
from sse_server import SseServer         # noqa: E402
import win_hook                          # noqa: E402

HOOK_JS = (RES_DIR / "island_hook.js").read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# Uplink protocol (verified against D:\aiwork\apk\d24.txt = classes24.dex dump)
#
# Every IM request is one envelope, captured verbatim from the desktop client
# (capture/hook.jsonl, cmd 1125):
#   {"cmd":1125,"uplink_body":{"batch_operate_conv_uplink_body":{...}},
#    "sequence_id":"<uuid>","channel":2,"version":"1"}
# IMCMD enum values (com.larus.im.internal.protocol.bean.IMCMD, d24.txt:2014340+):
#   OPERATE_CONVERSATION 1120, DELETE_USER_CONVERSATION 1121,
#   DISSOLVE_CONVERSATION 1122, UPDATE_CONVERSATION 1123,
#   BATCH_OPERATE_CONVERSATION 1125, BREAK_MSG 2240, BATCH_DEL_USER_CONV 4171
# ---------------------------------------------------------------------------
IM_CHANNEL = 2
IM_VERSION = "1"
CMD_DEL_USER_CONV = 1121
CMD_BATCH_OPERATE_CONV = 1125
CMD_BREAK_MSG = 2240            # documented only, see pc/ADAPTATION.md

# DeleteUserConversationUplinkBody serializer names (d24.txt:1991078) and
# ConversationDeleteMode.DeleteMode_RealDelete = 2 (d24.txt:1990739 clinit).
DELETE_MODE_REAL = 2
# operate_type observed in the desktop client's own successful delete
# (capture/hook.jsonl: {"operate_type":8,"conversation_id_list":[...]} ->
#  {"status":8,...,"success":true}); the enum semantics are not decoded.
BATCH_OPERATE_DELETE = 8

# Filled in by island_hook.js (page origin + the app's own query string are
# only known in the page).
PH_CID = "$CID"
PH_BOT = "$BOTID"

ACT_SEND = "com.islandbridge.SEND"
ACT_DELETE = "com.islandbridge.DELETE"

SEND_PATHS = ("/chat/completion", "/im/sse/send/message", "/im/send/message")


def im_envelope(cmd, key, body):
    """One IM uplink envelope with a fresh sequence_id."""
    return {"cmd": cmd, "uplink_body": {key: body},
            "sequence_id": str(uuid.uuid4()), "channel": IM_CHANNEL,
            "version": IM_VERSION}


def delete_candidates(cid, bot_id=""):
    """Real conversation-delete requests, most specific interface first.

    1. /im/conversation/del_user_conv (cmd 1121) — the single-conversation
       delete named in REVERSE_NOTES §4; body fields + mode take from the
       DEX serializer / ConversationDeleteMode constants.
    2. /im/conversation/batch_operate with operate_type 8 (cmd 1125) — what
       the desktop client itself called to delete a conversation, captured
       together with its {"success":true} response.

    Nothing is invented: ids stay as $CID/$BOTID placeholders until the page
    fills them (island_hook.js __ibDelete), and only fields that exist in the
    real serializers are sent.
    """
    cid = cid or PH_CID
    bot = bot_id or PH_BOT
    single = im_envelope(CMD_DEL_USER_CONV, "delete_user_conv_uplink_body", {
        "conversation_id": cid,
        "mode": DELETE_MODE_REAL,
        "clear_message_index": 0,
        "conversation_type": 0,
        "bot_id": bot,
    })
    batch = im_envelope(CMD_BATCH_OPERATE_CONV,
                        "batch_operate_conv_uplink_body", {
                            "operate_type": BATCH_OPERATE_DELETE,
                            "conversation_id_list": [cid],
                        })
    return [{"path": "/im/conversation/del_user_conv", "body": single},
            {"path": "/im/conversation/batch_operate", "body": batch}]


_mutex_handle = None


def singleton_or_exit():
    """Named-mutex guard — two daemons on one PC split events + double-bind
    the SSE port (Windows SO_REUSEADDR lets both listen)."""
    global _mutex_handle
    import ctypes
    _mutex_handle = ctypes.windll.kernel32.CreateMutexW(
        None, False, "Global\\IslandBridgeDaemonMutex")
    if ctypes.windll.kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
        print("[daemon] another instance is already running — exiting")
        sys.exit(0)


def redirect_log_if_frozen():
    if not FROZEN:
        return
    f = open(BASE_DIR / "daemon.log", "a", encoding="utf-8", buffering=1)
    sys.stdout = sys.stderr = f

# Browser-level capture: page-JS hooks give live SSE chunks (when installed
# before the app caches window.fetch — needs one page reload). As backstop
# the Fetch domain pauses JSON endpoints at Response stage and re-reads the
# finished body, and Network.loadingFinished lets us grab SSE bodies that
# JS hooks missed. Doubao's own traffic is never held back.
WATCH_STREAM = ("/chat/completion", "/chat/async/chunk_stream", "/samantha/")
WATCH_JSON = ("/im/thread/info", "/im/chain/", "/im/conversation/info",
              "/im/conversation/abstract", "/im/conversation/batch_operate",
              "/im/conversation/del_user_conv", "/im/conversation/batch_get")
FETCH_PATTERNS = [{"urlPattern": "*" + p + "*", "requestStage": "Response"}
                  for p in WATCH_JSON]


class Bridge:
    def __init__(self, cdp_port, ws_port, log_path=None, bind="127.0.0.1"):
        self.cdp_port = cdp_port
        self.bind = bind
        self.ws = SseServer(ws_port, bind=bind,
                            on_client=lambda c, ok:
                            print("[sse] client", "joined" if ok else "left",
                                  f"({self.ws.count()} online)"),
                            on_reply=self.send_reply,
                            on_delete=self.delete_conv)
        self.log = open(log_path, "a", encoding="utf-8") if log_path else None
        self.parser = FeedParser(self._emit)
        self.cdp = None
        self.sessions = {}
        self.targets = {}
        self._sid_by_tid = {}          # targetId -> sessionId (dedupe attach)
        self._seen = {}                # payload-hash -> ts (dedupe bindings)
        self._seen_lock = threading.Lock()
        self._pending = {}             # requestId -> (sid, url) for getResponseBody
        self._streams = {}             # requestId -> (sid, url) watched SSE
        self._js_live = {}             # url-prefix -> ts of last JS-hooked chunk
        self._eq = []
        self._eq_lock = threading.Lock()
        self._cdp_dead = False         # set by cdp.on_close -> triggers reconnect
        self._dup_n = 0                # dedupe counter (log every 50th)
        self._flag_warned = False
        # /chat/completion send templates per conversation id — replayed
        # in-page by __ibSendReply; persisted so a page reload doesn't
        # require the user to send once before island replies work
        self._tpl_path = ROOT / "pc" / "send_templates.json"
        self.send_tpls = self._load_tpls()

    # ---------- normalized event out ----------
    def _emit(self, obj):
        if self.log:
            self.log.write(json.dumps(obj, ensure_ascii=False) + "\n")
            self.log.flush()
        print("[ev]", json.dumps(obj, ensure_ascii=False)[:160])
        self.ws.broadcast(obj)

    # ---------- CDP ----------
    def connect_cdp(self):
        while True:
            try:
                self.cdp = CDP(browser_ws(self.cdp_port))
                self.cdp.on_event = self._reader
                self.cdp.on_close = self._cdp_lost
                self._cdp_dead = False
                return
            except Exception as e:
                print(f"[cdp] waiting for Doubao at :{self.cdp_port} ({e})")
                time.sleep(3)

    def _cdp_lost(self):
        self._cdp_dead = True

    def _reset_link(self):
        """Drop all state tied to the dead CDP connection."""
        try:
            self.cdp.on_close = None      # don't re-flag while we tear down
            self.cdp.close()
        except Exception:
            pass
        self.sessions.clear()
        self._sid_by_tid.clear()
        self._pending.clear()
        self._streams.clear()
        self._js_live.clear()
        self.targets.clear()

    def _bootstrap(self):
        """(Re)arm auto-attach and instrument every current target."""
        try:
            self.cdp.call("Target.setAutoAttach",
                          {"autoAttach": True,
                           "waitForDebuggerOnStart": False,
                           "flatten": True}, timeout=15)
        except Exception as e:
            print("[cdp] autoAttach failed:", e)
        for t in self.cdp.call("Target.getTargets")["targetInfos"]:
            self._attach(t)

    def _instrument(self, sid, tid):
        for call in (("Runtime.enable", None),
                     ("Runtime.addBinding", {"name": "__caplog"}),
                     ("Page.enable", None),
                     ("Page.addScriptToEvaluateOnNewDocument",
                      {"source": HOOK_JS}),
                     ("Network.enable", None),
                     ("Fetch.enable", {"patterns": FETCH_PATTERNS})):
            try:
                self.cdp.call(call[0], call[1] or {}, session_id=sid)
            except Exception:
                pass
        try:
            r = self.cdp.call("Runtime.evaluate",
                              {"expression": HOOK_JS, "returnByValue": True},
                              session_id=sid)
            print("[hook]", tid[:8], "->", r.get("result", {}).get("value"))
            self._seed_tpls(sid)
        except Exception as e:
            print("[hook] eval err", tid[:8], e)

    # ---------- send templates (reply without prior manual send) ----------
    def _load_tpls(self):
        try:
            return json.loads(self._tpl_path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_tpls(self):
        try:
            self._tpl_path.write_text(
                json.dumps(self.send_tpls, ensure_ascii=False),
                encoding="utf-8")
        except Exception as e:
            print("[tpl] save fail", e)

    @staticmethod
    def _tpl_cid(body):
        """Conversation id of a send body, either envelope shape
        (/chat/completion client_meta vs IM send_message_body)."""
        try:
            o = json.loads(body)
        except Exception:
            return ""
        cm = o.get("client_meta") or {}
        cid = cm.get("conversation_id") or cm.get("local_conversation_id") or ""
        if cid:
            return cid
        for b in (o.get("uplink_body") or {}).values():
            if not isinstance(b, dict):
                continue
            c2 = b.get("client_meta") or {}
            cid = (c2.get("conversation_id") or c2.get("local_conversation_id")
                   or b.get("conversation_id") or "")
            if cid:
                return cid
        return ""

    def _stash_tpl(self, rec):
        """Learn a send request as a replay template for __ibSend.
        Covers the desktop client's /chat/completion (verified: 120 real
        requests in capture/hook.jsonl) and the IM SSE send path. Only the
        pre-sign call is kept (no msToken/a_bogus in the url)."""
        body = rec.get("reqBody")
        url = rec.get("url", "")
        if not body or not any(p in url for p in SEND_PATHS):
            return
        if re.search(r"[?&](msToken|a_bogus|X-Bogus|_signature)=", url):
            return
        cid = self._tpl_cid(body)
        new = {"url": url, "method": rec.get("method") or "POST",
               "headers": rec.get("reqHeaders"), "body": body}
        if self.send_tpls.get(cid) != new:
            self.send_tpls[cid] = new
            self._save_tpls()
            print("[tpl] send template learned for", cid or "<new-conv>")

    def _seed_tpls(self, sid):
        """Push stored templates into a freshly-hooked page."""
        if not self.send_tpls:
            return
        try:
            js = ("(function(){var m=" +
                  json.dumps(self.send_tpls, ensure_ascii=False) +
                  ";window.__ibSendByCid=window.__ibSendByCid||{};"
                  "for(var k in m)window.__ibSendByCid[k]=m[k];"
                  "var ks=Object.keys(m);if(ks.length)"
                  "window.__ibSendLast=m[ks[ks.length-1]];"
                  "return 'seeded '+ks.length})()")
            r = self.cdp.call("Runtime.evaluate",
                              {"expression": js, "returnByValue": True},
                              session_id=sid)
            print("[tpl]", r.get("result", {}).get("value"))
        except Exception as e:
            print("[tpl] seed fail", e)

    def _attach(self, t):
        tid = t["targetId"]
        if tid in self._sid_by_tid:
            return                       # already have a session on it
        try:
            sid = self.cdp.attach(tid)
        except Exception as e:
            print("[cdp] attach fail", t.get("type"), e)
            return
        self.sessions[sid] = tid
        self._sid_by_tid[tid] = sid
        self.targets[tid] = t
        if t["type"] in ("page", "iframe", "worker", "shared_worker",
                         "service_worker", "other"):
            self._instrument(sid, tid)

    def _reader(self, m):
        with self._eq_lock:
            self._eq.append(m)

    def _handle(self, m):
        p = m.get("params", {})
        sid = m.get("sessionId")
        meth = m.get("method")
        if meth == "Target.attachedToTarget":
            t = p["targetInfo"]
            tid = t["targetId"]
            self.targets[tid] = t
            if tid in self._sid_by_tid:
                return                   # session already instrumented
            self.sessions[p["sessionId"]] = tid
            self._sid_by_tid[tid] = p["sessionId"]
            if t["type"] in ("page", "iframe", "worker", "shared_worker",
                             "service_worker", "other"):
                self._instrument(p["sessionId"], tid)
        elif meth == "Target.detachedFromTarget":
            self.sessions.pop(sid, None)
        elif meth == "Runtime.bindingCalled" and p.get("name") == "__caplog":
            payload = p.get("payload", "")
            try:
                rec = json.loads(payload)
            except Exception:
                return
            # dedupe key ignores timestamps AND signature query params —
            # Doubao's signer calls fetch twice (unsigned -> +msToken&a_bogus)
            # and each layer pumps the same body
            nrec = {k: v for k, v in rec.items() if k != "t"}
            if isinstance(nrec.get("url"), str):
                nrec["url"] = re.sub(r"[?&](msToken|a_bogus|X-Bogus|"
                                     r"_signature|_as)=[^&]*", "",
                                     nrec["url"])
            norm = json.dumps(nrec, sort_keys=True)
            with self._seen_lock:
                if norm in self._seen:
                    self._dup_n += 1
                    if self._dup_n % 50 == 1:
                        print(f"[dup] x{self._dup_n} suppressed")
                    return
                self._seen[norm] = time.time()
                if len(self._seen) > 500:
                    cutoff = time.time() - 3
                    self._seen = {k: v for k, v in self._seen.items()
                                  if v > cutoff}
            if rec.get("ev") == "chunk":
                self._js_live[rec.get("url", "").split("?")[0]] = time.time()
            elif rec.get("ev") == "open":
                self._stash_tpl(rec)
            tid = self.sessions.get(sid, "?")
            extra = ""
            if rec.get("ev") == "chunk":
                d = rec.get("data") or rec.get("text") or ""
                extra = f" seq={rec.get('seq')} len={len(d)} head={d[:28]!r}"
            print("[cap]", tid[:8], rec.get("k"), rec.get("ev", ""),
                  str(rec.get("url", ""))[:60], extra)
            self.parser.feed(rec)
        elif meth == "Fetch.requestPaused":
            self._on_paused(sid, p)
        elif meth == "Network.requestWillBeSent":
            u = (p.get("request") or {}).get("url", "")
            if any(s in u for s in WATCH_STREAM):
                self._streams[p["requestId"]] = (sid, u)
        elif meth == "Network.loadingFinished":
            rid = p.get("requestId")
            info = self._pending.pop(rid, None)
            st = self._streams.pop(rid, None)
            if info:
                threading.Thread(target=self._grab_body,
                                 args=(info[0], rid, info[1]),
                                 daemon=True).start()
            elif st and not self._js_covered(st[1]):
                threading.Thread(target=self._grab_body,
                                 args=(st[0], rid, st[1]),
                                 daemon=True).start()
        elif meth == "Network.loadingFailed":
            rid = p.get("requestId")
            self._pending.pop(rid, None)
            self._streams.pop(rid, None)

    def _js_covered(self, url):
        """True if the page-level hook already streamed chunks for this url."""
        base = url.split("?")[0]
        ts = self._js_live.get(base, 0)
        return time.time() - ts < 30

    # ---------- Fetch-domain capture ----------
    def _on_paused(self, sid, p):
        # only WATCH_JSON urls ever pause — release instantly, read the
        # finished body later via Network.getResponseBody
        rid = p.get("requestId")
        url = (p.get("request") or {}).get("url", "")
        try:
            self.cdp.call("Fetch.continueResponse", {"requestId": rid},
                          session_id=sid)
        except Exception:
            try:
                self.cdp.call("Fetch.continueRequest", {"requestId": rid},
                              session_id=sid)
            except Exception:
                return
        self._pending[rid] = (sid, url)
        print("[fetch] release", url[:90])

    def _grab_body(self, sid, rid, url):
        try:
            r = self.cdp.call("Network.getResponseBody", {"requestId": rid},
                              session_id=sid, timeout=10)
            body = r.get("body", "")
            if r.get("base64Encoded"):
                data = body
            else:
                import base64 as _b
                data = _b.b64encode(body.encode("utf-8")).decode()
            print("[fetch] body", len(data) * 3 // 4, "B", url[:70])
            self.parser.feed({"k": "req", "ev": "body", "url": url,
                              "t": int(time.time() * 1000), "data": data})
        except Exception as e:
            print("[fetch] body fail", url[:60], e)

    # ---------- uplink (POST /reply, POST /delete) ----------
    def _page(self, cid):
        """Pick the Doubao page to evaluate in: the one whose URL names the
        conversation, else any chat page, else any Doubao page.
        Returns (target, session_id) or (None, None)."""
        if self.cdp is None or self._cdp_dead:
            return None, None
        cands = [t for t in self.targets.values() if t.get("type") == "page"
                 and "doubao" in (t.get("url") or "")]
        chat = [t for t in cands if "/chat" in t.get("url", "")]
        pick = None
        if cid:
            pick = next((t for t in chat if cid in t.get("url", "")), None)
        pick = pick or (chat[0] if chat else (cands[0] if cands else None))
        if pick is None:
            return None, None
        tid = pick["targetId"]
        sid = self._sid_by_tid.get(tid)
        if sid is None:
            try:
                sid = self.cdp.attach(tid)
            except Exception:
                return None, None
            self.sessions[sid] = tid
            self._sid_by_tid[tid] = sid
        return pick, sid

    def _eval(self, sid, expression, await_promise=True, timeout=20):
        r = self.cdp.call("Runtime.evaluate",
                          {"expression": expression,
                           "awaitPromise": await_promise,
                           "returnByValue": True},
                          session_id=sid, timeout=timeout)
        return (r.get("result") or {}).get("value") or {}

    def send_reply(self, text, cid=""):
        """Send `text` into the Doubao desktop chat.

        Preferred path: replay the app's own send request in-page
        (island_hook.js __ibSend) so cookies and the app's signing layer
        (msToken/a_bogus, applied below window.fetch) still apply.
        Fallback: CDP typing of the text into the editor + Enter.
        Either way a {"t":"send.result", act:...} event is emitted.
        Returns (ok, detail) for the HTTP caller.
        """
        if self.cdp is None or self._cdp_dead:
            self._report(False, "cdp not connected", ACT_SEND)
            return False, "cdp not connected"
        pick, sid = self._page(cid)
        if pick is None:
            self._report(False, "no doubao page target", ACT_SEND)
            return False, "no doubao page target"
        err = ""
        try:
            js = ("((window.__ibSend||window.__ibSendReply)?"
                  "(window.__ibSend||window.__ibSendReply)("
                  + json.dumps(cid or "") + "," + json.dumps(text)
                  + "):Promise.resolve({ok:false,err:'no-hook'}))")
            res = self._eval(sid, js)
            if res.get("ok"):
                via = str(res.get("via") or "replay")
                print(f"[reply] api-sent {len(text)} chars via {via} -> "
                      f"{pick.get('url','')[:60]}")
                self._report(True, "", ACT_SEND)
                return True, "sent via " + via
            err = str(res.get("err") or res.get("status") or "unknown")
            print(f"[reply] api path failed ({err}), "
                  "falling back to input injection")
        except Exception as e:
            err = f"{type(e).__name__}: {e}"
            print(f"[reply] api path error {e}, falling back")
        try:
            r = self.cdp.call("Runtime.evaluate", {"expression":
                "(function(){var e=document.querySelector("
                "'[contenteditable=true]');if(!e)return 'no-editor';"
                "e.focus();return 'focused'})()",
                "returnByValue": True}, session_id=sid)
            if r.get("result", {}).get("value") != "focused":
                detail = "editor not found on " + pick.get("url", "?")[:60]
                self._report(False, detail, ACT_SEND)
                return False, detail
            self.cdp.call("Input.insertText", {"text": text},
                          session_id=sid)
            for typ in ("rawKeyDown", "keyUp"):
                self.cdp.call("Input.dispatchKeyEvent", {
                    "type": typ, "key": "Enter", "code": "Enter",
                    "windowsVirtualKeyCode": 13, "nativeVirtualKeyCode": 13,
                }, session_id=sid)
            print(f"[reply] typed {len(text)} chars -> {pick.get('url','')[:60]}")
            self._report(True, "", ACT_SEND)
            return True, "sent (cdp typing fallback)"
        except Exception as e:
            detail = f"{type(e).__name__}: {e}"
            self._report(False, detail, ACT_SEND)
            return False, detail

    def delete_conv(self, cid, bot_id=""):
        """Delete a conversation through the real IM interface.

        Candidates (see delete_candidates) are tried in order; the envelope is
        built here from the DEX-verified protocol and transported in-page by
        island_hook.js __ibDelete so cookies + the signing layer apply.
        There is no CDP-typing fallback for delete: the desktop UI path is a
        mouse-driven context menu, which cannot be driven reliably from the
        protocol side (documented in pc/ADAPTATION.md).
        Returns (ok, detail) and emits send.result with act=DELETE.
        """
        if not cid:
            self._report(False, "missing cid", ACT_DELETE)
            return False, "missing cid"
        if self.cdp is None or self._cdp_dead:
            self._report(False, "cdp not connected", ACT_DELETE)
            return False, "cdp not connected"
        pick, sid = self._page(cid)
        if pick is None:
            self._report(False, "no doubao page target", ACT_DELETE)
            return False, "no doubao page target"
        cands = json.dumps(delete_candidates(cid, bot_id), ensure_ascii=False)
        try:
            js = ("((window.__ibDelete)?window.__ibDelete("
                  + json.dumps(cid) + "," + json.dumps(bot_id or "") + ","
                  + json.dumps(cands)
                  + "):Promise.resolve({ok:false,err:'no-hook'}))")
            res = self._eval(sid, js, timeout=30)
            if res.get("ok"):
                via = str(res.get("via") or "")
                print(f"[delete] {cid} deleted via {via}")
                self._report(True, "", ACT_DELETE)
                return True, "deleted via " + via
            err = str(res.get("err") or res.get("status") or "unknown")
            print(f"[delete] {cid} failed: {err}")
            self._report(False, err, ACT_DELETE)
            return False, err
        except Exception as e:
            detail = f"{type(e).__name__}: {e}"
            print(f"[delete] {cid} error: {detail}")
            self._report(False, detail, ACT_DELETE)
            return False, detail

    def _report(self, ok, err, act):
        """Uplink receipt, same shape the LSPosed side sends:
        {"t":"send.result","ok":bool,"err":str,"act":"com.islandbridge.*"}.
        `src` is an additive field so the phone can tell a PC receipt from a
        same-device one (see pc/ADAPTATION.md §3.13 / §5)."""
        self._emit({"t": "send.result", "ok": bool(ok), "err": err or "",
                    "act": act, "src": "pc"})

    # ---------- coexistence watchdog ----------
    def _watchdog(self):
        """Every 5 min: re-add the debug flag to Doubao launch entries (app
        updates can rebuild them) and warn if a running Doubao lacks it."""
        while True:
            try:
                r = win_hook.ensure_all()
                if r["run"] or r["lnk"]:
                    print(f"[hook] re-patched launch entries: {r}")
                if r["missing_flag"] and not self._flag_warned:
                    self._flag_warned = True
                    print("[hook] Doubao is running WITHOUT "
                          "--remote-debugging-port — restart Doubao "
                          "once (e.g. reboot) to enable capture")
                elif not r["missing_flag"]:
                    self._flag_warned = False
            except Exception as e:
                print("[hook] watchdog err", e)
            time.sleep(300)

    def run(self):
        self.ws.start()
        bound = self.ws.host
        if self.ws.allow_lan:
            print(f"[sse] WARNING: listening on {bound}:{self.ws.port} — this "
                  "port is reachable from the whole LAN and has NO "
                  "authentication; the events feed and the /reply and "
                  "/delete uplinks are open to anyone who can reach it")
            print(f"[sse] phone app subscribes "
                  f"http://<this-pc-ip>:{self.ws.port}/events")
        else:
            print(f"[sse] listening on {bound}:{self.ws.port} "
                  "(loopback only). For the phone app set config.json "
                  "\"bind\": \"0.0.0.0\" (LAN-exposed, unauthenticated) or run "
                  "with --bind 0.0.0.0")
        threading.Thread(target=self._watchdog, daemon=True).start()
        self.connect_cdp()
        self._bootstrap()
        print("[bridge] running — send a Doubao message to see events")
        try:
            while True:
                if self._cdp_dead:
                    print("[cdp] connection lost — reconnecting")
                    self._reset_link()
                    self.connect_cdp()
                    self._bootstrap()
                    print("[cdp] reconnected, "
                          f"{len(self._sid_by_tid)} targets instrumented")
                while True:
                    with self._eq_lock:
                        if not self._eq:
                            break
                        m = self._eq.pop(0)
                    try:
                        self._handle(m)
                    except Exception as e:
                        print("[handle]", e)
                time.sleep(0.03)
        except KeyboardInterrupt:
            pass
        finally:
            self.ws.stop()
            if self.log:
                self.log.close()
            self.cdp.close()


def main():
    singleton_or_exit()
    redirect_log_if_frozen()
    cfg = {}
    cfg_file = BASE_DIR / "config.json"
    if cfg_file.exists():
        try:
            cfg = json.loads(cfg_file.read_text(encoding="utf-8"))
        except Exception:
            pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=cfg.get("port", 8787))
    ap.add_argument("--cdp-port", type=int, default=cfg.get("cdp_port", 9222))
    ap.add_argument("--log", default=cfg.get("log", ""))
    ap.add_argument("--bind", default=cfg.get("bind", "127.0.0.1"),
                    help="listen address; 127.0.0.1 (default) is loopback "
                         "only, 0.0.0.0 exposes the unauthenticated feed+"
                         "uplinks to the LAN")
    args = ap.parse_args()
    # security: never widen the bind implicitly. A config file written before
    # this option existed means the old build listened on 0.0.0.0 — say so
    # loudly instead of silently locking the phone app out.
    if "bind" not in cfg and cfg_file.exists():
        print("[sse] config.json has no \"bind\" key: falling back to "
              "loopback 127.0.0.1. If the phone app connects over the LAN, "
              "add \"bind\": \"0.0.0.0\" to pc/config.json")
    if args.bind not in ("127.0.0.1", "localhost", "::1"):
        print(f"[sse] WARNING: --bind {args.bind} exposes the event feed and "
              "the /reply, /delete uplinks to the network without any "
              "authentication")
    Bridge(args.cdp_port, args.port, args.log or None, bind=args.bind).run()


if __name__ == "__main__":
    main()
