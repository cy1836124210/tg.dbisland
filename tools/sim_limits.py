#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 41 条的**上限裁剪**真机验证（临时把 100 MB 调小，跑一次真实裁剪）。

为什么需要它：真机上不可能为了验证裁剪去写满 100 MB（写满也要几分钟 + 100 MB
流量）。所以 [com.tg.dbisland.LogStore.limits] 支持 `filesDir/ib_limits.txt`：

    log=8192        # 日志总量上限（字节）
    chat=6144       # 聊天记录总量上限（字节）

本脚本把上限压到几 KB，再灌一批模拟事件（造 8 条会话 + 让日志被刷爆），
期望看到：

  · `日志已按上限裁掉最旧的 N 片（… KB），当前 x MB / 上限 0 MB` —— 日志侧；
  · `聊天记录已按上限裁掉最旧的 N 条（… KB，当前 … MB / 上限 0 MB）` —— 聊天侧；
  · 会话文件数 < 8（最旧的几份被整份删掉）。

跑完把 `ib_limits.txt` 删掉即回到 100 MB（App 重启后读一次，见 LogStore.limits）。

用法：
    python tools/sim_limits.py run      # 压小上限 → 灌数据 → 看日志 → 复位
    python tools/sim_limits.py reset    # 只删 ib_limits.txt（回到 100 MB）
    python tools/sim_limits.py show     # 只看相关日志
"""
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB", r"D:\tool\android-sdk\platform-tools\adb.exe")
PKG = "com.tg.dbisland"
LOGS = "/data/data/%s/files/logs" % PKG
CHAT = "/data/data/%s/files/chat" % PKG
SIM = "/data/data/%s/files/ib_sim.txt" % PKG
LIMITS = "/data/data/%s/files/ib_limits.txt" % PKG
AUTH = "content://com.tg.dbisland.events"

KEYS = ("已按上限裁", "上限", "固化", "模拟通道已投递", "岛card", "启动 v")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sim_multi import adb, shell, su, poke, wait_awake, write_sim, batch  # noqa: E402

BUF = []


def say(msg):
    BUF.append(msg)
    try:
        print(msg)
    except UnicodeEncodeError:
        print(msg.encode("utf-8", "replace").decode("ascii", "replace"))


def write_limits(text):
    """写 ib_limits.txt（和 sim 文件同一套 base64 写法，避免引号问题）。"""
    import base64
    b = base64.b64encode(text.encode("utf-8")).decode("ascii")
    su("echo %s | base64 -d > %s.tmp && mv -f %s.tmp %s && chmod 666 %s"
       % (b, LIMITS, LIMITS, LIMITS, LIMITS))


def write_sim_blob(text):
    """把一段内容写到模拟文件。

    不能用 `write_sim` 的 base64-in-argv 方式：裁剪验证要写几十 KB 的事件，
    而 Windows 的 CreateProcess 命令行上限是 32KB（实测报
    `[WinError 206] 文件名或扩展名太长`）。所以走「本地临时文件 → adb push」。
    """
    import tempfile
    fd, path = tempfile.mkstemp(suffix=".txt")
    os.close(fd)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    try:
        adb("push", path, "/data/local/tmp/ib_sim_blob.txt")
        su("cp /data/local/tmp/ib_sim_blob.txt %s.tmp && mv -f %s.tmp %s && chmod 666 %s"
           % (SIM, SIM, SIM, SIM))
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def du():
    return su("ls -l %s; echo ---; ls %s; echo ---; wc -c %s/* 2>/dev/null | tail -3"
              % (LOGS, CHAT, CHAT))


def show(n=80):
    out = adb("logcat", "-d")
    for l in out.splitlines():
        if any(k in l for k in KEYS):
            say("  " + l.split("IslandBridge: ", 1)[-1])


def flush_out(path):
    if path:
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(BUF) + "\n")


def run(out=None):
    adb("logcat", "-c")
    say("== 0) 复位：清日志/聊天记录/模拟文件，上限压到 log=8192B chat=6144B ==")
    write_sim("")
    su("rm -rf %s/*.log %s/*.jsonl" % (LOGS, CHAT))
    write_limits("log=8192\nchat=6144\n")
    su("am force-stop %s" % PKG)
    time.sleep(1)
    su("am start -n %s/%s.MainActivity" % (PKG, PKG))
    wait_awake(4)

    say("== 1) 灌 8 条会话（每条几 KB 正文）+ 300 条短动作刷日志 ==")
    ev = []
    for i in range(8):
        cid = "sim-%02d" % i
        ev.append(("chat.start", cid, "m%d" % i, "", "", "", None))
        ev.append(("chat.delta", cid, "m%d" % i, "text",
                   "会话%d的正文：" % i + "填充" * 400, "", None))
        ev.append(("chat.end", cid, "m%d" % i, "", "", "", None))
    # 空 cid 的 ack 只让 App 打一行日志（不会误伤真实豆包会话）
    for i in range(300):
        ev.append(("sim.action", "", "", "", "", "", '"a":"ack"'))
    blob = batch(ev, int(time.time() * 1000))
    # 分两次写（第一次投递后立刻被清空，所以两次都要写）
    write_sim_blob(blob)
    poke()
    wait_awake(12)
    write_sim_blob(blob)
    poke()
    wait_awake(15)

    say("== 2) 裁剪日志（App 侧那一行是证据）==")
    show()
    say("== 3) 磁盘现状（du / 会话文件列表）==")
    say(du())
    flush_out(out)


def reset():
    su("rm -f %s" % LIMITS)
    say("已删除 %s（上限回到 100 MB；App 换片时读取，最多 6 MB 后生效）" % LIMITS)


def main():
    argv = sys.argv[1:]
    out = None
    if "--out" in argv:
        i = argv.index("--out")
        out = argv[i + 1]
        del argv[i:i + 2]
    mode = (argv[0] if argv else "run").lower()
    if mode == "show":
        show()
    elif mode == "reset":
        reset()
    else:
        run(out)
    flush_out(out)


if __name__ == "__main__":
    main()
