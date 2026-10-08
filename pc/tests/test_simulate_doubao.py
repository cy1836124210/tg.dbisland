"""Tests for pc/simulate_doubao.py (帧构造 + 8799 线格式)，全部离线，不需要设备。

Run:
    python -m unittest discover -s pc/tests -v
    python pc/tests/test_simulate_doubao.py
"""
import json
import os
import socket
import sys
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
sys.path.insert(0, PC)

import simulate_doubao as sim  # noqa: E402


class BuildFramesTest(unittest.TestCase):
    def test_full_shape(self):
        fr = sim.build_frames("full", text="一二三四五六七八九十", seed=1)
        self.assertEqual(fr[0]["t"], "chat.conv")
        self.assertEqual(fr[1]["t"], "chat.start")
        self.assertEqual(fr[-1]["t"], "chat.end")
        kinds = [f.get("kind") for f in fr[2:-1]]
        self.assertIn("think", kinds)
        self.assertIn("tool", kinds)
        self.assertIn("text", kinds)

    def test_ids_are_shared_across_frames(self):
        fr = sim.build_frames("full", text="abc", seed=2)
        mid = {f["mid"] for f in fr if "mid" in f}
        self.assertEqual(len(mid), 1, "同一段流必须共用同一个 mid")
        cid = {f["cid"] for f in fr if "cid" in f}
        self.assertEqual(len(cid), 1)

    def test_text_chunks_reassemble(self):
        text = "这是一段用于验证切块还原的文本，长度足够被切成多块。"
        fr = sim.build_frames("stream", text=text, seed=3)
        body = "".join(f["text"] for f in fr if f["t"] == "chat.delta")
        self.assertEqual(body, text)

    def test_chunk_text_bounds(self):
        chunks = sim.chunk_text("x" * 100, min_chunk=3, max_chunk=7)
        self.assertEqual("".join(chunks), "x" * 100)
        self.assertTrue(all(1 <= len(c) <= 7 for c in chunks))

    def test_plan_sequence(self):
        fr = sim.build_frames("plan", total=4, seed=4)
        self.assertEqual(fr[0]["t"], "plan.start")
        self.assertEqual(fr[-1]["t"], "plan.end")
        self.assertTrue(fr[-1]["success"])
        prog = [f for f in fr if f["t"] == "plan.progress"]
        self.assertEqual(len(prog), 4)
        self.assertEqual([p["done"] for p in prog], [1, 2, 3, 4])
        self.assertTrue(all(p["total"] == 4 for p in prog))

    def test_async_sequence(self):
        fr = sim.build_frames("async", seed=5)
        self.assertEqual([f["t"] for f in fr][-1], "chat.async")
        self.assertTrue(fr[-1]["task_id"])

    def test_all_expands(self):
        fr = sim.build_frames("all", text="短", total=2, seed=6)
        types = [f["t"] for f in fr]
        self.assertIn("plan.start", types)
        self.assertIn("chat.async", types)
        self.assertIn("chat.end", types)
        # full 在最前：第一帧应为 chat.conv
        self.assertEqual(types[0], "chat.conv")

    def test_repeat_uses_distinct_mids(self):
        fr = sim.build_frames("short", repeat=2, seed=7)
        starts = [f["mid"] for f in fr if f["t"] == "chat.start"]
        self.assertEqual(len(starts), 2)
        self.assertNotEqual(starts[0], starts[1])

    def test_unknown_scenario_raises(self):
        with self.assertRaises(SystemExit):
            sim.build_frames("does-not-exist")

    def test_field_caps(self):
        fr = sim.build_frames("full", text="x", cname="标" * 500, seed=8)
        self.assertLessEqual(len(fr[0]["cname"]), sim.MAX_TITLE)
        self.assertTrue(fr[0]["cname"].endswith("…"))

    def test_cut_short_text_untouched(self):
        self.assertEqual(sim.cut("abc", 10), "abc")
        self.assertEqual(sim.cut(None, 10), "")

    def test_frames_are_json_serialisable(self):
        for f in sim.build_frames("all", text="中文 ok", total=2, seed=9):
            s = json.dumps(f, ensure_ascii=False)
            self.assertEqual(json.loads(s)["t"], f["t"])


class WireFormatTest(unittest.TestCase):
    """8799 的真实线格式：<TOKEN>\\t<JSON>\\n，一行一帧。"""

    def _collect(self, run):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]
        got = []

        def serve():
            try:
                c, _ = srv.accept()
            except OSError:
                return
            buf = b""
            c.settimeout(5)
            try:
                while True:
                    b = c.recv(4096)
                    if not b:
                        break
                    buf += b
            except OSError:
                pass
            got.append(buf)
            c.close()

        t = threading.Thread(target=serve, daemon=True)
        t.start()
        n = run(port)
        t.join(5)
        srv.close()
        return n, got

    def test_token_prefixed_lines(self):
        frames = [{"t": "chat.start", "mid": "m1"},
                  {"t": "chat.delta", "mid": "m1", "kind": "text", "text": "你好"},
                  {"t": "chat.end", "mid": "m1"}]
        n, got = self._collect(lambda p: sim.send_tcp(
            frames, "127.0.0.1", p, "tok-123", interval=0))
        self.assertEqual(n, 3)
        lines = got[0].decode("utf-8").strip().split("\n")
        self.assertEqual(len(lines), 3)
        for line in lines:
            self.assertTrue(line.startswith("tok-123\t"), line)
            json.loads(line.split("\t", 1)[1])  # 必须仍是合法 JSON
        self.assertEqual(json.loads(lines[1].split("\t", 1)[1])["text"], "你好")

    def test_no_token_mode_sends_bare_json(self):
        """--no-token 用于安全回归：应发出不带令牌的裸 JSON（手机端必须丢弃）。"""
        n, got = self._collect(lambda p: sim.send_tcp(
            [{"t": "chat.start", "mid": "m"}], "127.0.0.1", p, "",
            interval=0, token_style="none"))
        self.assertEqual(n, 1)
        line = got[0].decode("utf-8").strip()
        self.assertFalse(line.startswith("\t"))
        self.assertEqual(json.loads(line)["t"], "chat.start")

    def test_garbage_mode(self):
        n, got = self._collect(lambda p: sim.send_tcp(
            [{"t": "chat.start"}], "127.0.0.1", p, "t", interval=0,
            token_style="garbage"))
        self.assertEqual(n, 1)
        with self.assertRaises(ValueError):
            json.loads(got[0].decode("utf-8").strip())


class CliTest(unittest.TestCase):
    def test_dry_run_sends_nothing(self):
        self.assertEqual(sim.main(["--scenario", "full", "--dry-run"]), 0)

    def test_queue_mode_requires_adb_or_fails_loudly(self):
        # 没有 adb 时必须显式报错，而不是静默成功
        with self.assertRaises(SystemExit):
            sim.main(["--via", "queue", "--adb", "definitely-not-adb-xyz",
                      "--scenario", "short"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
