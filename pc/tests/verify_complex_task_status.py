# Lead-side independent verification of pc/ADAPTATION.md §3.5:
#   complex_task_block.status: 4 = "已开始工作"(organizer), 2 = "已完成工作"(sub_agent)
# For every capture record that mentions complex_task_block, parse the record as
# JSON, walk it, and for each dict that HAS a complex_task_block, read that
# dict's own `status` plus any nearby human-readable summary text. Then print the
# joint distribution (ctb_status, summary).
import collections
import json

PATH = r"D:\aiwork\doubaoni\capture\hook.jsonl"
KEY = "complex_task_block"
joint = collections.Counter()
parsed = 0
bad = 0


def walk(node, found):
    if isinstance(node, dict):
        if KEY in node and isinstance(node[KEY], (dict, str)):
            found.append(node)
        for v in node.values():
            walk(v, found)
    elif isinstance(node, list):
        for v in node:
            walk(v, found)
    elif isinstance(node, str) and KEY in node:
        try:
            walk(json.loads(node), found)
        except Exception:
            pass


def label(node):
    """Collect the human-readable text near a complex_task_block."""
    txt = []

    def rec(n):
        if isinstance(n, dict):
            for k, v in n.items():
                if k in ("summary", "title", "name", "text", "brief") and isinstance(v, str):
                    txt.append(v)
                elif k == KEY and isinstance(v, dict):
                    rec(v)
                elif isinstance(v, (dict, list)):
                    rec(v)
        elif isinstance(n, list):
            for v in n:
                rec(v)

    rec(node)
    return " | ".join(txt)


with open(PATH, "r", encoding="utf-8", errors="replace") as fh:
    for raw in fh:
        if KEY not in raw:
            continue
        try:
            rec = json.loads(raw)
            parsed += 1
        except Exception:
            bad += 1
            continue
        hits = []
        walk(rec, hits)
        for h in hits:
            ctb = h.get(KEY)
            if not isinstance(ctb, dict):
                continue
            st = ctb.get("status")
            summary = label(h)
            flags = []
            if "已开始工作" in summary:
                flags.append("已开始工作")
            if "已完成工作" in summary:
                flags.append("已完成工作")
            at = h.get("agent_type") or ctb.get("agent_type") or "?"
            joint[(st, tuple(flags), at)] += 1

print(f"records mentioning {KEY}: parsed={parsed} unparsable={bad}")
print("complex_task_block.status | summary flags | agent_type | count")
for (st, flags, at), n in sorted(joint.items(), key=lambda kv: -kv[1]):
    print(f"  {str(st):<5} | {','.join(flags) or '-':<14} | {at:<12} | {n}")
