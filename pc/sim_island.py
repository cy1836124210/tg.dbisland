#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""岛卡片「电脑 + 手机 都在响应」的模拟脚本（只走 adb，不需要真的开电脑端）。

用法：
    python pc/sim_island.py both     # 模拟电脑在线且有响应，并触发一条豆包回答
    python pc/sim_island.py off      # 取消模拟（岛上只显示手机）
    python pc/sim_island.py show     # 只看 App 日志里卡片帧的来源信息

原理：App 会读 filesDir/ib_sim.txt，内容含 "pc on" 就把它当「电脑在线且有响应」。
为什么放在 filesDir：那里只有本应用自己和 root 能写，第三方应用写不进去，
所以 release 包也能带这个通道（不需要先装 debug 包、也不需要真的连电脑端）。
它只影响「岛上显示什么」，不参与发送/鉴权。

真实判断（非模拟）见 IslandBridge.sources()：电脑必须 SSE 连着 **且** 最近
PC_LIVE_MS(20s) 内真收到过电脑推来的东西（含心跳行），光「在线」不算。
"""
import base64
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB", r"D:\tool\android-sdk\platform-tools\adb.exe")
PKG = "com.islandbridge"
SIM = "/data/data/%s/files/ib_sim.txt" % PKG
TUNE = "/data/data/%s/files/ib_lyric.txt" % PKG
PROMPT = "/data/local/tmp/ib_p.txt"


def adb(*args):
    """adb <args>，返回 stdout 文本。"""
    r = subprocess.run([ADB] + list(args), capture_output=True)
    return r.stdout.decode("utf-8", "ignore")


def shell(cmd):
    """adb shell '<cmd>' —— 整条命令作为**一个**参数传给设备 shell。"""
    return adb("shell", cmd)


def su(cmd):
    """root 执行；cmd 里不要出现单引号。"""
    return shell("su -c '" + cmd + "'")


def write_file(path, text):
    """用 base64 写文件，避开引号/中文转义（PowerShell、adb 两层转义都容易踩）。"""
    b = base64.b64encode(text.encode("utf-8")).decode("ascii")
    su("echo %s | base64 -d > %s; chmod 644 %s" % (b, path, path))


def frames(pattern="岛card", n=8):
    out = adb("logcat", "-d")
    hit = [l for l in out.splitlines() if pattern in l]
    return [l.split(pattern + " ", 1)[-1] for l in hit[-n:]]


def main():
    mode = (sys.argv[1] if len(sys.argv) > 1 else "both").lower()

    if mode == "off":
        write_file(SIM, "pc off\n")
        print("已取消模拟：岛上应只列出手機")
        for f in frames():
            print("  " + f)
        return

    if mode == "show":
        for f in frames():
            print("  " + f)
        return

    # both
    write_file(SIM, "pc on\n")
    print("ib_sim.txt = 'pc on'  →  模拟「电脑在线且有响应」（10 分钟）")
    print("（调参文件 ib_lyric.txt 第 1 行 = 胶囊分组长，第 2 行 = 正文一行字数）")
    adb("logcat", "-c")

    # 触发一条真实回答：走豆包自己的分享入口，不需要电脑端参与
    su("am force-stop com.larus.nova")
    time.sleep(2)
    su("am start-service -n com.larus.nova/com.ss.android.message.NotifyService")
    time.sleep(5)
    su('am start -a android.intent.action.SEND -t text/plain '
       '--es android.intent.extra.TEXT "$(cat %s)" '
       '-n com.larus.nova/com.larus.home.impl.OuterShareDeliverActivity' % PROMPT)
    time.sleep(16)

    print("App 卡片帧（srcs = 明细卡里并列的来源；both 时应为 电脑,手机）：")
    for f in frames():
        print("  " + f)


if __name__ == "__main__":
    main()
