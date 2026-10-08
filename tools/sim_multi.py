#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多会话 / 副岛 / 「主岛归先到者」的模拟脚本（只走 adb + root，不需要真豆包回答）。

用法：
    python pc/sim_multi.py start       # 造两个不同 cid 的会话（两张卡同时在线）
    python pc/sim_multi.py ack sim-a   # 把某个会话「收掉」（等价于点「我知道了」）
    python pc/sim_multi.py expand sim-a   # 等价于宿主回调 onExpanded(reply:sim-a)
    python pc/sim_multi.py reply sim-a 你好  # 等价于宿主回调 onReply（岛上回复框）
    python pc/sim_multi.py collapse sim-a # 等价于宿主回调 onCollapsed
    python pc/sim_multi.py show        # 只看相关日志（岛card / 主岛 / 岛动作…）
    python pc/sim_multi.py clear       # 清空模拟文件
    python pc/sim_multi.py run         # 一条龙：start → 看日志 → ack 先到者 → 再看日志
    python pc/sim_multi.py replytest   # 第 40 条：展开→换 MessageCard→回复→已发送
    python pc/sim_multi.py run --out log.txt   # 同上，另存一份 UTF-8 证据文本

原理：App 每秒轮询 filesDir/ib_sim.txt（只有 root/adb 能写），把里面一行一个
JSON 事件**走和真实推流同一条分发路径**投进去（见 IslandBridge.simPoller /
dispatchSim）。所以造两个 cid 的会话就能验证：

  · 两张卡同时在线        —— 日志里同时出现 `岛card id=reply:sim-a` 与
                            `岛card id=reply:sim-b`
  · 先到者占主岛          —— `岛card id=reply:sim-a prio=high`（HIGH）
  · 后来者进副岛          —— `岛card id=reply:sim-b prio=default`（DEFAULT）
  · 先到者被收掉后升级    —— `先到者已被收掉 → 副岛升级为主岛 … prio=high`
                            + sim-b 重新 post 一帧 `岛card … prio=high`

`n` 是单调序号（这里取 unix 毫秒 + 序号），App 用它去重，重复跑不会重复投递。
日志里**不打印回复正文**，只有长度 / 状态 / cid 后 6 位。

**为什么每次写文件后都要 `content query` 一下**：ColorOS 会把退到后台的 App
冻住（进程还在，线程全停），而这条模拟通道是 App **自己轮询**文件的 —— 冻住
就不会轮询。一次 Binder 事务进 App 的 provider 会把进程解冻（这也是模块在
真实场景里的做法：保活窗内每 3s `callProvider` 一次；README 里把它记作
「纯解冻探针」）。所以脚本在等待期间每秒 poke 一次，等价于真实推流到达时
把 App 唤醒的那一下。
"""
import base64
import json
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB", r"D:\tool\android-sdk\platform-tools\adb.exe")
PKG = "com.tg.dbisland"
SIM = "/data/data/%s/files/ib_sim.txt" % PKG
AUTH = "content://com.tg.dbisland.events"
OUT = None          # --out <path>：额外写一份 UTF-8 证据文件（控制台会乱码）

# 只看这些关键字：卡片帧 / 主岛归属 / 岛动作 / 模拟通道 / 内容结束 / 第 40 条回复链路
KEYS = ("岛card", "主岛", "副岛", "岛动作", "模拟通道", "岛收起", "岛 end",
        "构建回复卡片", "被拒", "岛展开", "岛回复", "岛上回复", "卡片形态",
        "已递交豆包进程发送", "已发送", "回复[", "已按上限裁", "启动 v")


def adb(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True)
    return r.stdout.decode("utf-8", "ignore")


def shell(cmd):
    return adb("shell", cmd)


def su(cmd):
    """root 执行；cmd 里不要出现单引号。"""
    return shell("su -c '" + cmd + "'")


def poke():
    """一次 Binder 事务进 App 的 EventProvider → ColorOS 解冻进程（纯解冻探针）。"""
    return shell("content query --uri %s" % AUTH)


def wait_awake(sec):
    """等待 sec 秒，其间每秒 poke 一次让 App 保持可运行（见文件头说明）。"""
    end = time.time() + sec
    while time.time() < end:
        poke()
        time.sleep(1.0)


def write_sim(text):
    """用 base64 写模拟文件，避开引号/中文转义；写临时文件再 mv（避免半行）。

    `chmod 666` 是**第 41 条**加的：App 处理完会把这个文件清空（防重启重放），
    而 `su` 写出来的文件属主是 root、默认 644 —— App 读得到写不了，于是每秒
    一条 `FileNotFoundException`。给全局写权限后 App 就能自己清。
    """
    b = base64.b64encode(text.encode("utf-8")).decode("ascii")
    su("echo %s | base64 -d > %s.tmp && mv -f %s.tmp %s && chmod 666 %s"
       % (b, SIM, SIM, SIM, SIM))


def lines():
    out = adb("logcat", "-d")
    return [l for l in out.splitlines() if any(k in l for k in KEYS)]


# 证据文本：控制台在中文 Windows 上默认按 OEM 代码页解码，中文会乱码，
# 所以同时（可选）写一份 UTF-8 文件：`--out <path>`。
BUF = []


def say(msg):
    BUF.append(msg)
    try:
        print(msg)
    except UnicodeEncodeError:
        print(msg.encode("utf-8", "replace").decode("ascii", "replace"))


def flush_out():
    if OUT:
        with open(OUT, "w", encoding="utf-8") as f:
            f.write("\n".join(BUF) + "\n")


def show(n=48):
    for l in lines()[-n:]:
        # 去掉 logcat 的时间戳/pid 前缀，只留 tag 之后的内容
        say("  " + l.split("IslandBridge: ", 1)[-1])


def batch(events, base):
    """events: [(t, cid, mid, kind, text, src, extra)] → 一批 JSON 行。

    extra 是直接拼进去的 JSON 片段（如 `"a":"ack"`），没有就给 None。
    文本一律走 `json.dumps` 转义（正文里可能有引号 / 反斜杠 / 换行，
    第 41 条起模拟通道也会把这些文本写进聊天记录，不能靠手拼）。
    """
    out = []
    n = base
    for (t, cid, mid, kind, text, src, extra) in events:
        n += 1
        o = '{"n":%d,"t":"%s","cid":"%s","mid":"%s"' % (n, t, cid, mid)
        if kind:
            o += ',"kind":"%s"' % kind
        if text:
            o += ',"text":' + json.dumps(text, ensure_ascii=False)
        if src:
            o += ',"src":"%s"' % src
        if extra:
            o += "," + extra
        if t == "chat.start":
            o += ',"cname":' + json.dumps("模拟%s" % cid, ensure_ascii=False)
        o += "}"
        out.append(o)
    return "".join(x + "\n" for x in out)


def do_start(base):
    """两个会话同时在答：sim-a 先到（电脑来源），sim-b 后到（手机来源）。"""
    ev = [
        ("chat.start", "sim-a", "ma", "", "", "pc", None),
        ("chat.delta", "sim-a", "ma", "text", "先到的会话在答第一句", "pc", None),
        ("chat.delta", "sim-a", "ma", "text", "，第二句。", "pc", None),
        ("chat.start", "sim-b", "mb", "", "", "", None),
        ("chat.delta", "sim-b", "mb", "text", "后到的会话同时在答", "", None),
        ("chat.delta", "sim-b", "mb", "text", "，两条卡同时在线。", "", None),
        ("chat.end", "sim-a", "ma", "", "", "pc", None),
        ("chat.end", "sim-b", "mb", "", "", "", None),
    ]
    write_sim(batch(ev, base))
    poke()
    print("已写入两会话事件（先到 sim-a / 后到 sim-b）")


def do_ack(cid, base):
    write_sim(batch([("sim.action", cid, "", "", "", "", '"a":"ack"')], base))
    poke()
    print("已投「收掉 %s」（等价于岛上点「我知道了」）" % cid)


def do_expand(cid, base):
    write_sim(batch([("sim.expand", cid, "", "", "", "", None)], base))
    poke()
    print("已投「展开 %s」（等价于宿主 onExpanded(reply:%s)）" % (cid, cid))


def do_collapse(cid, base):
    write_sim(batch([("sim.collapse", cid, "", "", "", "", None)], base))
    poke()
    print("已投「收起 %s」（等价于宿主 onCollapsed）" % cid)


def do_reply(cid, text, base):
    """岛上回复框那条链路：宿主 onReply(id, text) → 本应用真正发送。"""
    ev = [("sim.reply", cid, "", "", text, "", None)]
    write_sim(batch(ev, base))
    poke()
    print("已投「岛上回复 %s」（等价于宿主 onReply(reply:%s, %d 字)）"
          % (cid, cid, len(text)))


def do_replytest(base):
    """第 40 条一条龙：造会话 → 展开 → 回复 → 收起。

    **必须一次写完**：第 41 条起 App 处理完就把文件清空（防重启重放），
    分几次写会互相覆盖。全部走宿主回调那一套函数。
    """
    ev = [
        ("chat.start", "sim-a", "ma", "", "", "pc", None),
        ("chat.delta", "sim-a", "ma", "text", "先到的会话在答第一句", "pc", None),
        ("chat.delta", "sim-a", "ma", "text", "，第二句。", "pc", None),
        ("chat.end", "sim-a", "ma", "", "", "pc", None),
        ("sim.expand", "sim-a", "", "", "", "", None),
        ("sim.reply", "sim-a", "", "", "岛上回复链路验证", "", None),
    ]
    write_sim(batch(ev, base))
    poke()
    print("已写入第 40 条链路事件（start/delta/end → expand → reply）")


def main():
    global OUT
    argv = [a for a in sys.argv[1:]]
    if "--out" in argv:
        i = argv.index("--out")
        OUT = argv[i + 1]
        del argv[i:i + 2]
    mode = (argv[0] if argv else "run").lower()
    base = int(time.time() * 1000)

    if mode == "clear":
        write_sim("")
        say("ib_sim.txt 已清空")
        flush_out()
        return
    if mode == "show":
        show()
        flush_out()
        return
    if mode == "start":
        do_start(base)
        wait_awake(5)
        say("App 日志：")
        show()
        flush_out()
        return
    if mode == "ack":
        cid = argv[1] if len(argv) > 1 else "sim-a"
        do_ack(cid, base)
        wait_awake(5)
        say("App 日志：")
        show()
        flush_out()
        return
    if mode == "expand":
        cid = argv[1] if len(argv) > 1 else "sim-a"
        do_expand(cid, base)
        wait_awake(5)
        say("App 日志：")
        show()
        flush_out()
        return
    if mode == "collapse":
        cid = argv[1] if len(argv) > 1 else "sim-a"
        do_collapse(cid, base)
        wait_awake(5)
        say("App 日志：")
        show()
        flush_out()
        return
    if mode == "reply":
        cid = argv[1] if len(argv) > 1 else "sim-a"
        text = argv[2] if len(argv) > 2 else "岛上回复链路验证"
        do_reply(cid, text, base)
        wait_awake(10)
        say("App 日志：")
        show()
        flush_out()
        return

    # replytest：第 40 条一条龙（展开 → 回复）
    if mode == "replytest":
        adb("logcat", "-c")
        write_sim("")
        wait_awake(2)
        say("== 1) 造一条会话 sim-a 并展开 → 回复（一次投递，第 41 条起处理完即清空）==")
        do_replytest(base)
        wait_awake(12)
        show(n=60)
        say("== 2) sim.collapse（宿主 onCollapsed → 同一 id 切回 GenericCard）==")
        do_collapse("sim-a", base + 200)
        wait_awake(4)
        show(n=60)
        flush_out()
        return

    # run：一条龙
    say("== 1) 清空模拟文件 + 清 logcat ==")
    write_sim("")
    adb("logcat", "-c")
    poke()
    time.sleep(1.5)
    say("== 2) 造两个会话（先到 sim-a，后到 sim-b）==")
    do_start(base)
    wait_awake(5)
    show()
    say("== 3) 收掉先到者 sim-a，看 sim-b 是否升为主岛（HIGH）==")
    do_ack("sim-a", base + 100)
    wait_awake(5)
    show()
    say("== 4) 收掉 sim-b，主岛应空出 ==")
    do_ack("sim-b", base + 200)
    wait_awake(5)
    show()
    say("== done（日志不打印回复正文，只有长度/状态/cid 后 6 位）==")
    flush_out()


if __name__ == "__main__":
    main()
