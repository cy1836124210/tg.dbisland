#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""simulate_doubao.py —— 模拟「豆包」把内容推给手机，用来在没有真豆包对话的情况下
测试手机端的收帧链路、卡片上岛、进度条、删除按钮等。

两条通道，都是真实链路（不是造一个假接口）：

  --via tcp    电脑端豆包 → 手机 8799 监听器（listen.sh）
               帧格式： <TOKEN>\\t<JSON>\\n   每行一帧
               落岛后的卡片来源显示为「电脑」(handler.sh 里写死 src=pc)。
               手机端 8799 默认只绑 127.0.0.1，所以用 --adb-forward 走 USB
               隧道连过去（不会把端口暴露到局域网）。

  --via queue  手机端豆包 hook → 事件队列文件（模拟 LSPosed 模块在 com.larus.nova
               进程内写的 ibq.log），再由 root worker relay.sh 用 Binder 送进 App。
               落岛后的卡片来源显示为「手机」。
               需要 adb + root，且 relay.sh 正在运行。

常见用法：

  # 1) 最常用：USB 隧道 + 自动读手机里的令牌，推一段完整回答
  python pc/simulate_doubao.py --adb-forward --scenario full

  # 2) 自定义回答内容（会按真实增量节奏切块推送）
  python pc/simulate_doubao.py --adb-forward --text "你好，这是电脑端推送的模拟回答。"

  # 3) 直接连局域网手机（此时手机 listen.sh 必须配了 BIND=0.0.0.0，不推荐）
  python pc/simulate_doubao.py --host 192.168.1.20 --token <令牌>

  # 4) 模拟手机端豆包 hook 写队列，测 relay.sh → Binder → 上岛
  python pc/simulate_doubao.py --via queue --scenario full

  # 5) 安全回归：不带令牌 / 错令牌 / 非 JSON —— 都应该被丢且只留一条 dropped 日志
  python pc/simulate_doubao.py --adb-forward --no-token
  python pc/simulate_doubao.py --adb-forward --bad-token
  python pc/simulate_doubao.py --adb-forward --garbage

  # 6) 只看帧、不发送（离线检查协议字段）
  python pc/simulate_doubao.py --scenario all --dry-run

事件类型与字段以 pc/protocol.md 为准则；节流按该文档「~300ms 合并」的约定，
默认 0.3s 一帧，可用 --interval 调整。
"""

import argparse
import base64
import json
import os
import random
import shutil
import socket
import subprocess
import sys
import time
import uuid

DEFAULT_PORT = 8799
DEFAULT_FORWARD_PORT = 18799
DEFAULT_TOKEN_FILE = "/data/adb/islandbridge/token"
NOVA_QUEUE = "/data/data/com.larus.nova/files/ibq.log"
RELAY_LOG = "/data/local/tmp/islandbridge_relay.log"

# 岛侧字段上限（见 protocol.md「字段上限」）：标题 ≤128、副标题 ≤256、歌词行 ≤512
MAX_TITLE = 128
MAX_SUBTITLE = 256
MAX_LINE = 512

SCENARIOS = ("full", "short", "stream", "think", "plan", "async", "spam", "all")

DEFAULT_TEXT = (
    "这是一条来自电脑端豆包的模拟回答，用来验证手机端卡片是否正常接收。\n\n"
    "1. 收到 chat.start 后，岛上会出现一个 MEDIA 歌词项；\n"
    "2. chat.delta 的每一小段文字都会滚动上屏（手机端约 300ms 合并一次）；\n"
    "3. chat.end 之后，卡片变为「回答完毕」，出现「我知道了 / 删除会话」按钮。\n\n"
    "如果你在岛上看到了这段话，说明电脑端 → 手机 → 星河岛这条链路是通的。"
)


# --------------------------------------------------------------------------- #
# 帧构造（纯函数，便于单测）
# --------------------------------------------------------------------------- #

def rid(n=8):
    return uuid.uuid4().hex[:n]


def cut(text, limit):
    text = "" if text is None else str(text)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def chunk_text(text, min_chunk=6, max_chunk=14, rng=None):
    """把整段回答切成近似真实增量的小块（豆包是按 token 流式吐字的）。"""
    rng = rng or random.Random(0)
    out, i = [], 0
    while i < len(text):
        n = rng.randint(min_chunk, max_chunk)
        out.append(text[i : i + n])
        i += n
    return out or [""]


def build_frames(scenario="full", text=None, cid=None, cname=None, mid=None,
                 total=5, seed=None, repeat=1):
    """返回一个帧列表（每个元素是 dict）。scenario 可以是逗号分隔的多个场景。"""
    rng = random.Random(seed)
    text = DEFAULT_TEXT if text is None else text
    cid = cid or ("sim-conv-" + rid(6))
    cname = cut(cname or "电脑端模拟会话", MAX_TITLE)
    mid = mid or ("sim-mid-" + rid(6))

    wanted = []
    for s in str(scenario).split(","):
        s = s.strip()
        if s:
            wanted.append(s)
    if "all" in wanted:
        wanted = ["full", "plan", "async"]

    frames = []
    for round_no in range(max(1, repeat)):
        m = mid if round_no == 0 else "%s-r%d" % (mid, round_no)
        for s in wanted:
            if s == "full":
                frames += _full(text, cid, cname, m, rng)
            elif s == "short":
                frames += [
                    {"t": "chat.start", "mid": m, "cid": cid, "cname": cname},
                    {"t": "chat.delta", "mid": m, "kind": "text", "text": "收到。"},
                    {"t": "chat.end", "mid": m, "cid": cid, "cname": cname},
                ]
            elif s == "stream":
                frames += [
                    {"t": "chat.start", "mid": m, "cid": cid, "cname": cname},
                    *[{"t": "chat.delta", "mid": m, "kind": "text", "text": c}
                      for c in chunk_text(text, rng=rng)],
                    {"t": "chat.end", "mid": m, "cid": cid, "cname": cname},
                ]
            elif s == "think":
                frames += [
                    {"t": "chat.start", "mid": m, "cid": cid, "cname": cname},
                    {"t": "chat.delta", "mid": m, "kind": "think",
                     "text": "正在理解你的问题…"},
                    {"t": "chat.delta", "mid": m, "kind": "think",
                     "text": "拆解成 3 个子任务…"},
                    {"t": "chat.delta", "mid": m, "kind": "tool",
                     "text": "调用 搜索 工具…"},
                    {"t": "chat.delta", "mid": m, "kind": "tool",
                     "text": "读取 2 个网页…"},
                    {"t": "chat.delta", "mid": m, "kind": "text",
                     "text": "结论：这条链路是通的。"},
                    {"t": "chat.end", "mid": m, "cid": cid, "cname": cname},
                ]
            elif s == "plan":
                tid = "sim-tid-" + rid(6)
                frames.append({"t": "plan.start", "tid": tid,
                               "title": cut("模拟计划任务：下载 3 个文件", MAX_TITLE),
                               "kind": "download"})
                for i in range(1, max(1, total) + 1):
                    frames.append({"t": "plan.progress", "done": i, "total": total,
                                   "name": "第 %d/%d 个文件.part" % (i, total)})
                frames.append({"t": "plan.end", "success": True,
                               "text": "全部完成"})
            elif s == "async":
                frames += [
                    {"t": "chat.start", "mid": m, "cid": cid, "cname": cname},
                    {"t": "chat.delta", "mid": m, "kind": "text",
                     "text": "这个任务有点长，我转到后台继续。"},
                    {"t": "chat.async", "mid": m, "task_id": "sim-task-" + rid(6)},
                ]
            elif s == "spam":  # 节流/合帧压测：默认 3 秒内推 60 帧
                frames.append({"t": "chat.start", "mid": m, "cid": cid,
                               "cname": cname})
                for i in range(60):
                    frames.append({"t": "chat.delta", "mid": m, "kind": "text",
                                   "text": "[%02d]节流压测 " % i})
                frames.append({"t": "chat.end", "mid": m, "cid": cid,
                               "cname": cname})
            else:
                raise SystemExit("未知场景：%s（可选：%s）"
                                 % (s, ", ".join(SCENARIOS)))
    return frames


def _full(text, cid, cname, mid, rng):
    frames = [
        # 会话标题揭晓（真实链路里由 SSE_ACK 学到，这里提前发一次）
        {"t": "chat.conv", "cid": cid, "cname": cname},
        {"t": "chat.start", "mid": mid, "cid": cid, "cname": cname},
        {"t": "chat.delta", "mid": mid, "kind": "think",
         "text": "正在思考…"},
        {"t": "chat.delta", "mid": mid, "kind": "tool",
         "text": "正在联网搜索…"},
    ]
    frames += [{"t": "chat.delta", "mid": mid, "kind": "text", "text": c}
               for c in chunk_text(text, rng=rng)]
    frames.append({"t": "chat.end", "mid": mid, "cid": cid, "cname": cname})
    return frames


# --------------------------------------------------------------------------- #
# 令牌 / adb
# --------------------------------------------------------------------------- #

def which_adb(explicit=None):
    """找 adb。显式指定了路径就必须用它，找不到直接返回 None —— 绝不静默
    回退到 PATH 里的另一个 adb（否则 --adb 写错时会连到别的设备/别的环境）。"""
    if explicit:
        if os.path.sep in explicit or (os.altsep and os.altsep in explicit):
            return explicit if os.path.exists(explicit) else None
        return shutil.which(explicit)
    env = os.environ.get("ADB")
    if env:
        if os.path.sep in env or (os.altsep and os.altsep in env):
            if os.path.exists(env):
                return env
        else:
            p = shutil.which(env)
            if p:
                return p
        return None
    return shutil.which("adb")


def adb_cmd(adb, serial, *args):
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    return cmd + list(args)


def adb_shell(adb, serial, script, timeout=25):
    """su -c 执行一段 sh；返回 (rc, stdout+stderr)。"""
    p = subprocess.run(adb_cmd(adb, serial, "shell", "su -c %s" % json.dumps(script)),
                       capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def read_token(args):
    if args.no_token:
        return ""
    if args.bad_token:
        return "dead" * 4
    if args.token:
        return args.token.strip()
    if args.token_file and os.path.exists(args.token_file):
        return open(args.token_file, "r", encoding="utf-8").read().strip()
    adb = which_adb(args.adb)
    if not adb:
        raise SystemExit("没有令牌，也找不到 adb。请用 --token 指定，或用 --adb 指定 adb 路径。")
    rc, out = adb_shell(adb, args.serial,
                        "cat %s 2>/dev/null" % DEFAULT_TOKEN_FILE)
    tok = out.strip().splitlines()[-1].strip() if out.strip() else ""
    if not tok:
        raise SystemExit("从手机读令牌失败（%s 不存在？root 组件还没装/没启动？）。"
                         "可用 --token 手动指定。" % DEFAULT_TOKEN_FILE)
    return tok


def setup_forward(args):
    adb = which_adb(args.adb)
    if not adb:
        raise SystemExit("--adb-forward 需要 adb，请用 --adb 指定路径。")
    local = args.forward_port
    p = subprocess.run(adb_cmd(adb, args.serial, "forward", "tcp:%d" % local,
                               "tcp:%d" % args.port),
                       capture_output=True, text=True, timeout=20)
    if p.returncode != 0:
        raise SystemExit("adb forward 失败：%s%s" % (p.stdout, p.stderr))
    print("[sim] adb forward tcp:%d -> 手机 tcp:%d（USB 隧道，手机端口不需要对外）"
          % (local, args.port))
    return "127.0.0.1", local


# --------------------------------------------------------------------------- #
# 发送
# --------------------------------------------------------------------------- #

def send_tcp(frames, host, port, token, interval=0.3, dry_run=False,
             token_style="ok", connect_timeout=6.0):
    """把帧按 <TOKEN>\\t<JSON>\\n 逐行推给手机 8799。返回发送成功的帧数。"""
    lines = []
    for f in frames:
        payload = json.dumps(f, ensure_ascii=False)
        if token_style == "none":
            line = payload
        elif token_style == "garbage":
            line = "这不是 JSON"
        else:
            line = "%s\t%s" % (token, payload)
        lines.append(line)

    if dry_run:
        for i, l in enumerate(lines, 1):
            print("[dry] %3d  %s" % (i, l))
        return 0

    s = socket.create_connection((host, port), timeout=connect_timeout)
    sent = 0
    try:
        for i, line in enumerate(lines, 1):
            s.sendall((line + "\n").encode("utf-8"))
            sent += 1
            if interval > 0 and i < len(lines):
                time.sleep(interval)
        # 给 nc 里的 handler 一点时间把最后几帧走完 Binder
        time.sleep(0.4)
    finally:
        s.close()
    return sent


def send_queue(frames, args):
    """模拟手机端豆包 hook：把 base64 帧追加进 ibq.log，等 relay.sh 取走。"""
    adb = which_adb(args.adb)
    if not adb:
        raise SystemExit("--via queue 需要 adb，请用 --adb 指定路径。")
    blob = "\n".join(base64.b64encode(
        json.dumps(f, ensure_ascii=False).encode("utf-8")).decode("ascii")
        for f in frames)

    local = os.path.join(os.environ.get("TEMP", "/tmp"), "ib_sim_queue.txt")
    with open(local, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(blob + "\n")
    remote = "/data/local/tmp/ib_sim_queue.txt"
    p = subprocess.run(adb_cmd(adb, args.serial, "push", local, remote),
                       capture_output=True, text=True, timeout=30)
    if p.returncode != 0:
        raise SystemExit("adb push 失败：%s%s" % (p.stdout, p.stderr))

    script = (
        "U=$(stat -c %%u /data/data/com.larus.nova 2>/dev/null); "
        "touch %(q)s; "
        "cat %(r)s >> %(q)s; "
        "chown ${U:-1000}:${U:-1000} %(q)s; chmod 600 %(q)s; "
        "rm -f %(r)s; "
        "echo APPENDED; wc -l < %(q)s"
    ) % {"q": NOVA_QUEUE, "r": remote}
    rc, out = adb_shell(adb, args.serial, script)
    if "APPENDED" not in out:
        raise SystemExit("写入队列失败：%s" % out.strip())
    print("[sim] 已把 %d 帧写入手机端队列 %s（等 relay.sh 取走）" % (len(frames), NOVA_QUEUE))
    if args.wait:
        time.sleep(max(2.0, args.interval * len(frames) + 2.0))
    return len(frames)


def tail_phone_log(args, n=6):
    adb = which_adb(args.adb)
    if not adb:
        return
    rc, out = adb_shell(adb, args.serial, "tail -n %d %s 2>/dev/null" % (n, RELAY_LOG))
    out = out.strip()
    if out:
        print("[手机日志] %s 尾部：" % RELAY_LOG)
        for line in out.splitlines():
            print("           " + line)


# --------------------------------------------------------------------------- #

def main(argv=None):
    ap = argparse.ArgumentParser(
        description="模拟豆包把内容推给手机（IslandBridge 收帧链路测试器）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="场景：%s\n示例见文件头注释。" % "、".join(SCENARIOS))
    ap.add_argument("--via", choices=("tcp", "queue"), default="tcp",
                    help="tcp=电脑端推手机 8799；queue=模拟手机端豆包 hook 写队列")
    ap.add_argument("--scenario", default="full", help="场景，逗号分隔；all=full,plan,async")
    ap.add_argument("--text", default=None, help="自定义回答正文（会按真实节奏切块）")
    ap.add_argument("--cid", default=None, help="会话 id")
    ap.add_argument("--cname", default=None, help="会话标题")
    ap.add_argument("--mid", default=None, help="消息 id")
    ap.add_argument("--total", type=int, default=5, help="plan 场景的总步数")
    ap.add_argument("--repeat", type=int, default=1, help="整段重复次数")
    ap.add_argument("--seed", type=int, default=None, help="切块随机种子（便于复现）")
    ap.add_argument("--interval", type=float, default=0.3,
                    help="帧间隔秒数，默认 0.3（与岛侧 300ms 合并约定一致）")
    ap.add_argument("--host", default="127.0.0.1", help="tcp 模式目标主机")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help="手机监听端口，默认 8799")
    ap.add_argument("--adb-forward", action="store_true",
                    help="用 adb forward 把手机 8799 映射到本机端口（推荐；手机不必开局域网）")
    ap.add_argument("--forward-port", type=int, default=DEFAULT_FORWARD_PORT,
                    help="adb forward 的本机端口，默认 18799")
    ap.add_argument("--token", default=None, help="令牌；默认自动从手机读取")
    ap.add_argument("--token-file", default=None, help="从文件读令牌")
    ap.add_argument("--adb", default=None, help="adb 路径（默认找 PATH 里的 adb）")
    ap.add_argument("--serial", default=None, help="adb -s 指定设备")
    # 安全回归用
    ap.add_argument("--no-token", action="store_true", help="故意不带令牌（应被丢弃）")
    ap.add_argument("--bad-token", action="store_true", help="故意用错令牌（应被丢弃）")
    ap.add_argument("--garbage", action="store_true", help="故意发非 JSON（应被丢弃）")
    ap.add_argument("--dry-run", action="store_true", help="只打印帧，不发送")
    ap.add_argument("--wait", action="store_true", help="发完多等一会儿再退出")
    ap.add_argument("--verify", action="store_true", help="发完打印手机端日志尾部")
    args = ap.parse_args(argv)

    frames = build_frames(args.scenario, text=args.text, cid=args.cid,
                          cname=args.cname, mid=args.mid, total=args.total,
                          seed=args.seed, repeat=args.repeat)
    for f in frames:
        if len(json.dumps(f, ensure_ascii=False)) > MAX_LINE + 200:
            print("[warn] 有一帧过长，岛侧可能按 %d 字截断" % MAX_LINE)

    if args.dry_run:
        print("[sim] 场景=%s 共 %d 帧（未发送）" % (args.scenario, len(frames)))
        target = ("queue" if args.via == "queue" else "%s:%d" % (args.host, args.port))
        print("[sim] 目标=%s" % target)
        send_tcp(frames, args.host, args.port, "", dry_run=True,
                 token_style="none")
        return 0

    token_style = "ok"
    if args.no_token:
        token_style = "none"
    elif args.garbage:
        token_style = "garbage"
    token = read_token(args)

    t0 = time.time()
    if args.via == "queue":
        sent = send_queue(frames, args)
    else:
        host, port = args.host, args.port
        if args.adb_forward:
            host, port = setup_forward(args)
        print("[sim] 目标 %s:%d，场景=%s，共 %d 帧，间隔 %.2fs"
              % (host, port, args.scenario, len(frames), args.interval))
        if token_style == "ok":
            print("[sim] 令牌 %s…（%d 位）" % (token[:6], len(token)))
        elif token_style == "none":
            print("[sim] 故意**不带**令牌 —— 预期手机端丢弃并记 'dropped: bad token'")
        elif token_style == "garbage":
            print("[sim] 故意发非 JSON —— 预期手机端丢弃")
        sent = send_tcp(frames, host, port, token, interval=args.interval,
                        token_style=token_style)

    print("[sim] 已发送 %d 帧，用时 %.2fs" % (sent, time.time() - t0))
    if args.verify or args.wait:
        time.sleep(1.0)
        tail_phone_log(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
