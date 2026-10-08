"""HTTP face tests — default bind, /health, /events, /reply, /delete.

The server is started on an ephemeral loopback port and driven with plain
stdlib HTTP calls, so no Doubao and no CDP are needed.

Run:
    python -m unittest discover -s pc/tests -v
    python pc/tests/test_sse_server.py
"""
import contextlib
import io
import json
import os
import queue
import socket
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
sys.path.insert(0, PC)

from sse_server import PROTO, SseServer  # noqa: E402


def get(port, path, timeout=5):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}",
                                    timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def post(port, path, obj, timeout=5):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=json.dumps(obj).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.replies = []
        self.deletes = []
        self.srv = SseServer(
            0,                                   # ephemeral port
            on_reply=lambda text, cid: (self.replies.append((text, cid))
                                        or (True, "ok")),
            on_delete=lambda cid, bot: (self.deletes.append((cid, bot))
                                        or (True, "ok")))
        self.srv.start()
        self.port = self.srv.port
        self.addCleanup(self.srv.stop)

    # ---------- security: default bind ----------
    def test_binds_loopback_by_default(self):
        self.assertEqual(self.srv.host, "127.0.0.1")
        self.assertFalse(self.srv.allow_lan)
        host = self.srv._http.server_address[0]
        self.assertIn(host, ("127.0.0.1", "::1"))

    def test_socket_is_not_reachable_from_the_lan(self):
        """A listener on 127.0.0.1 must refuse a connection to this host's
        LAN address (0.0.0.0 would accept it)."""
        lan = socket.gethostbyname(socket.gethostname())
        if lan.startswith("127."):
            self.skipTest("host has no LAN address")
        s = socket.socket()
        s.settimeout(1.0)
        try:
            with self.assertRaises((ConnectionRefusedError, OSError)):
                s.connect((lan, self.port))
        finally:
            s.close()

    def test_lan_bind_is_explicit_and_warns(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            srv = SseServer(0, host="0.0.0.0")
        self.assertTrue(srv.allow_lan)
        self.assertIn("WARNING", buf.getvalue())
        self.assertIn("NO authentication", buf.getvalue())

    # ---------- protocol ----------
    def test_health(self):
        code, body = get(self.port, "/health")
        self.assertEqual(code, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(body["proto"], PROTO)
        self.assertEqual(body["clients"], 0)
        self.assertEqual(body["bind"], "127.0.0.1")
        self.assertFalse(body["lan"])
        self.assertEqual(body["port"], self.port)

    def test_events_streams_broadcast_frames(self):
        frames = []

        def reader():
            with urllib.request.urlopen(
                    f"http://127.0.0.1:{self.port}/events", timeout=8) as r:
                for line in r:
                    line = line.decode("utf-8").strip()
                    if line.startswith("data: "):
                        frames.append(json.loads(line[6:]))
                        return

        t = threading.Thread(target=reader, daemon=True)
        t.start()
        for _ in range(100):                     # wait for the client slot
            if self.srv.count():
                break
            time.sleep(0.02)
        self.assertEqual(self.srv.count(), 1)
        self.srv.broadcast({"t": "chat.delta", "text": "你好"})
        t.join(timeout=8)
        self.assertEqual(frames, [{"t": "chat.delta", "text": "你好"}])
        # the client is still registered: the server only notices the closed
        # socket when the next heartbeat write fails (HEARTBEAT_S = 15 s)
        self.assertEqual(self.srv.count(), 1)

    def test_client_registry_add_drop(self):
        q = queue.Queue()
        self.srv._add(q)
        self.assertEqual(self.srv.count(), 1)
        self.srv._drop(q)
        self.assertEqual(self.srv.count(), 0)
        self.srv._drop(q)                      # idempotent
        self.assertEqual(self.srv.count(), 0)

    # ---------- uplinks ----------
    def test_reply_route_unchanged(self):
        code, body = post(self.port, "/reply", {"text": "hi", "cid": "42"})
        self.assertEqual((code, body), (200, {"ok": True, "detail": "ok"}))
        self.assertEqual(self.replies, [("hi", "42")])

    def test_reply_rejects_empty_text(self):
        code, body = post(self.port, "/reply", {"text": "   "})
        self.assertEqual(code, 400)
        self.assertFalse(body["ok"])

    def test_delete_route(self):
        code, body = post(self.port, "/delete",
                          {"cid": "38443983515268866", "botId": "7338"})
        self.assertEqual(code, 200)
        self.assertEqual(body, {"ok": True, "detail": "ok",
                                "cid": "38443983515268866"})
        self.assertEqual(self.deletes, [("38443983515268866", "7338")])

    def test_delete_without_cid_is_rejected(self):
        code, body = post(self.port, "/delete", {"botId": "7338"})
        self.assertEqual(code, 400)
        self.assertIn("cid", body["detail"])
        self.assertEqual(self.deletes, [])

    def test_delete_without_handler_is_503(self):
        srv = SseServer(0)
        srv.start()
        self.addCleanup(srv.stop)
        code, body = post(srv.port, "/delete", {"cid": "1"})
        self.assertEqual(code, 503)
        self.assertFalse(body["ok"])

    def test_delete_handler_failure_maps_to_502(self):
        srv = SseServer(0, on_delete=lambda cid, bot: (False, "http 401"))
        srv.start()
        self.addCleanup(srv.stop)
        code, body = post(srv.port, "/delete", {"cid": "1"})
        self.assertEqual((code, body["ok"], body["detail"]), (502, False, "http 401"))

    def test_unknown_routes_404(self):
        self.assertEqual(get(self.port, "/nope")[0], 404)
        self.assertEqual(post(self.port, "/nope", {})[0], 404)

    def test_bad_json_is_400(self):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/delete", data=b"{oops",
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            urllib.request.urlopen(req, timeout=5)
            self.fail("expected 400")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 400)


class LegacyWsServerTest(unittest.TestCase):
    def test_ws_server_defaults_to_loopback(self):
        import server
        s = server.WsServer(0)
        self.assertEqual(s.host, "127.0.0.1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
