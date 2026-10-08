"""Uplink protocol tests — send/delete envelopes and the send.result contract.

The delete envelope is checked against the desktop client's own captured
delete call (pc/tests/fixtures/batch_operate_delete.json, extracted from
capture/hook.jsonl), so the cmd / uplink_body key / operate_type values are
evidence-backed rather than guessed. The single-conversation interface
(/im/conversation/del_user_conv, cmd 1121) has no capture — it is taken from
the DEX dump (D:\\aiwork\\apk\\d24.txt), which this test pins as well.

Run:
    python -m unittest discover -s pc/tests -v
    python pc/tests/test_uplink_protocol.py
"""
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
sys.path.insert(0, PC)

import bridge_daemon as bd  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "batch_operate_delete.json")
TEMPLATES = os.path.join(PC, "send_templates.json")
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                     r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$")


class DeleteEnvelopeTest(unittest.TestCase):
    def setUp(self):
        self.cands = bd.delete_candidates("38443983515268866", "7338286299411103781")

    def test_two_candidates_single_then_batch(self):
        self.assertEqual([c["path"] for c in self.cands],
                         ["/im/conversation/del_user_conv",
                          "/im/conversation/batch_operate"])

    def test_single_delete_uses_dexdump_verified_shape(self):
        body = self.cands[0]["body"]
        self.assertEqual(body["cmd"], 1121)          # DELETE_USER_CONVERSATION
        self.assertEqual(body["channel"], 2)
        self.assertEqual(body["version"], "1")
        self.assertRegex(body["sequence_id"], UUID_RE)
        self.assertEqual(list(body["uplink_body"]),
                         ["delete_user_conv_uplink_body"])
        inner = body["uplink_body"]["delete_user_conv_uplink_body"]
        self.assertEqual(inner, {
            "conversation_id": "38443983515268866",
            "mode": 2,                     # DeleteMode_RealDelete
            "clear_message_index": 0,
            "conversation_type": 0,
            "bot_id": "7338286299411103781",
        })

    def test_batch_operate_matches_the_real_captured_request(self):
        if not os.path.exists(FIXTURE):
            self.skipTest("batch_operate_delete.json missing")
        with open(FIXTURE, encoding="utf-8") as f:
            real = json.loads(f.read())
        real_body = json.loads(real["reqBody"])
        ours = self.cands[1]["body"]
        self.assertEqual(ours["cmd"], real_body["cmd"])          # 1125
        self.assertEqual(ours["channel"], real_body["channel"])
        self.assertEqual(ours["version"], real_body["version"])
        self.assertEqual(list(ours["uplink_body"]),
                         list(real_body["uplink_body"]))
        self.assertEqual(ours["uplink_body"], real_body["uplink_body"])
        self.assertEqual(real["status"], 200)
        # the captured response proves this call really deletes
        res = json.loads(real["resBody"])
        nested = res["downlink_body"]["batch_operate_conv_downlink_body"]
        self.assertEqual(nested["status"], 8)
        self.assertTrue(nested["conversation_list"][0]["success"])
        self.assertFalse(nested["has_failure"])

    def test_ids_are_placeholders_when_unknown(self):
        c = bd.delete_candidates("", "")
        inner = c[0]["body"]["uplink_body"]["delete_user_conv_uplink_body"]
        self.assertEqual(inner["conversation_id"], bd.PH_CID)
        self.assertEqual(inner["bot_id"], bd.PH_BOT)
        self.assertEqual(
            c[1]["body"]["uplink_body"]["batch_operate_conv_uplink_body"]
            ["conversation_id_list"], [bd.PH_CID])

    def test_sequence_id_is_fresh_per_call(self):
        a = bd.delete_candidates("1", "")
        b = bd.delete_candidates("1", "")
        self.assertNotEqual(a[0]["body"]["sequence_id"],
                            b[0]["body"]["sequence_id"])

    def test_envelope_helper(self):
        e = bd.im_envelope(2240, "break_stream_msg_uplink_body", {"a": 1})
        self.assertEqual(e["cmd"], 2240)      # BREAK_MSG (documented only)
        self.assertEqual(e["uplink_body"],
                         {"break_stream_msg_uplink_body": {"a": 1}})
        self.assertRegex(e["sequence_id"], UUID_RE)
        self.assertNotIn("a", e)              # no invented top-level fields


class SendResultContractTest(unittest.TestCase):
    def test_act_constants_match_the_android_contract(self):
        # android/.../DoubaoHookEntry.kt ACT_SEND/ACT_DELETE and
        # IslandBridge.kt BCAST_SEND/BCAST_DELETE
        self.assertEqual(bd.ACT_SEND, "com.islandbridge.SEND")
        self.assertEqual(bd.ACT_DELETE, "com.islandbridge.DELETE")

    def test_tpl_cid_reads_both_envelope_shapes(self):
        with open(TEMPLATES, encoding="utf-8") as f:
            real = json.loads(f.read())
        key = next(k for k in real if k)
        self.assertEqual(bd.Bridge._tpl_cid(real[key]["body"]), key)
        im = json.dumps({"uplink_body": {"send_message_body": {
            "client_meta": {"conversation_id": "1234567890"}}}})
        self.assertEqual(bd.Bridge._tpl_cid(im), "1234567890")
        self.assertEqual(bd.Bridge._tpl_cid("not json"), "")

    def test_send_paths_cover_desktop_and_im(self):
        self.assertIn("/chat/completion", bd.SEND_PATHS)
        self.assertIn("/im/sse/send/message", bd.SEND_PATHS)


class DispatcherTest(unittest.TestCase):
    """Bridge._report emits exactly the documented receipt frame."""

    def test_report_shape(self):
        import contextlib
        import io
        import types
        got = []
        bridge = bd.Bridge.__new__(bd.Bridge)     # no CDP / no sockets
        bridge.log = None
        bridge.ws = types.SimpleNamespace(broadcast=got.append)
        with contextlib.redirect_stdout(io.StringIO()):   # _emit also prints
            bd.Bridge._report(bridge, False, "boom", bd.ACT_DELETE)
        self.assertEqual(got, [{"t": "send.result", "ok": False, "err": "boom",
                                "act": "com.islandbridge.DELETE", "src": "pc"}])
        self.assertEqual(set(got[0]), {"t", "ok", "err", "act", "src"})


class CliBindTest(unittest.TestCase):
    """main() bind resolution: loopback unless explicitly configured.

    Runs the real main() with the singleton check and Bridge stubbed, against a
    throwaway config.json — this is the code path a user actually starts.
    """

    def _run(self, argv, cfg=None):
        import contextlib
        import io
        import tempfile
        from pathlib import Path
        tmp = Path(tempfile.mkdtemp(prefix="ib-cfg-"))
        if cfg is not None:
            (tmp / "config.json").write_text(json.dumps(cfg),
                                             encoding="utf-8")
        real = (bd.singleton_or_exit, bd.redirect_log_if_frozen, bd.Bridge,
                bd.BASE_DIR)
        created = {}

        class FakeBridge:
            def __init__(self, cdp_port, ws_port, log_path=None,
                         bind="127.0.0.1"):
                created.update(cdp_port=cdp_port, ws_port=ws_port,
                               log_path=log_path, bind=bind)

            def run(self):
                created["ran"] = True

        bd.singleton_or_exit = lambda: None
        bd.redirect_log_if_frozen = lambda: None
        bd.Bridge = FakeBridge
        bd.BASE_DIR = tmp
        argv0 = sys.argv
        sys.argv = ["bridge_daemon.py"] + list(argv)
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                bd.main()
        finally:
            sys.argv = argv0
            (bd.singleton_or_exit, bd.redirect_log_if_frozen, bd.Bridge,
             bd.BASE_DIR) = real
        self.assertTrue(created.get("ran"), "Bridge.run() was not reached")
        return created, buf.getvalue()

    def test_defaults_to_loopback(self):
        got, out = self._run([])
        self.assertEqual(got["bind"], "127.0.0.1")
        self.assertNotIn("WARNING", out)

    def test_config_values_are_honoured(self):
        got, out = self._run([], {"port": 9999, "cdp_port": 9333,
                                  "log": "x.jsonl", "bind": "127.0.0.1"})
        self.assertEqual((got["ws_port"], got["cdp_port"], got["log_path"]),
                         (9999, 9333, "x.jsonl"))
        self.assertEqual(got["bind"], "127.0.0.1")
        self.assertNotIn("WARNING", out)

    def test_lan_bind_requires_explicit_config_and_warns(self):
        got, out = self._run([], {"bind": "0.0.0.0"})
        self.assertEqual(got["bind"], "0.0.0.0")
        self.assertIn("WARNING", out)
        self.assertIn("authentication", out)

    def test_lan_bind_via_cli_warns(self):
        got, out = self._run(["--bind", "0.0.0.0"])
        self.assertEqual(got["bind"], "0.0.0.0")
        self.assertIn("WARNING", out)

    def test_config_without_bind_key_says_how_to_expose(self):
        got, out = self._run([], {"port": 8787})
        self.assertEqual(got["bind"], "127.0.0.1")
        self.assertIn('no "bind" key', out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
