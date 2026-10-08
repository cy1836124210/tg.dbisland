"""Regenerate pc/tests/fixtures/*.jsonl from the real capture.

The captures in capture/hook.jsonl use the dev tool's record shape
({"k":"xhr","resBody":"..."}), while the daemon/FeedParser consume the hook's
shape ({"k":"req","ev":"body","url":...,"data":"<base64>"} — this is exactly
what bridge_daemon._grab_body emits). This script converts a couple of real
records verbatim so the regression tests are fed by genuine server payloads
without shipping the 272 MB capture.

Optional tool — the generated fixtures are checked in, so tests do not need
capture/hook.jsonl to exist.

    python pc/tests/make_fixtures.py [capture/hook.jsonl]
"""
import base64
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(HERE)), "capture", "hook.jsonl")
OUT = os.path.join(HERE, "fixtures")

WANT = {
    # file name -> ([substrings the response body must contain], how many)
    "thread_info.jsonl": (["get_thread_info_downlink_body", "thread_status"], 2),
    "abstract_complex_task.jsonl": (
        ["pull_conversation_abstract_downlink_body", "complex_task_block"], 2),
    "conv_info.jsonl": (["get_conv_info_downlink_body",
                         '"conversation_info"'], 3),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    got = {k: [] for k in WANT}
    with open(SRC, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                rec = json.loads(line)
            except Exception:
                continue
            body = rec.get("resBody") or ""
            if not body:
                continue
            for name, (needles, limit) in WANT.items():
                if len(got[name]) >= limit:
                    continue
                if not all(n in body for n in needles):
                    continue
                got[name].append({
                    "k": "req", "ev": "body",
                    "url": rec.get("url", ""), "t": rec.get("t") or 0,
                    "data": base64.b64encode(body.encode("utf-8")).decode(),
                })
            if all(len(got[k]) >= WANT[k][1] for k in WANT):
                break
    for name, recs in got.items():
        path = os.path.join(OUT, name)
        with open(path, "w", encoding="utf-8") as f:
            for r in recs:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"{name}: {len(recs)} record(s) -> {os.path.getsize(path)} B")


if __name__ == "__main__":
    main()
