#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 45 条：悬浮窗回复 + 三按钮 的自动化验证脚本（只靠 adb + root）。

用法（在仓库根目录跑）：
    D:\\tool\\miniconda\\python.exe tools/overlay_test.py buttons
    D:\\tool\\miniconda\\python.exe tools/overlay_test.py sim
    D:\\tool\\miniconda\\python.exe tools/overlay_test.py type "<ascii 文本>"
    D:\\tool\\miniconda\\python.exe tools/overlay_test.py log [n]
    D:\\tool\\miniconda\\python.exe tools/overlay_test.py real "<提示词>"
    D:\\tool\\miniconda\\python.exe tools/overlay_test.py shot dev/ov_panel.png

设计要点：
  · 模拟通道（`filesDir/ib_sim.txt`）里的 `sim.overlay` 走的正是**用户点岛上
    「回复」按钮**调用的那个函数（`IslandBridge.onAction(id, ACT_REPLY)`），
    所以「无人值守验证」跑的就是真实那条路；
  · 悬浮窗是**本 App**（com.tg.dbisland）的 `TYPE_APPLICATION_OVERLAY` 窗口，
    窗口可聚焦 → `adb shell input text` / `input keyevent 66` 直接打进面板的
    EditText 并触发发送（不需要软键盘）；
  · 真机上想看到 `已发送到手机豆包`，cid 必须是**真实豆包会话**
    （模拟 cid 没有对应会话，模块发不出去）→ 用 `real` 子命令走用户规定的那条
    提示词路径，再从日志里取真实 cid。
"""
import base64
import json
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", r"D:\tool\android-sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("SERIAL", "d666858b")
PKG = "com.tg.dbisland"
# **真实物理路径**：`/data/data/<pkg>` 在 Android 上只是指向 `/data/user/0/<pkg>`
# 的符号链接，App 自己（filesDir）用的就是后者 —— 两个都通，但日志里 App 报的是
# `/data/user/0/...`，脚本统一用同一条路径，排查时不会看着两套路径发懵。
DATA = "/data/user/0/%s" % PKG
SIM = DATA + "/files/ib_sim.txt"
LOG_DIR = DATA + "/files/logs"
CHAT_DIR = DATA + "/files/chat"
AUTH = "content://com.tg.dbisland.events"
DOUBAO = "com.larus.nova"
OUT = None


def adb(*args):
    r = subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True)
    return r.stdout.decode("utf-8", "ignore")


def shell(cmd):
    return adb("shell", cmd)


def su(cmd):
    return shell("su -c '" + cmd + "'")


def poke():
    """一次 Binder 事务进 App 的 EventProvider：顺带把被 ColorOS 冻住的进程解冻。"""
    return shell("content query --uri %s" % AUTH)


def write_sim(text):
    b = base64.b64encode(text.encode("utf-8")).decode("ascii")
    su("echo %s | base64 -d > %s.tmp && mv -f %s.tmp %s && chmod 666 %s"
       % (b, SIM, SIM, SIM, SIM))
    poke()


def log_lines():
    out = adb("logcat", "-d", "-s", "IslandBridge:I")
    return [l.split("IslandBridge: ", 1)[-1].rstrip() for l in out.splitlines()
            if "IslandBridge: " in l]


def grep(*pats):
    res = []
    for l in log_lines():
        if all(p in l for p in pats):
            res.append(l)
    return res


def say(msg):
    try:
        print(msg)
    except UnicodeEncodeError:
        print(msg.encode("utf-8", "replace").decode("ascii", "replace"))


BUF = []


def note(msg):
    BUF.append(msg)
    say(msg)


def flush():
    if OUT:
        with open(OUT, "w", encoding="utf-8") as f:
            f.write("\n".join(BUF) + "\n")


def wait_for(pats, sec, tag):
    """等日志里出现全部 pats（all in 同一行）。"""
    end = time.time() + sec
    while time.time() < end:
        hit = grep(*pats)
        if hit:
            note("  ✔ [%s] %s" % (tag, hit[-1]))
            return hit[-1]
        poke()
        time.sleep(0.7)
    note("  ✘ [%s] %ds 内没等到 %s" % (tag, sec, pats))
    return None


def cur_cid():
    """从最近一条 `主岛归先到者 … cid=xxxxxx` 拿真实 cid（后 6 位）。"""
    h = grep("主岛归先到者")
    if not h:
        return None
    m = re.search(r"cid=(\S+)", h[-1])
    return m.group(1) if m else None


# ----------------------------------------------------------------- 子命令

def do_buttons():
    """只看「三个按钮有没有被宿主接受」那一行 + 卡片形态。"""
    note("== 岛上按钮组合（第 45 条）==")
    for l in grep("岛按钮组合被接受", "卡片形态"):
        note("  " + l)
    rej = grep("被拒")
    note("  被拒行数: %d" % len(rej))
    for l in rej[-4:]:
        note("  " + l)


def do_sim(cid="sim-ol"):
    """造一条合成会话 + 触发「回复」按钮（等价于用户点岛上的回复）。"""
    note("== sim.overlay：等价于用户点岛上「回复」按钮（cid=%s）==" % cid)
    ev = (
        '{"n":1,"t":"chat.start","cid":"%s","mid":"m1","cname":"悬浮窗验证"}\n' % cid +
        '{"n":2,"t":"chat.delta","cid":"%s","mid":"m1","kind":"text",'
        '"text":"这是用来验证悬浮窗回复的合成会话。"}\n' % cid +
        '{"n":3,"t":"chat.end","cid":"%s","mid":"m1"}\n' % cid)
    write_sim(ev)
    time.sleep(2.5)
    write_sim('{"n":4,"t":"sim.overlay","cid":"%s"}\n' % cid)
    wait_for(["模拟通道悬浮窗回复"], 12, "sim.overlay 分发")
    wait_for(["岛动作:回复"], 12, "onAction(reply)")
    wait_for(["面板已弹出"], 12, "面板出现")


def do_type(text):
    """往（已经弹出的）面板里打字并回车发送 —— 面板有焦点才收得到。"""
    note("== 注入文字并回车（%d 字，ASCII）==" % len(text))
    shell("input text '%s'" % text)
    time.sleep(0.8)
    shell("input keyevent 66")
    wait_for(["悬浮窗回复：提交"], 12, "回车触发发送")
    wait_for(["提交"], 12, "提交日志")


def do_log(n=40):
    note("== 最近的 IslandBridge 日志（尾部 %d 行）==" % n)
    for l in log_lines()[-n:]:
        note("  " + l)


def do_shots(path):
    adb("exec-out", "screencap", "-p")
    r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                       capture_output=True)
    with open(path, "wb") as f:
        f.write(r.stdout)
    note("截图 -> %s (%d 字节)" % (path, len(r.stdout)))


def do_real(prompt):
    """按用户规定的路径触发一次真实回答。"""
    note("== 触发真实回答（force-stop → NotifyService → OuterShareDeliverActivity）==")
    b = base64.b64encode(prompt.encode("utf-8")).decode("ascii")
    su("echo %s | base64 -d > /data/local/tmp/ib_p.txt" % b)
    shell("am force-stop %s" % DOUBAO)
    time.sleep(1.5)
    su("am start-service -n %s/com.ss.android.message.NotifyService" % DOUBAO)
    time.sleep(2.0)
    su('am start -a android.intent.action.SEND -t text/plain '
       '--es android.intent.extra.TEXT "$(cat /data/local/tmp/ib_p.txt)" '
       '-n %s/com.larus.home.impl.OuterShareDeliverActivity' % DOUBAO)
    note("  提示词已投出，等 chat.start …")
    wait_for(["主岛归先到者"], 60, "chat.start / 主岛")
    cid = cur_cid()
    note("  真实 cid（后 6 位）= %s" % cid)
    note("  等回答结束 …")
    wait_for(["st=回答结束"], 120, "回答结束")
    return cid


def do_restart():
    """干净重启 App。

    **必须做这一步**：`adb install -r` 之后如果老进程还活着（真机实测：ColorOS 上
    旧进程能在覆盖安装后继续存在），跑的仍是**旧代码**、而且它的 EventProvider
    已经不在 AMS 里了 —— 模块侧会一直报
    `call fail: IllegalArgumentException Unknown authority com.tg.dbisland.events`，
    表面上像「模块没生效 / 模拟通道没反应」。force-stop 后再起就正常。
    """
    note("== 干净重启 App（force-stop → am start）==")
    shell("am force-stop %s" % PKG)
    time.sleep(2)
    adb("logcat", "-c")
    su("am start -n %s/com.tg.dbisland.MainActivity" % PKG)
    end = time.time() + 20
    while time.time() < end:
        if grep("启动 v"):
            break
        time.sleep(0.7)
    for l in grep("启动 v", "固化:"):
        note("  " + l)
    note("  pid = %s" % shell("pidof %s" % PKG).strip())


def main():
    global OUT
    argv = list(sys.argv[1:])
    if "--out" in argv:
        i = argv.index("--out")
        OUT = argv[i + 1]
        del argv[i:i + 2]
    if not argv:
        note(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd == "buttons":
        do_buttons()
    elif cmd == "sim":
        do_sim(rest[0] if rest else "sim-ol")
    elif cmd == "type":
        do_type(rest[0])
    elif cmd == "log":
        do_log(int(rest[0]) if rest else 40)
    elif cmd == "shot":
        do_shots(rest[0] if rest else "dev/ov_panel.png")
    elif cmd == "real":
        do_real(rest[0])
    elif cmd == "restart":
        do_restart()
    elif cmd == "files":
        note(shell("su -c 'ls -l %s; echo ---; ls -l %s'" % (LOG_DIR, CHAT_DIR)))
    elif cmd == "tail":
        note(shell("su -c 'tail -%d $(ls -t %s/*.log | head -1)'"
                   % (int(rest[0]) if rest else 25, LOG_DIR)))
    elif cmd == "chat":
        note(shell("su -c 'tail -6 %s/%s.jsonl'" % (CHAT_DIR, rest[0])))
    elif cmd == "poke":
        note(poke())
    elif cmd == "cid":
        note(str(cur_cid()))
    else:
        note("未知子命令: %s" % cmd)
        return 2
    flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
