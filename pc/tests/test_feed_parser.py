"""Regression tests for pc/feed_parser.py against *real* captured traffic.

Everything asserted here is produced from capture data that lives in the repo:
  pc/raw_cap.jsonl                        live daemon capture of /chat/completion
  capture/last_stream.txt                 full SSE body (ChunkDelta + ACK paths)
  capture/chunk_stream_all.txt            full SSE body (snapshots + tts + async)
  pc/tests/fixtures/*.jsonl               real IM response bodies, converted by
                                          make_fixtures.py into the daemon record
                                          shape ({"k":"req","ev":"body",...,"data"})

Run:
    python -m unittest discover -s pc/tests -v
    python pc/tests/test_feed_parser.py            (also works: no pytest needed)
"""
import base64
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
ROOT = os.path.dirname(PC)
sys.path.insert(0, PC)

from feed_parser import FeedParser  # noqa: E402

RAW_CAP = os.path.join(PC, "raw_cap.jsonl")
LAST_STREAM = os.path.join(ROOT, "capture", "last_stream.txt")
CHUNK_STREAM = os.path.join(ROOT, "capture", "chunk_stream_all.txt")
FIXTURES = os.path.join(HERE, "fixtures")


def feed_records(path, parser):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                parser.feed(json.loads(line))


def run_records(path):
    evs = []
    p = FeedParser(evs.append)
    feed_records(path, p)
    return p, evs


def run_sse(path, chunk=4096):
    """Feed an SSE capture the way the live daemon does: many base64 `chunk`
    records plus a final `done`, so the streaming accumulation path is what
    gets tested (not a single body shortcut)."""
    with open(path, "rb") as f:
        raw = f.read()
    evs = []
    p = FeedParser(evs.append)
    url = "https://www.doubao.com/chat/completion"
    t = 1
    for i in range(0, len(raw), chunk):
        p.feed({"k": "req", "ev": "chunk", "url": url, "t": t,
                "data": base64.b64encode(raw[i:i + chunk]).decode()})
        t = 2                      # same request id afterwards -> same buffer
    p.feed({"k": "req", "ev": "done", "url": url, "t": 2})
    return p, evs


def sse_text(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def kinds(evs, t):
    return [e for e in evs if e.get("t") == t]


def types(evs):
    return [e.get("t") for e in evs]


class RawCapTest(unittest.TestCase):
    """pc/raw_cap.jsonl — 39 records of a real /chat/completion SSE stream."""

    @classmethod
    def setUpClass(cls):
        if not os.path.exists(RAW_CAP):
            raise unittest.SkipTest("pc/raw_cap.jsonl missing")
        cls.parser, cls.evs = run_records(RAW_CAP)

    def test_lifecycle_start_delta_end(self):
        self.assertEqual(types(self.evs)[0], "chat.start")
        self.assertEqual(types(self.evs)[-1], "chat.reply")
        assert types(self.evs).count("chat.start") == 1
        assert types(self.evs).count("chat.end") == 1
        assert types(self.evs).count("chat.reply") == 1
        self.assertEqual(kinds(self.evs, "chat.start")[0]["mid"],
                         "56667377653877762")

    def test_cid_learned_from_stream_msg_notify_meta(self):
        for e in self.evs:
            self.assertEqual(e.get("cid"), "38444131109954306")

    def test_end_mid_comes_from_msg_finish_attr(self):
        # the stream also carries end_type 2/3 frames; only end_type 1 has
        # msg_finish_attr.msgid and must be the one that closes the reply
        body = ""
        with open(RAW_CAP, encoding="utf-8") as f:
            for line in f:
                rec = json.loads(line)
                if rec.get("data"):
                    body += base64.b64decode(rec["data"]).decode("utf-8")
        want = json.loads(body.split("SSE_REPLY_END\ndata: ", 1)[1]
                          .split("\n", 1)[0])["msg_finish_attr"]["msgid"]
        self.assertEqual(kinds(self.evs, "chat.end")[0]["mid"], want)

    def test_text_deltas_sum_to_reply(self):
        text = [e for e in self.evs
                if e["t"] == "chat.delta" and e["kind"] == "text"]
        self.assertTrue(text)
        acc = "".join(e["text"] for e in text)
        reply = kinds(self.evs, "chat.reply")[0]["text"]
        # brief (msg_finish_attr.brief) is truncated by the server (100 chars
        # here, 300 in last_stream) — the streamed text must win
        self.assertEqual(acc, reply)
        self.assertGreater(len(reply), 100)

    def test_no_plan_or_async_events(self):
        self.assertFalse(kinds(self.evs, "plan.start"))
        self.assertFalse(kinds(self.evs, "chat.async"))


class LastStreamTest(unittest.TestCase):
    """capture/last_stream.txt — same request shape plus SSE_ACK (title),
    FULL_MSG_NOTIFY (user echo), tts_content patches and 4 SSE_REPLY_END."""

    @classmethod
    def setUpClass(cls):
        if not os.path.exists(LAST_STREAM):
            raise unittest.SkipTest("capture/last_stream.txt missing")
        cls.text = sse_text(LAST_STREAM)
        cls.parser, cls.evs = run_sse(LAST_STREAM)

    def test_conversation_title_from_sse_ack(self):
        conv = kinds(self.evs, "chat.conv")
        self.assertEqual(len(conv), 1)
        self.assertEqual(conv[0]["cid"], "38444072230225666")
        self.assertEqual(conv[0]["cname"], "新对话")   # /conversation_info/name

    def test_single_end_despite_four_reply_end_frames(self):
        self.assertEqual(self.text.count("event: SSE_REPLY_END"), 4)
        self.assertEqual(len(kinds(self.evs, "chat.end")), 1)
        self.assertEqual(kinds(self.evs, "chat.end")[0]["mid"],
                         "56536561423217410")

    def test_user_echo_message_never_becomes_a_reply(self):
        # FULL_MSG_NOTIFY carries the message the user just sent
        # (SSE_ACK question_id == that message id)
        user_mid = "56572661895586306"
        self.assertIn('"message_id":"%s"' % user_mid, self.text)
        self.assertNotIn(user_mid, [e.get("mid") for e in self.evs])
        self.assertEqual(len(kinds(self.evs, "chat.start")), 1)

    def test_tts_content_never_double_emitted(self):
        mid = "56536561423217410"
        m = self.parser.messages[mid]
        # patch_object 111 carries 251 tts_content fragments; concatenated they
        # mirror the reply exactly (5531 chars) — recorded, never emitted
        self.assertEqual(len(m["tts"]), 5531)
        self.assertEqual(m["tts"], m["text"])
        emitted = sum(len(e["text"]) for e in self.evs
                      if e["t"] == "chat.delta" and e["kind"] == "text")
        # if tts were emitted as well, emitted would be text+tts (11062)
        self.assertEqual(emitted, len(m["text"]))

    def test_status_lines_cover_reading_and_visualization_blocks(self):
        lines = [e["text"] for e in self.evs
                 if e["t"] == "chat.delta" and e["kind"] == "think"]
        for want in ("正在理解任务要求", "正在思考",
                     "生成可视化学习计划框架",     # loading_block / 10063
                     "正在读取 doubao visualization 技能中的 SKILL.md",
                     "已读取 doubao visualization 技能中的"):
            # substring check: the file_operation blocks append the full path
            self.assertTrue(any(want in x for x in lines),
                            f"{want!r} missing from {lines}")
        # every delta carries the conversation id learned from SSE_ACK
        for e in self.evs:
            if e["t"] == "chat.delta":
                self.assertEqual(e.get("cid"), "38444072230225666")


class ChunkStreamTest(unittest.TestCase):
    """capture/chunk_stream_all.txt — ASYNC_CHUNK_SNAPSHOT, tts patch_object
    111, search/query blocks, and patch_value.ext.async_job -> chat.async."""

    @classmethod
    def setUpClass(cls):
        if not os.path.exists(CHUNK_STREAM):
            raise unittest.SkipTest("capture/chunk_stream_all.txt missing")
        cls.text = sse_text(CHUNK_STREAM)
        cls.parser, cls.evs = run_sse(CHUNK_STREAM)

    def test_no_chat_async_in_this_capture(self):
        """chat.async comes from fin_reason.async_task, which does NOT appear
        in any capture (0 hits in both files): the background-handoff event is
        therefore unverified — see test_chat_async_synthetic below."""
        self.assertIn('"async_job"', self.text)
        self.assertNotIn("fin_reason", self.text)
        self.assertFalse(kinds(self.evs, "chat.async"))

    def test_chat_async_synthetic(self):
        """Hand-written STREAM_CHUNK frame to the documented vocabulary,
        because no capture contains fin_reason.async_task."""
        evs = []
        p = FeedParser(evs.append)
        body = ("event: STREAM_CHUNK\ndata: " + json.dumps({
            "seq_no": 191, "message_id": "56544991295959554",
            "fin_reason": {"async_task": {"id": "56544991295959554"}},
        }) + "\n\n")
        p.feed({"k": "req", "ev": "body",
                "url": "https://www.doubao.com/chat/async/chunk_stream",
                "t": 1, "data": base64.b64encode(
                    body.encode("utf-8")).decode()})
        self.assertEqual(kinds(evs, "chat.async"),
                         [{"t": "chat.async", "mid": "56544991295959554",
                           "task_id": "56544991295959554"}])

    def test_patch_50_ext_ends_on_is_finish_and_async_job_status_2(self):
        # the real frame right before SSE_REPLY_END in this capture; the ext
        # value is a *string*, which is what async_job_status parses
        self.assertIn('"is_finish":"1","async_job":"{\\"job_id\\":'
                      '\\"56544991295959554\\",\\"status\\":2', self.text)
        real = {"async_job": '{"job_id":"1","status":2,"append_scene":6}'}
        self.assertEqual(FeedParser.async_job_status(real), 2)
        self.assertEqual(FeedParser.async_job_status({"async_job": {"status": 1}}), 1)
        self.assertIsNone(FeedParser.async_job_status({"async_job": "junk"}))
        self.assertIsNone(FeedParser.async_job_status({}))

    def test_end_from_ext_is_finish_and_msg_finish_attr(self):
        ends = kinds(self.evs, "chat.end")
        self.assertEqual(len(ends), 1)
        self.assertEqual(ends[0]["mid"], "56544991295959554")

    def test_snapshot_thinking_block_becomes_status_line(self):
        self.assertIn("event: ASYNC_CHUNK_SNAPSHOT", self.text)
        lines = [e["text"] for e in self.evs
                 if e["t"] == "chat.delta" and e["kind"] == "think"]
        self.assertIn("调研2026年NEV市场政策", lines)
        self.assertIn("正在写入文件", lines)          # 10063 local_file_block
        self.assertIn("正在读取网页", lines)          # 10006

    def test_search_block_includes_query_and_first_card(self):
        lines = [e["text"] for e in self.evs
                 if e["t"] == "chat.delta" and e["kind"] == "think"]
        hit = [x for x in lines if "参考 10 篇资料" in x]
        self.assertTrue(hit)
        self.assertTrue(any("「" in x for x in hit))          # /queries/*
        self.assertTrue(any("新华网" in x for x in hit))      # text_card/title

    def test_tts_absent_in_this_capture(self):
        # this stream has no patch_object 111 frames at all
        self.assertNotIn('"tts_content"', self.text)
        m = self.parser.messages["56544991295959554"]
        self.assertFalse(m.get("tts"))
        emitted = sum(len(e["text"]) for e in self.evs
                      if e["t"] == "chat.delta" and e["kind"] == "text")
        self.assertEqual(emitted, len(m["text"]))

    def test_no_plan_events_in_this_capture(self):
        self.assertFalse([e for e in self.evs if e["t"].startswith("plan.")])


class FixtureTest(unittest.TestCase):
    """Real /im response bodies (see make_fixtures.py)."""

    def test_plan_start_from_thread_info(self):
        p, evs = run_records(os.path.join(FIXTURES, "thread_info.jsonl"))
        starts = kinds(evs, "plan.start")
        self.assertEqual(len(starts), 1)
        self.assertEqual(starts[0]["tid"], "56356036522376194")
        self.assertEqual(starts[0]["kind"], "thread")
        prog = kinds(evs, "plan.progress")
        self.assertEqual(prog[-1], {"t": "plan.progress", "done": 0, "total": 1,
                                    "name": "2026新能源车行业报告"})
        self.assertEqual(types(evs), ["plan.start", "plan.progress"])

    def test_abstract_complex_task_drives_plan_but_no_history_spam(self):
        p, evs = run_records(
            os.path.join(FIXTURES, "abstract_complex_task.jsonl"))
        starts = kinds(evs, "plan.start")
        self.assertEqual(len(starts), 1)
        self.assertEqual(starts[0]["tid"], "56356036522376194")
        self.assertEqual(starts[0]["kind"], "organizer")
        # /im/conversation/abstract replays the whole conversation: feeding its
        # text blocks through the walker would emit the entire history
        self.assertFalse(kinds(evs, "chat.delta"))
        self.assertFalse(kinds(evs, "chat.start"))

    def test_conv_info_learns_title_and_spams_nothing(self):
        """Real /im/conversation/info bodies (cmd 1110, 56 records in the
        capture, ~20 KB each): only the title may be mined — the body also
        contains the whole conversation and must not produce any chat.* spam."""
        p, evs = run_records(os.path.join(FIXTURES, "conv_info.jsonl"))
        self.assertEqual(types(evs), ["chat.conv", "chat.conv"])
        self.assertEqual(evs[0], {"t": "chat.conv", "cid": "38443605902515202",
                                  "cname": "新对话"})
        # a later response carries the auto-generated title and updates it
        self.assertEqual(evs[1]["cname"], "介绍自己")
        self.assertEqual(p.conv_names["38443605902515202"], "介绍自己")
        # same cid+name twice -> only one announcement
        self.assertEqual(sum(1 for e in evs if e["cname"] == "新对话"), 1)

    def test_message_shaped_objects_never_set_a_title(self):
        evs = []
        p = FeedParser(evs.append)
        body = {"downlink_body": {"get_conv_info_downlink_body": {
            "conversation_info": {"conversation_id": "38443605902515202",
                                 "conversation_type": 3, "name": "新对话"},
            "messages": [{"conversation_id": "38443605902515202",
                          "message_id": "1", "brief": "not a title"}]}}}
        p.feed({"k": "req", "ev": "body",
                "url": "https://www.doubao.com/im/conversation/info", "t": 1,
                "data": base64.b64encode(
                    json.dumps(body).encode("utf-8")).decode()})
        self.assertEqual([e["cname"] for e in evs], ["新对话"])

    def test_plan_end_synthetic(self):
        """No capture contains a finished thread: every thread_status in
        capture/hook.jsonl is "running" (282x). This frame is therefore
        hand-written to the documented vocabulary to cover plan.end."""
        p, evs = run_records(os.path.join(FIXTURES, "thread_info.jsonl"))
        body = {
            "cmd": 1110, "status_code": 0,
            "downlink_body": {"get_thread_info_downlink_body": {"thread_info": {
                "thread_id": "56356036522376194",
                "thread_name": "2026新能源车行业报告",
                "ext": {"thread_status": "completed"},
            }}},
        }
        p.feed({"k": "req", "ev": "body",
                "url": "https://www.doubao.com/im/thread/info", "t": 9,
                "data": base64.b64encode(
                    json.dumps(body).encode("utf-8")).decode()})
        self.assertEqual(len(kinds(evs, "plan.end")), 1)
        self.assertEqual(kinds(evs, "plan.end")[0]["success"], True)
        self.assertEqual(kinds(evs, "plan.progress")[-1]["done"], 1)


class RobustnessTest(unittest.TestCase):
    def test_duplicate_records_do_not_duplicate_events(self):
        if not os.path.exists(RAW_CAP):
            self.skipTest("pc/raw_cap.jsonl missing")
        once = run_records(RAW_CAP)[1]
        twice = []
        p = FeedParser(twice.append)
        feed_records(RAW_CAP, p)
        feed_records(RAW_CAP, p)      # stacked hooks re-deliver the same body
        self.assertEqual(len(twice), len(once))
        for t in ("chat.start", "chat.delta", "chat.end", "chat.reply"):
            self.assertEqual(kinds(twice, t), kinds(once, t))

    def test_garbage_input_is_ignored(self):
        evs = []
        p = FeedParser(evs.append)
        for rec in ({}, {"k": "req", "ev": "chunk", "data": "!!!notb64"},
                    {"k": "req", "ev": "body", "data": "e30="},   # {}
                    {"k": "req", "ev": "done", "text": "not json"},
                    {"k": "req", "ev": "body", "data": "W10="}):  # []
            p.feed(rec)
        self.assertEqual(evs, [])


if __name__ == "__main__":
    # verbosity 2 keeps the command output self-explanatory in reports
    unittest.main(verbosity=2)
