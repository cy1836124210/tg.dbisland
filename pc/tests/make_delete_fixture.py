"""Extract one real request record from capture/hook.jsonl into a small fixture.

Used once to create pc/tests/fixtures/batch_operate_delete.json — the
desktop client's own successful conversation-delete call (cmd 1125), which the
protocol test compares our generated envelope against.

    python pc/tests/make_delete_fixture.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "capture", "hook.jsonl")
OUT = os.path.join(HERE, "fixtures", "batch_operate_delete.json")

KEEP = ("url", "method", "reqHeaders", "reqBody", "status", "resBody")


def main():
    with open(SRC, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "/im/conversation/batch_operate" not in line:
                continue
            try:
                rec = json.loads(line)
            except Exception:
                continue
            if '"operate_type":8' not in (rec.get("reqBody") or ""):
                continue
            out = {k: rec.get(k) for k in KEEP}
            with open(OUT, "w", encoding="utf-8") as g:
                json.dump(out, g, ensure_ascii=False, indent=1)
            print("wrote", OUT, os.path.getsize(OUT), "B")
            print("reqBody:", out["reqBody"][:200])
            return
    print("no matching record found", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
