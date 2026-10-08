package com.tg.dbisland.xposed

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.tg.dbisland.BridgeSecurity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File

/** LSPosed entry (assets/xposed_init).
 *
 *  Scope 1 — com.larus.nova (per D:\aiwork\apk\REVERSE_NOTES):
 *    · OmniHttpCallByNative.writeMetaInfo/writeChunkData — all SSE stream
 *      bytes → MobileFeedParser → normalized events broadcast to
 *      com.tg.dbisland/.BridgeEventReceiver (island pipeline).
 *    · Omni{Message,Conversation,AIJob}Dispatcher — hooked read-only and
 *      logged (field layout unknown; check LSPosed log to refine).
 *    · Doubao process also pings the bridge app every 60s (keep-alive
 *      while Doubao runs).
 *
 *  Scope 2 — android (system_server): periodic keep-alive broadcast to
 *    com.tg.dbisland/.KeepAliveReceiver so the bridge service survives
 *    even when Doubao is closed.
 */
class DoubaoHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "IslandBridge"
        private const val PKG_SELF = "com.tg.dbisland"
        private const val PKG_DOUBAO = "com.larus.nova"
        private const val ACT_EVENT = "com.tg.dbisland.EVENT"
        private const val ACT_EVENT_RELAY = "com.tg.dbisland.EVENT_RELAY"
        private const val ACT_KEEPALIVE = "com.tg.dbisland.KEEPALIVE"
        private const val ACT_SEND = "com.tg.dbisland.SEND"
        private const val ACT_DELETE = "com.tg.dbisland.DELETE"
        /** App → 豆包进程的保活 ping（也走 CONTROL 签名权限的那道闸）。 */
        private const val ACT_PING = "com.tg.dbisland.PING"
        /** App → system_server 的 ColorOS HANS 冻结豁免窗口续期（见 installHansExempt）。 */
        private const val ACT_HANS = "com.tg.dbisland.HANS"

        // ---- 后台保活窗（用户要求）----
        // 规则：豆包**离开前台**就开始保活；窗内**只要有推流就一直保**（每条
        // chat.start/delta/reply 都续期）；**响应结束（chat.end）后重新计 30s**；
        // 彻底静默 30s 才放弃，把豆包交回系统（ColorOS）处理。
        //
        // 为什么只能这么做：钩子住在豆包进程里，进程一旦被冻结连定时器都停了，
        // 所以「放弃」必须由**不会冻结的一方**（本 App 的前台服务）也镜像一份
        // 同样的计时；模块这边只负责开工、续期、如实上报状态。
        private const val KEEPALIVE_IDLE_MS = 20_000L
        /** 活动窗口内低频 ping；空闲时窗口关闭，不发送。 */
        private const val KEEPALIVE_PING_MS = 8_000L
        /** 前台判定去抖：Activity 之间切换时 handleStopActivity 会先于下一个
         *  handleResumeActivity 到达，不加去抖会被误判成「退到后台」。 */
        private const val FG_DEBOUNCE_MS = 800L
        private const val RC_EVENT = "$PKG_SELF.BridgeEventReceiver"
        private const val RC_KEEPALIVE = "$PKG_SELF.KeepAliveReceiver"
        private const val PING_MS = 60_000L
        private const val SYS_PING_MS = 300_000L
        /** 心跳来源标记（放进 Intent 的 "src"）：豆包进程内的 ping 才能证明
         *  模块真的注入成功；system_server 那个即使豆包没开、作用域没勾也照样发。 */
        private const val SRC_DOUBAO = "doubao"
        private const val SRC_SYSTEM = "system"

        @Volatile private var appCtx: Context? = null
        // per-process statics: the same code can be reached from more than
        // one hook (Application.attach AND callApplicationOnCreate, plus the
        // lazy fallback in ensureCtx) — register exactly once per process or
        // one SEND broadcast lands twice and the message is sent twice
        @Volatile private var cmdRegistered = false
        @Volatile private var pingerStarted = false

        /** SEND 去重表（按 App 给的随机 id）。
         *  为什么必须有：豆包在后台被冻住时，App 那条 SEND 广播会被**推迟投递**，
         *  App 等不到回执就会走 root 唤醒 + 重发；如果被推迟的那条随后又被
         *  投递进来，用户就会看到同一条消息被发两遍（豆包还会答两遍）。
         *  同一个用户动作只带一个 id，重发/迟到都共用它 → 这里丢掉重复的。 */
        private val recentSendIds = ArrayDeque<String>()
        private const val RECENT_SEND_MAX = 16

        // ---- 后台保活窗状态（只在豆包主进程里有意义）----
        @Volatile private var fgTrackerInstalled = false
        private var doubaoForeground = true
        private var lastResumeAt = 0L
        @Volatile private var keepAliveOn = false
        private var keepAliveWl: android.os.PowerManager.WakeLock? = null
        private var keepAliveDeadline: Runnable? = null
        private var keepAlivePingR: Runnable? = null
        private var fgDebounce: Runnable? = null
        private var pingLogged = 0

        /** 主线程 Handler —— **绝不能**在 companion 初始化时创建。
         *  LSPosed 是在 `Zygote.specializeAppProcess()` 阶段加载模块类的，那时
         *  新进程的主线程还没 `Looper.prepareMainLooper()`，
         *  `Handler(Looper.getMainLooper())` 会抛
         *  `NullPointerException: Looper.mQueue on a null object reference`，
         *  于是 `<clinit>` 失败 → **整个模块在每个进程都加载不上**
         *  （v1.2 真机踩过：`Failed to load class …DoubaoHookEntry`，
         *  连一条模块日志都打不出来，看起来像"模块没装"）。所以做成惰性获取。 */
        @Volatile private var mainHandlerRef: Handler? = null
        private fun mainHandler(): Handler? {
            mainHandlerRef?.let { return it }
            return try {
                val lo = Looper.getMainLooper() ?: return null
                Handler(lo).also { mainHandlerRef = it }
            } catch (_: Throwable) { null }
        }

        /** 丢到主线程；拿不到主线程时（极早期）退回当前线程尽力执行。 */
        private fun onMain(delayMs: Long, r: Runnable) {
            val h = mainHandler()
            if (h != null) {
                if (delayMs <= 0L) h.post(r) else h.postDelayed(r, delayMs)
            } else if (delayMs <= 0L) {
                try { r.run() } catch (_: Throwable) {}
            }
        }

        private fun cancelMain(r: Runnable?) {
            if (r == null) return
            try { mainHandler()?.removeCallbacks(r) } catch (_: Throwable) {}
        }

        // ---- 豆包**自己**发出去的消息：等不到回答就把豆包叫回来 ----
        // 真机现象（用户报告）：在豆包里发消息，**还没回复就立刻退出**，我们一条
        // 事件都收不到；手动把豆包拉回前台它才继续回答 —— 说明豆包在后台把推流
        // （SSE）断掉了，钩子住在它进程里，没有事件就什么都推不出来。我们造不出
        // 回答，但可以把它**叫回前台**让它继续推（等价于用户手动打开豆包），
        // 关键是**绝不重发**：这条消息已经由豆包自己发出去了。
        /** 多久完全没有 chat.* 事件就认为"这条回答断在半路"。 */
        private const val REPLY_STALL_MS = 5_000L
        @Volatile private var lastInAppSendAt = 0L
        @Volatile private var lastStreamAt = 0L
        @Volatile private var sendGeneration = 0L
        private var noReplyWatch: Runnable? = null

        /** MessageSender 的发送钩子发现"这是豆包自己发的"时调这里。
         *  [inAppSendHandler] 由 class 侧（装前后台追踪时）注册，用来开保活窗 ——
         *  保活窗的实现在 class 里，companion 直接调不到。 */
        @Volatile private var inAppSendHandler: ((String) -> Unit)? = null

        fun noteInAppSend(cid: String) {
            val gen = ++sendGeneration
            lastInAppSendAt = android.os.SystemClock.elapsedRealtime()
            lastStreamAt = lastInAppSendAt
            runCatching { inAppSendHandler?.invoke(cid) }
            startNoReplyWatch(cid, gen)
        }

        private fun startNoReplyWatch(cid: String, generation: Long) {
            val r = Runnable {
                if (generation != sendGeneration) return@Runnable
                val now = android.os.SystemClock.elapsedRealtime()
                val sinceSend = now - lastInAppSendAt
                val silent = now - lastStreamAt
                if (sinceSend >= REPLY_STALL_MS && silent >= REPLY_STALL_MS) {
                    XposedBridge.log("$TAG 豆包自己发的消息 ${sinceSend}ms 无任何推流" +
                        " → 请 App 唤醒豆包（不重发）")
                    runCatching {
                        callProvider(ensureCtx(), JSONObject()
                            .put("t", "reply.stalled").put("cid", cid))
                    }.onFailure {
                        XposedBridge.log("$TAG reply.stalled 通知失败: $it")
                    }
                }
            }
            cancelMain(noReplyWatch)
            noReplyWatch = r
            onMain(REPLY_STALL_MS, r)
        }

        /** 推流活着（chat.*）就不需要叫醒豆包。 */
        fun noteStreamEvent(t: String) {
            lastStreamAt = android.os.SystemClock.elapsedRealtime()
            if (t == "chat.start" || t == "chat.delta" || t == "chat.end") {
                cancelMain(noReplyWatch)
                noReplyWatch = null
            }
        }

        /** Resolve the Doubao Application context lazily —
         *  ActivityThread.currentApplication() works from any thread and needs
         *  no hooks, so this is the last-resort path if both ctx hooks missed. */
        private fun ensureCtx(): Context? {
            appCtx?.let { return it }
            return try {
                val at = XposedHelpers.findClass("android.app.ActivityThread", null)
                (XposedHelpers.callStaticMethod(at, "currentApplication")
                    as? Application)?.also { appCtx = it }
            } catch (_: Throwable) { null }
        }

        private const val AUTH_EVENTS = "com.tg.dbisland.events"

        private const val EVENT_QUEUE_MAX = 512

        // ---- 有序事件队列：合帧 + 无损背压 ----
        // 为什么不用裸队列：`chat.delta` 的 text 是**增长型**的（每次只带新增尾巴，
        // 见 MobileFeedParser.emitGrown），所以同一条 mid+kind 的连续 delta
        // **拼接起来 = 零信息损失**。合并之后长回答在队列里只剩 1 条，队列根本压不满，
        // 顺带把 Binder 事务数降 1~2 个数量级（App 侧每条事件都要一次 AMS 调用，
        // 见 IslandBridge.handle 的注释）。
        // 而旧实现的 drop-oldest 会**静默丢字** —— 对文本增量是不可接受的。
        private val evLock = java.util.concurrent.locks.ReentrantLock()
        private val evSignal = evLock.newCondition()
        private val evQueue = ArrayDeque<Pair<Context, JSONObject>>()
        @Volatile private var eventPumpStarted = false
        private var droppedEvents = 0L
        private var mergedEvents = 0L

        private fun ensureEventPump() {
            if (eventPumpStarted) return
            synchronized(this) {
                if (eventPumpStarted) return
                eventPumpStarted = true
                Thread {
                    while (true) {
                        val item: Pair<Context, JSONObject>
                        evLock.lock()
                        try {
                            while (evQueue.isEmpty()) evSignal.await()
                            item = evQueue.removeFirst()
                        } catch (t: InterruptedException) {
                            Thread.currentThread().interrupt()
                            continue
                        } finally {
                            evLock.unlock()
                        }
                        try {
                            if (!callProvider(item.first, item.second)) {
                                item.first.sendBroadcast(Intent(ACT_EVENT)
                                    .setComponent(ComponentName(PKG_SELF, RC_EVENT))
                                    .putExtra("v", 1)
                                    .putExtra("ev", item.second.toString()))
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG event pump fail: ${t.javaClass.simpleName}")
                        }
                    }
                }.apply { name = "island-event-pump"; isDaemon = true }.start()
            }
        }

        private fun enqueueEvent(ctx: Context, ev: JSONObject) {
            ensureEventPump()
            evLock.lock()
            try {
                val t = ev.optString("t")
                val last = evQueue.lastOrNull()
                // ① 合帧：同 mid + 同 kind 的连续文本/思考增量 → 拼接
                if (t == "chat.delta" && last != null &&
                    last.second.optString("t") == "chat.delta" &&
                    last.second.optString("mid") == ev.optString("mid") &&
                    last.second.optString("kind") == ev.optString("kind")) {
                    val merged = JSONObject(last.second.toString())
                    merged.put("text", last.second.optString("text") +
                        ev.optString("text"))
                    evQueue.removeLast()
                    evQueue.addLast(last.first to merged)
                    mergedEvents++
                    evSignal.signalAll()
                    return
                }
                // ② 有界：满了优先丢**最旧的非 delta**（状态帧比文字帧更可重建），
                //    但绝不丢新来的 —— 它是"更近的真相"
                if (evQueue.size >= EVENT_QUEUE_MAX) {
                    var victim = -1
                    for (i in 0 until evQueue.size) {
                        if (evQueue[i].second.optString("t") != "chat.delta") {
                            victim = i; break
                        }
                    }
                    if (victim < 0) victim = 0
                    val v = evQueue.removeAt(victim)
                    droppedEvents++
                    if (droppedEvents % 20L == 1L)
                        XposedBridge.log("$TAG event queue full(=$EVENT_QUEUE_MAX): " +
                            "丢弃 t=${v.second.optString("t")} " +
                            "累计丢弃=$droppedEvents 合并=$mergedEvents")
                }
                evQueue.addLast(ctx to JSONObject(ev.toString()))
                evSignal.signalAll()
            } finally {
                evLock.unlock()
            }
        }

        /** Primary delivery: a Binder call into the bridge app's
         *  com.tg.dbisland went `freeze=1 wchan=do_freezer_trap` ->
         *  `freeze=0 wchan=do_epoll_wait` on a single transaction
         *  (UNFREEZE_REASON_BINDER_TXNS / _GET_PROVIDER), ~1.1 s. This is
         *  the only transport that both penetrates the ColorOS freezer and
         *  leaves the app running long enough to render the card.
         *  @return true when the app acknowledged the frame. */
        fun callProvider(ctx: Context?, ev: JSONObject): Boolean {
            if (ctx == null) return false
            return try {
                val b = ctx.contentResolver.call(
                    android.net.Uri.parse("content://" + AUTH_EVENTS),
                    "event", null,
                    android.os.Bundle().apply { putString("ev", ev.toString()) })
                b?.getBoolean("ok") == true
            } catch (t: Throwable) {
                XposedBridge.log("$TAG call fail: ${t.javaClass.simpleName} ${t.message}")
                false
            }
        }

        fun sendTo(ctx: Context?, action: String, receiver: String,
                   ev: JSONObject? = null, src: String? = null) {
            if (ctx == null) return
            if (action == ACT_EVENT && ev != null) {
                enqueueEvent(ctx, ev)
                return
            }
            // --- primary path: Binder (survives the freezer) ---
            if (action == ACT_EVENT && ev != null && callProvider(ctx, ev)) {
                XposedBridge.log("$TAG ev[$action] via provider")
                return
            }
            try {
                val i = Intent(action)
                    .setComponent(ComponentName(PKG_SELF, receiver))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra("v", 1)
                // Which process sent this. The keep-alive pinger runs BOTH
                // inside Doubao (proves injection) and inside system_server
                // (works even when Doubao is dead), and the app must not
                // mistake the second for the first — see EnvCheck.
                if (src != null) i.putExtra("src", src)
                if (ev != null) i.putExtra("ev", ev.toString())
                ctx.sendBroadcast(i)
                // ColorOS OplusAppStartupManager blocks app→app broadcasts
                // that would start a stopped package (observed: every EVENT
                // for BridgeEventReceiver dropped). Also fire an implicit
                // relay action — the receiver registered inside system_server
                // (hookSystem) re-sends it as uid 1000, which the startup
                // manager does not block. App-side dedupes both copies.
                //
                // 这里曾经还有一条「root 中继」兜底：把事件 base64 追加到
                // app 私有目录的 ibq.log，由 /data/adb/service.d 下的 root
                // 脚本轮询重投。那条链路是电脑端专用的 root 组件（`relay.sh`
                // / `listen.sh`）的一部分，已随电脑端整体删除 —— 现在没有
                // 任何进程会读 ibq.log，继续写只会白占空间，所以一并删掉
                // （见 CHANGELOG 第 34 条）。
                if (action == ACT_EVENT && ev != null) {
                    ctx.sendBroadcast(Intent(ACT_EVENT_RELAY)
                        .putExtra("v", 1).putExtra("ev", ev.toString()))
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG send fail: $t")
            }
        }
    }

    /** One-time per-process init once the Application context is known. */
    private fun onAppCtx(app: Application?) {
        if (app == null) return
        appCtx = app
        XposedBridge.log("$TAG nova ctx captured")
        try {
            if (!pingerStarted) {
                pingerStarted = true
                startPinger(app, PING_MS, SRC_DOUBAO)
            }
        } catch (t: Throwable) { XposedBridge.log("$TAG pinger fail: $t") }
        // 前后台追踪：只有豆包**主进程**有 Activity，:push 进程装了也没用
        try {
            if (!fgTrackerInstalled && currentProcessName() == PKG_DOUBAO) {
                fgTrackerInstalled = true
                installForegroundTracker()
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG fg tracker fail: ${t.javaClass.simpleName} ${t.message}")
        }
        // SEND/DELETE commands from our app arrive on this receiver living
        // inside the Doubao process
        try {
            if (!cmdRegistered) {
                cmdRegistered = true
                val f = android.content.IntentFilter().apply {
                    addAction(ACT_SEND)
                    addAction(ACT_DELETE)
                    addAction(ACT_PING)
                }
                // ---- 安全整改（审核问题 1）----
                // 这两个动作能替用户发消息、永久删除会话，属于写操作。
                // 之前用 RECEIVER_EXPORTED 且不校验发送方，任意应用一条广播
                // 就能触发。现在以本模块定义的签名级权限
                // com.tg.dbisland.permission.CONTROL 作为 broadcastPermission：
                // 系统在投递前强制校验「发送方是否持有该权限」，而该权限
                // protectionLevel="signature"，只有与本模块同证书签名的应用
                // 才可能被授予 —— 第三方应用无从获得。
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    app.registerReceiver(cmdReceiver, f,
                        BridgeSecurity.PERM_CONTROL, null,
                        Context.RECEIVER_EXPORTED)
                else
                    app.registerReceiver(cmdReceiver, f,
                        BridgeSecurity.PERM_CONTROL, null)
                XposedBridge.log("$TAG cmd receiver registered " +
                    "(perm=${BridgeSecurity.PERM_CONTROL})")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG receiver fail: $t")
        }
    }

    /** Registered inside the Doubao process: receives com.tg.dbisland.SEND /
     *  .DELETE broadcasts from our app and replays them through the Omni SDK.
     *  The result goes back over the normal ACT_EVENT channel as
     *  {"t":"send.result", ok, err}. */
    private val cmdReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            // 第二道闸：签名级权限已由系统强制，这里再核对发送方 uid，
            // 只认本模块 App / root / shell（无法归因时放行，理由见
            // BridgeSecurity.allowCommandSender）。
            val uid = com.tg.dbisland.BridgeSecurity.senderUid(this)
            if (!com.tg.dbisland.BridgeSecurity.allowCommandSender(ctx, uid)) {
                XposedBridge.log("$TAG ${i.action} rejected: sender uid=$uid")
                return
            }
            // App 侧保活窗的 ping：只"摸一下"本进程（一来一回的 binder 事务让
            // ColorOS 冻结器认为豆包仍活跃），没有任何业务动作、不产生回执。
            if (i.action == ACT_PING) {
                if (pingLogged++ % 10 == 1)
                    XposedBridge.log("$TAG ping from app (保活中=$keepAliveOn " +
                        "前台=$doubaoForeground)")
                if (!doubaoForeground) {
                    if (keepAliveOn) touchKeepAlive("app-ping")
                    else armKeepAlive("app-ping")
                }
                return
            }
            // 用户/App 要发消息了 → 先把保活窗激活（回答一定会来，见上面注释）
            if (i.action == ACT_SEND) activateKeepAliveForSend()
            // 去重：只在本进程（豆包主进程）判，:push 不能"吃掉"这个 id
            val sendId = i.getStringExtra("id") ?: ""
            if (i.action == ACT_SEND && sendId.isNotEmpty() &&
                currentProcessName() == PKG_DOUBAO) {
                val dup = synchronized(recentSendIds) {
                    if (recentSendIds.contains(sendId)) true
                    else {
                        recentSendIds.addLast(sendId)
                        while (recentSendIds.size > RECENT_SEND_MAX)
                            recentSendIds.removeFirst()
                        false
                    }
                }
                if (dup) {
                    XposedBridge.log("$TAG 重复 SEND（id=$sendId）已忽略 —— " +
                        "root 重发 / 推迟投递的去重")
                    return
                }
            }
            val text = i.getStringExtra("text") ?: ""
            val cid = i.getStringExtra("cid")
                ?.ifEmpty { null } ?: MessageSender.lastCid
            val botId = i.getStringExtra("botId")
                ?.ifEmpty { null } ?: MessageSender.lastBotId
            Thread {
                // Doubao runs com.larus.nova and com.larus.nova:push as
                // separate processes and this receiver exists in each. Only the
                // MAIN process can send (it owns the chat UI + the send
                // template); answer SEND/DELETE there and stay silent elsewhere
                // so :push cannot race a duplicate or claim "no ids".
                val mainProc = currentProcessName() == PKG_DOUBAO
                XposedBridge.log("$TAG ${i.action} recv in=${currentProcessName()} " +
                    "text=${text.length}字 cid=${cid ?: "-"} main=$mainProc")
                if (!mainProc) {
                    XposedBridge.log("$TAG skip ${i.action} in " +
                        "${currentProcessName()}")
                    return@Thread
                }
                val t0 = android.os.SystemClock.elapsedRealtime()
                val err = when (i.action) {
                    ACT_SEND -> if (text.isEmpty()) "空文本"
                        else MessageSender.send(text, cid)
                    ACT_DELETE -> MessageSender.delete(cid, botId)
                    else -> "未知动作"
                }
                // 耗时必须记：App 侧等回执有超时，之前 1.5s 的旧值会让每次都
                // 误判「无回执 → 回退剪贴板」，真机表现为「回复失败 + 岛不再刷新」。
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                XposedBridge.log("$TAG ${i.action} done ${ms}ms err=${err ?: "ok"}")
                // 发失败就把 id 从去重表撤掉：否则 App 的 root 重投/重试会被
                // 当成"重复"丢掉（真机 00:25 就是这样，用户永远等不到回答）。
                if (i.action == ACT_SEND && sendId.isNotEmpty() && err != null) {
                    synchronized(recentSendIds) { recentSendIds.remove(sendId) }
                }
                val ev = JSONObject().put("t", "send.result")
                    .put("ok", err == null)
                    .put("err", err ?: "")
                    .put("act", i.action)
                    .put("ms", ms)
                sendTo(ctx, ACT_EVENT, RC_EVENT, ev)
            }.start()
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                "com.larus.nova" -> hookNova(lpparam)
                // upstream LSPosed names system_server "android"; the
                // JingMatrix fork on this device passes "system" — accept both
                "android", "system" -> hookSystem(lpparam)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    // ---------- com.larus.nova ----------
    private fun hookNova(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        // Capture the Application context. Doubao's UI-launch path creates the
        // Application WITHOUT going through Instrumentation.callApplicationOnCreate
        // (in the main process that hook never fires — logs only ever show it
        // for :push), which left appCtx null and silently dropped every event.
        // Application.attach(Context) is `final` — every creation path (manual
        // newApplication+attach+onCreate included) passes through it.
        XposedHelpers.findAndHookMethod(
            "android.app.Application", cl, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    onAppCtx(p.thisObject as? Application)
                }
            })
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", cl,
            "callApplicationOnCreate", Application::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    onAppCtx(p.args[0] as? Application)
                }
            })

        // reply-injection path: capture + replay OmniMessageService calls
        MessageSender.init(cl)

        // all SSE stream bytes — hook every overload by name
        hookStreamMethods(cl,
            "com.larus.im.internal.jni.dependency.OmniHttpCallByNative")

        // primary capture: streaming replies arrive as OmniMessage objects
        // through the message dispatcher (native IM channel), not writeChunkData
        hookMessageDispatcher(cl,
            "com.larus.im.internal.jni.observer.OmniMessageDispatcher")

        // 纯观测：记录豆包结束后状态栏通知的真实来源与 extras，不改变通知行为。
        installNotificationProbe(cl)

        // downstream dispatchers: log-only taps so field layout can be
        // refined from the LSPosed log without touching native code.
        // OmniConversationDispatcher additionally feeds the conversation-name
        // learner（手机侧会话标题的唯一现成来源，见 noteConvName）。
        logAllMethods(cl, "com.larus.im.internal.jni.observer.OmniConversationDispatcher",
            learnConvs = true)
        logAllMethods(cl, "com.larus.im.internal.jni.observer.OmniAIJobDispatcher")
    }

    private fun installNotificationProbe(cl: ClassLoader) {
        try {
            val nm = XposedHelpers.findClass("android.app.NotificationManager", cl)
            for (name in arrayOf("notify", "notifyAsPackage", "cancel", "cancelAsPackage")) {
                XposedBridge.hookAllMethods(nm, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val args = p.args ?: return
                            val sb = StringBuilder()
                                .append("notifyProbe proc=").append(currentProcessName())
                                .append(" method=").append(name).append(" args=")
                            args.forEachIndexed { i, a ->
                                when (a) {
                                    is Int, is String, is Long, is Boolean ->
                                        sb.append("[$i]=").append(a).append(' ')
                                    is android.app.Notification -> {
                                        val e = a.extras
                                        sb.append("[$i]Notification channel=")
                                            .append(a.channelId)
                                            .append(" when=").append(a.`when`)
                                            .append(" flags=").append(a.flags)
                                        if (e != null) {
                                            val keys = e.keySet().sorted()
                                            sb.append(" keys=").append(keys.joinToString(","))
                                            for (k in keys) {
                                                val v = e.get(k)
                                                if (v is CharSequence || v is String)
                                                    sb.append(" ").append(k).append("='")
                                                        .append(v.toString().take(300)).append("'")
                                            }
                                        }
                                        sb.append(' ')
                                    }
                                    else -> sb.append("[$i]=")
                                        .append(a?.javaClass?.name ?: "null").append(' ')
                                }
                            }
                            XposedBridge.log("$TAG $sb")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG notifyProbe fail ${t.javaClass.simpleName}")
                        }
                    }
                })
            }
            XposedBridge.log("$TAG notification probe installed proc=${currentProcessName()}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG notification probe install fail $t")
        }
    }

    // 用户规则：豆包一离开前台就开始保活；**有回复就持续保**（每条推流续期）；
    // **响应结束后重新计 30s**；这 30s 里始终没有新内容 → 放弃保活，交给系统。

    /** 追踪豆包前后台。挂在 `ActivityThread` 上而不是 `Activity.onResume/onStop`：
     *  后者在子类覆写又没调 super 时不会触发（Compose/AppCompat 页面很常见）。 */
    private fun installForegroundTracker() {
        // 豆包自己发消息时（MessageSender 的钩子）也让这里开保活窗
        inAppSendHandler = { touchKeepAlive("豆包内发送") }
        try {
            val at = XposedHelpers.findClass("android.app.ActivityThread", null)
            XposedBridge.hookAllMethods(at, "handleResumeActivity",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        onResumed()
                    }
                })
            val back = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    scheduleBackgroundCheck()
                }
            }
            XposedBridge.hookAllMethods(at, "handleStopActivity", back)
            XposedBridge.hookAllMethods(at, "handleDestroyActivity", back)
            XposedBridge.log("$TAG 前后台追踪已装（退后台保活 ${
                KEEPALIVE_IDLE_MS / 1000}s，有推流则续期）")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG 前后台追踪失败: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun onResumed() {
        lastResumeAt = android.os.SystemClock.elapsedRealtime()
        cancelMain(fgDebounce)
        fgDebounce = null
        if (!doubaoForeground) {
            doubaoForeground = true
            XposedBridge.log("$TAG 豆包回到前台 → 停止保活")
            stopKeepAlive("回到前台")
        }
    }

    /** handleStopActivity 后 FG_DEBOUNCE_MS 内没有新的 resume → 判定真的退后台。 */
    private fun scheduleBackgroundCheck() {
        cancelMain(fgDebounce)
        val r = Runnable {
            fgDebounce = null
            if (android.os.SystemClock.elapsedRealtime() - lastResumeAt <
                FG_DEBOUNCE_MS) return@Runnable
            if (!doubaoForeground) return@Runnable
            doubaoForeground = false
            XposedBridge.log("$TAG 豆包离开前台 → 开始保活（静默 ${
                KEEPALIVE_IDLE_MS / 1000}s 后放弃）")
            armKeepAlive("退到后台")
        }
        fgDebounce = r
        onMain(FG_DEBOUNCE_MS, r)
    }

    /** 任何一条推流都算"还活着"。可能来自解析线程，统一丢主线程处理。 */
    private fun onStreamEvent(t: String) {
        onMain(0L, Runnable {
            try { onStreamEventOnMain(t) } catch (x: Throwable) {
                XposedBridge.log("$TAG keepalive stream fail: ${x.javaClass.simpleName}")
            }
        })
    }

    private fun onStreamEventOnMain(t: String) {
        if (doubaoForeground) {
            if (keepAliveOn) stopKeepAlive("豆包已在前台")
            return
        }
        if (keepAliveOn) touchKeepAlive(t) else armKeepAlive("后台收到 $t")
    }

    /** **「发送」也是保活窗的激活条件**（用户要求）：App 刚把一条消息交给豆包，
     *  回答一定会来。不能等第一条 `chat.start/delta` 才开窗 —— 那时豆包很可能
     *  已经被 ColorOS 冻住，事件根本到不了，于是又变成"发完就走收不到内容"。
     *  所以收到 SEND 就先开窗：App 侧会立刻开始每 3s ping 豆包进程，
     *  把这条命令和随后的回答一起捞回来。 */
    private fun activateKeepAliveForSend() {
        onMain(0L, Runnable {
            try {
                if (keepAliveOn) {
                    touchKeepAlive("app-send")
                } else {
                    XposedBridge.log("$TAG App 发送消息 → 激活保活窗" +
                        "（前台=$doubaoForeground）")
                    armKeepAlive("app-send")
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG 发送激活保活失败: ${t.javaClass.simpleName}")
            }
        })
    }

    /** 开工：拿唤醒锁 + 起自 ping + 开始 30s 倒计时，并告知 App 一起镜像计时。 */
    private fun armKeepAlive(cause: String) {
        if (!keepAliveOn) {
            keepAliveOn = true
            try {
                val pm = appCtx?.getSystemService(Context.POWER_SERVICE)
                    as? android.os.PowerManager
                keepAliveWl = pm?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "islandbridge:doubao-keepalive")?.apply {
                    setReferenceCounted(false)
                    acquire(10 * 60_000L)
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG 保活窗：唤醒锁不可用(${t.javaClass.simpleName})，仅靠 ping")
            }
            if (keepAliveWl == null)
                XposedBridge.log("$TAG 保活窗：未拿到唤醒锁，仅靠 ping")
            emitKeepAlive("armed", cause)
            startKeepAlivePings()
        }
        touchKeepAlive(cause)
    }

    /** 续期：有内容就一直保（chat.end 之后同样从这里重新计 30s）。 */
    private fun touchKeepAlive(cause: String) {
        if (!keepAliveOn) return
        cancelMain(keepAliveDeadline)
        val r = Runnable {
            keepAliveDeadline = null
            stopKeepAlive("静默 ${KEEPALIVE_IDLE_MS / 1000}s 没有新内容")
        }
        keepAliveDeadline = r
        onMain(KEEPALIVE_IDLE_MS, r)
        XposedBridge.log("$TAG 保活续期（$cause）")
    }

    private fun startKeepAlivePings() {
        val r = object : Runnable {
            override fun run() {
                keepAlivePingR = null
                if (!keepAliveOn) return
                // 放到后台线程：callProvider 是同步 binder 调用，跑在主线程上
                // 万一对面慢就会卡住豆包 UI。
                Thread {
                    // 主 ping：一次「豆包 → App provider → 豆包」的 binder 往返，
                    // 冻结器据此把本进程记成活跃；silent.ping 在 App 侧不打日志。
                    try { callProvider(appCtx, JSONObject().put("t", "silent.ping")) }
                    catch (_: Throwable) {}
                    try { sendTo(appCtx, ACT_KEEPALIVE, RC_KEEPALIVE, src = SRC_DOUBAO) }
                    catch (_: Throwable) {}
                }.start()
                keepAlivePingR = this
                onMain(KEEPALIVE_PING_MS, this)
            }
        }
        keepAlivePingR = r
        onMain(KEEPALIVE_PING_MS, r)
    }

    private fun stopKeepAlive(reason: String) {
        if (!keepAliveOn) return
        keepAliveOn = false
        cancelMain(keepAliveDeadline)
        keepAliveDeadline = null
        cancelMain(keepAlivePingR)
        keepAlivePingR = null
        try { if (keepAliveWl?.isHeld == true) keepAliveWl?.release() }
        catch (_: Throwable) {}
        keepAliveWl = null
        XposedBridge.log("$TAG 保活结束（$reason）→ 豆包交回系统处理")
        emitKeepAlive("stopped", reason)
    }

    private fun emitKeepAlive(state: String, reason: String) {        try {
            sendTo(appCtx, ACT_EVENT, RC_EVENT, JSONObject()
                .put("t", "keepalive")
                .put("state", state)      // armed / stopped
                .put("reason", reason)
                .put("idleMs", KEEPALIVE_IDLE_MS))
        } catch (_: Throwable) {}
    }

    // Both capture paths (SSE chunks + OmniMessage objects) can describe the
    // same reply with different chunking. The SSE stream is the primary
    // protocol (matches the desktop feed); OmniMessage is the fallback for
    // replies delivered while Doubao is backgrounded (push channel). Once
    // SSE produces events for a mid, omni events for it are dropped.
    //
    // 第 47 条：这个集合**跨轮保留**（chat.end 不再删除，见 emitShared），
    // 所以加一个上限防止长进程里无限长；超了整批清空（那些回答早就过去了）。
    private val sseMids = HashSet<String>()

    // ---------------- 手机侧会话名（会话标题修复）----------------
    //
    // 病根：手机侧回复走 native IM 通道（OmniMessageDispatcher），桌面端那条
    // `SSE_ACK.ack_client_meta.conversation_info.name` 在这条链路上**根本不存在**，
    // 于是 App 的 convNames 永远是空的，卡片标题一直是兜底值「安卓包-豆包」。
    // 修法：模块自己从豆包的 IM 会话对象上取名字（OmniConversation 有
    // `conversationId` + `name`，见 D:\aiwork\apk\idx24.tsv），两条来源并用：
    //   ① 被动：OmniConversationDispatcher.notifyChange/notifyReplace 的参数里带
    //      OmniConversation，顺路学（会话改名、自动标题回填都会走它）；
    //   ② 主动：chat.start 时用 ConversationService.getConversation(cid) 查一次
    //      （后台线程，最多等 2.5s，绝不阻塞回复推流）；chat.end 之后再查一次 ——
    //      豆包的自动标题是**回答完之后**由服务端回填的。
    // 学到就发一条 `chat.conv {cid,cname}`（同 cid 同名只发一次）。
    /** cid → 已发出的名字（去重）。 */
    private val convNameSent = HashMap<String, String>()
    /** 正在后台查询的 cid，避免同一 cid 并发查询。 */
    private val convNameQuerying = HashSet<String>()
    /** chat.end 之后多久补查一次（等自动标题回填）。 */
    private val CONV_NAME_LATE_MS = 4_000L

    /** 从一个对象（或它的 List 元素）上读 (conversationId, name)。
     *
     *  **必须读字段，不能读 getter**：OmniConversation 的属性是 `@JvmField`
     *  （idx24.tsv 里只有 `getConversationId$annotations` / `getName$annotations`，
     *  **没有** `getConversationId()` / `getName()`）。真机第一次就是只用反射
     *  getter 才空手而归的（日志：`对象里没有名字字段 u99.e` / 无 `会话名[dsp]`）。 */
    private fun convIdName(o: Any?): Pair<String, String>? {
        val one = { x: Any? ->
            if (x == null) null else {
                val cn = x.javaClass.name
                val looksConv = cn.contains("Conversation") || cn.contains("conversation")
                if (!looksConv) null else {
                    val id = fieldStr(x, "conversationId")
                        ?: strGetter(x, "getConversationId")
                    val nm = fieldStr(x, "name") ?: fieldStr(x, "title")
                        ?: strGetter(x, "getName")
                    if (id.isNullOrEmpty() || nm.isNullOrEmpty()) null else id to nm
                }
            }
        }
        one(o)?.let { return it }
        if (o is List<*>) for (x in o) one(x)?.let { return it }
        return null
    }

    /** 读字段（含父类链）上的非空字符串。 */
    private fun fieldStr(o: Any, name: String): String? {
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            val f = runCatching { k.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(o) as? String
                }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            }
            k = k.superclass
        }
        return null
    }

    private fun strGetter(o: Any, name: String): String? = runCatching {
        o.javaClass.getMethod(name).invoke(o) as? String
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 一次性诊断：ConversationDispatcher 列表里的元素长什么样（只打一次）。 */
    private var convListDumped = false
    private fun dumpConvList(l: List<*>) {
        synchronized(this) {
            if (convListDumped) return
            convListDumped = true
        }
        val e = l.firstOrNull() ?: return
        XposedBridge.log("$TAG convList size=${l.size} elem=${e.javaClass.name} ")
        XposedBridge.log("$TAG convList fields: ${dumpObj(e).take(1200)}")
        XposedBridge.log("$TAG convList getters: ${probeGetters(e).take(600)}")
    }

    /** 学到会话名 → 发 chat.conv。
     *
     *  [force]=true 用于**主动查询**那条路（chat.start / chat.end 后补查）：App 可能
     *  在豆包进程之后重启过（内存里的 convNames 清空），而这张去重表是按**豆包进程**
     *  存的 —— 只靠去重就会「App 重启后再也收不到会话名」，卡片标题永远停在兜底值。
     *  （真机实测：重启 App 后标题仍旧是 安卓包-豆包，就是这条去重吃掉的。）
     *  每条回复多发一次 binder 事件，代价可以忽略；App 侧自己按「名字变了」去重。 */
    private fun noteConvName(cid: String?, name: String?, from: String, force: Boolean = false) {
        if (cid.isNullOrBlank() || cid.length <= 5 || cid == "0") return
        val n = name?.trim().orEmpty().take(64)
        if (n.isEmpty()) return
        val first = synchronized(convNameSent) {
            if (convNameSent[cid] == n) false
            else {
                convNameSent[cid] = n
                true
            }
        }
        if (!first && !force) return
        // 会话名不是隐私正文，可以整串打；cid 只留后 6 位
        XposedBridge.log("$TAG 会话名[$from] cid=${cid.takeLast(6)} " +
            "name='$n'（${n.length}字）")
        try {
            sendTo(ensureCtx(), ACT_EVENT, RC_EVENT, JSONObject()
                .put("t", "chat.conv").put("cid", cid).put("cname", n))
        } catch (t: Throwable) {
            XposedBridge.log("$TAG 会话名上报失败: ${t.javaClass.simpleName}")
        }
    }

    /** 主动查询；[late]=true 表示 chat.end 之后的补查。后台线程，不阻塞推流。 */
    private fun queryConvName(cid: String, late: Boolean) {
        if (cid.isBlank() || cid.length <= 5) return
        synchronized(convNameQuerying) {
            if (!convNameQuerying.add(cid)) return
        }
        Thread {
            try {
                val r = MessageSender.fetchConversationName(cid)
                if (r != null) noteConvName(r.first, r.second,
                    if (late) "late" else "get", force = true)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG 会话名查询异常: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                synchronized(convNameQuerying) { convNameQuerying.remove(cid) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun emitShared(src: String, ev: JSONObject) {
        val t = ev.optString("t")
        val mid = ev.optString("mid")
        // 后台保活窗：任何一条推流都续期；chat.end 之后重新计 30s（用户规则）
        if (t.startsWith("chat.") || t == "plan.end" || t == "plan.start") {
            onStreamEvent(t)
            // 推流还活着 → 取消"叫醒豆包"的看门狗（见 noteInAppSend）
            noteStreamEvent(t)
        }
        // track the live conversation id for MessageSender replays
        ev.optString("cid").let { if (it.isNotEmpty()) MessageSender.lastCid = it }
        if (t.startsWith("chat.")) MessageSender.sawChatTraffic = true
        synchronized(sseMids) {
            when {
                src == "sse" && (t == "chat.start" || t == "chat.delta") -> {
                    sseMids.add(mid)
                    if (sseMids.size > 4096) {
                        sseMids.clear()
                        sseMids.add(mid)
                    }
                }
                src == "omni" && mid in sseMids &&
                    t != "chat.end" && t != "chat.reply" -> return
                // 第 47 条：**不再**在 chat.end 时把这个 mid 从集合里删掉。
                //
                // 旧实现在 chat.end 就 remove，于是「这条回答已经由 SSE 通道完整
                // 走完」这个事实在收尾那一刻被忘掉了；紧接着 omni 通道把这**同一轮
                // 回答的全文**再推一遍（真机实测：中断重发时 omni 的 messageId 甚至
                // 还停在被中断的那条回答上，把新回答的 490 字挂到旧 mid 上），
                // 因为 mid 已被移除，去重失效 → 旧消息被写进新回答的正文。
                // 现在 mid 只在会话内保留（上限见 sseMids 声明），omni 侧对
                // 「SSE 已经答过的消息」一律不再重复投。
            }
        }
        XposedBridge.log("$TAG ev[$src] t=$t mid=${mid.takeLast(8)} " +
            "cid=${ev.optString("cid").takeLast(8)} text=${ev.optString("text").length}")
        sendTo(ensureCtx(), ACT_EVENT, RC_EVENT, ev)

        // 会话名：chat.start 时主动查一次；chat.end 之后补查（自动标题回填）。
        // 放在事件投递**之后**，查询走后台线程，绝不给推流加延迟（见上）。
        val cid0 = ev.optString("cid")
        if (cid0.length > 5) {
            when (t) {
                "chat.start" -> if (ev.optString("cname").isEmpty()) queryConvName(cid0, false)
                "chat.end" -> onMain(CONV_NAME_LATE_MS, Runnable {
                    queryConvName(cid0, true) })
            }
        }
    }

    /** Omni 收录器按 conversationId 隔离；心跳状态不参与内容分配。 */
    private val omniParsers = java.util.concurrent.ConcurrentHashMap<String, MobileFeedParser>()
    private val omniMidKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var activeOmniKey = ""

    private fun omniParserFor(cid: String, mid: String): MobileFeedParser {
        val prior = if (mid.isNotBlank()) omniMidKeys[mid] else null
        val key = cid.ifBlank { prior ?: "mid:$mid" }.ifBlank { "unknown" }
        if (cid.isNotBlank() && prior != null && prior != key) {
            omniParsers.remove(prior)?.let { omniParsers.putIfAbsent(key, it) }
        }
        if (mid.isNotBlank()) {
            omniMidKeys[mid] = key
            if (mid.contains('#')) omniMidKeys[mid.substringBefore('#')] = key
        }
        activeOmniKey = key
        return omniParsers.getOrPut(key) {
            XposedBridge.log("$TAG new omni collector cid=${cid.takeLast(6)} key=$key")
            MobileFeedParser { ev -> emitShared("omni", ev) }
        }
    }

    private fun finishOmniForMessage(mid: String) {
        if (mid.isBlank()) return
        val base = mid.substringBefore('#')
        val key = omniMidKeys[mid] ?: omniMidKeys[base] ?: mid
        val parser = omniParsers[key] ?: run {
            val candidates = omniParsers.values.filter { it.isStarted() }
            if (candidates.size == 1) candidates.first() else null
        }
        XposedBridge.log("$TAG finish omni mid=${mid.takeLast(12)} base=${base.takeLast(12)} " +
            "key=$key found=${parser != null}")
        parser?.finishOmni(mid)
    }

    private fun finishActiveOmni() {
        val key = activeOmniKey
        if (key.isBlank()) return
        omniParsers[key]?.finishOmni()
    }

    private fun hookMessageDispatcher(cl: ClassLoader, clsName: String) {
        val cls = try {
            XposedHelpers.findClass(clsName, cl)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG no $clsName"); return
        }
        var n = 0
        for (m in cls.declaredMethods) {
            val hasMsg = m.parameterTypes.any {
                it.name == "com.larus.im.internal.jni.bean.OmniMessage" }
            val isEnd = m.name == "onReceiveEnd" ||
                m.name == "onMessageEnd"
            if (!hasMsg && !isEnd) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            // onStreamingMessage carries (name, new, old, ...):
                            // feeding every OmniMessage arg double-counts —
                            // take only the first (newest snapshot).
                            val msg = p.args?.firstOrNull {
                                it?.javaClass?.name ==
                                    "com.larus.im.internal.jni.bean.OmniMessage"
                            }
                            if (msg != null) handleOmni(msg, allowFinal = isEnd)
                            if (isEnd) {
                                val ss = p.args?.filterIsInstance<String>().orEmpty()
                                val replyMsgId = ss.firstOrNull().orEmpty()
                                val endMsg = ss.getOrNull(1).orEmpty()
                                XposedBridge.log("$TAG explicit stream end ${m.name} " +
                                    "reply=${replyMsgId.takeLast(6)} end=${endMsg.takeLast(6)}")
                                finishOmniForMessage(replyMsgId)
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG msg fail: $t")
                        }
                    }
                })
                XposedBridge.log("$TAG hooked ${cls.simpleName}.${m.name}")
                if (++n > 30) break
            } catch (_: Throwable) {}
        }
    }

    private fun handleOmni(m: Any, allowFinal: Boolean = false) {
        fun f(name: String) = runCatching {
            XposedHelpers.getObjectField(m, name) as? String }.getOrNull() ?: ""
        // Normal reply snapshots carry replyId. Final/history snapshots may
        // omit it, but are still valid when they have cid plus content/brief.
        val replyId = f("replyId")
        val mid = f("messageId")
        val cid = f("conversationId")
        val brief = f("brief")
        val content = f("content")
        if (replyId.isEmpty() && (!allowFinal || cid.isEmpty() && content.isEmpty() && brief.isEmpty())) return
        val parser = omniParserFor(cid, mid)
        parser.feedOmniMessage(mid, cid, brief, content)
        // Some Doubao builds deliver the final full snapshot without replyId
        // and never call the named ReceiveEnd method. The snapshot itself is
        // the terminal evidence; debounce so a later snapshot can cancel it.
        if (replyId.isEmpty() && cid.isNotEmpty() &&
            (content.isNotEmpty() || brief.isNotEmpty())) {
            XposedBridge.log("$TAG final snapshot candidate cid=${cid.takeLast(6)} " +
                "content=${content.length} brief=${brief.length}")
            parser.finishOmni(mid)
        }
    }

    private fun hookStreamMethods(cl: ClassLoader, clsName: String) {
        val parsers = HashMap<Any, MobileFeedParser>()
        val cls = try {
            XposedHelpers.findClass(clsName, cl)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG no $clsName"); return
        }
        for (m in cls.declaredMethods) {
            if (m.name != "writeMetaInfo" && m.name != "writeChunkData")
                continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    private var calls = 0
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            // visibility probe: log first calls with arg shapes
                            if (calls++ < 5 || calls % 500 == 0) {
                                val sig = p.args?.joinToString(",") {
                                    when (it) {
                                        is ByteArray -> "ByteArray(${it.size})"
                                        is String -> "Str(${it.length}):'${it.take(40)}'"
                                        else -> "${it?.javaClass?.simpleName}"
                                    }
                                } ?: ""
                                XposedBridge.log("$TAG call ${m.name}($sig)")
                            }
                            // key by REQUEST, not call object — concurrent
                            // completions share one OmniHttpCallByNative and
                            // would otherwise pour both SSE streams into one
                            // parser (observed: two essays braided together).
                            // args carry a 36-char request uuid and/or a
                            // native call handle; thisObject is the fallback.
                            var key: Any = p.thisObject
                            for (a in p.args ?: emptyArray()) {
                                if (a is String && a.length == 36 &&
                                    a.count { it == '-' } == 4) { key = a; break }
                                if (a is Long) { key = a; break }
                            }
                            val parser = synchronized(parsers) {
                                parsers.getOrPut(key) {
                                    XposedBridge.log("$TAG new parser " +
                                        "key=$key obj=${System.identityHashCode(p.thisObject)}")
                                    MobileFeedParser { ev -> emitShared("sse", ev) }
                                }
                            }
                            for (a in p.args ?: return) when (a) {
                                is ByteArray -> parser.feedBytes(a)
                                is String -> {
                                    // writeChunkData hands us the raw SSE byte
                                    // stream split into arbitrary pieces —
                                    // 'id:'/'event:'/blank lines included, so
                                    // feed everything or frame boundaries are
                                    // lost. Meta calls carry a bare uuid that
                                    // would poison the buffer; skip those.
                                    val isUuid = a.length == 36 &&
                                        a.count { it == '-' } == 4
                                    if (!isUuid) parser.feed(a)
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG parse fail: $t")
                        }
                    }
                })
                XposedBridge.log("$TAG hooked ${m.name}(${m.parameterTypes
                    .joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hook ${m.name} fail: $t")
            }
        }
    }

    private fun logAllMethods(cl: ClassLoader, clsName: String,
                              learnConvs: Boolean = false) {
        val cls = try {
            XposedHelpers.findClass(clsName, cl)
        } catch (t: Throwable) { return }
        var n = 0
        for (m in cls.declaredMethods) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    private var dumps = 0
                    override fun afterHookedMethod(p: MethodHookParam) {
                        // OmniConversationDispatcher 的参数里就带 OmniConversation
                        // （conversationId + name）—— 会话改名/自动标题回填都会走它，
                        // 顺路把名字学下来（见 noteConvName）。
                        if (learnConvs) for (a in p.args ?: emptyArray()) {
                            if (a is List<*>) dumpConvList(a)
                            val r = convIdName(a) ?: continue
                            noteConvName(r.first, r.second, "dsp")
                        }
                        // hot dispatchers fire constantly — cap log lines per
                        // method or they flush everything else out of logcat
                        if (dumps >= 6) return
                        dumps++
                        val sig = p.args?.joinToString(",") {
                            it?.javaClass?.simpleName ?: "null" } ?: ""
                        XposedBridge.log("$TAG dsp ${cls.simpleName}." +
                            "${m.name}($sig)")
                        // dump message-object fields a few times so we can
                        // see where reply text lives
                        if (dumps <= 3) for (a in p.args ?: return) {
                            if (a == null || a.javaClass.name.let {
                                    it.startsWith("java.") ||
                                    it.startsWith("kotlin.") }) continue
                            XposedBridge.log("$TAG field ${a.javaClass.name} " +
                                "= ${dumpObj(a).take(600)}")
                            // JNI wrappers hold a native ptr — the text is in
                            // zero-arg getters / toString, not fields
                            XposedBridge.log("$TAG str ${a.javaClass.name} " +
                                "toString=${runCatching { a.toString() }
                                    .getOrNull()?.take(200)} " +
                                "getters=${probeGetters(a).take(600)}")
                        }
                    }
                })
                if (++n > 40) break          // safety bound
            } catch (_: Throwable) {}
        }
    }

    /** Shallow field dump of an opaque SDK object (all fields, incl. super). */
    private fun dumpObj(o: Any, depth: Int = 0): String {
        if (depth > 2) return "…"
        val sb = StringBuilder()
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(o)
                    sb.append(f.name).append('=').append(
                        when (v) {
                            null -> "null"
                            is String -> "'${v.take(80)}'"
                            is ByteArray -> "B[${v.size}]"
                            is Number, is Boolean -> v.toString()
                            else -> if (depth < 1 && !v.javaClass.name
                                    .startsWith("java."))
                                "{${dumpObj(v, depth + 1).take(200)}}"
                            else v.javaClass.simpleName
                        }).append(' ')
                } catch (_: Throwable) {}
            }
            k = k.superclass
        }
        return sb.toString()
    }

    /** Invoke zero-arg methods returning String/CharSequence to find the
     *  content accessor on opaque JNI wrapper objects. */
    private fun probeGetters(o: Any): String {
        val sb = StringBuilder()
        for (m in o.javaClass.declaredMethods) {
            if (m.parameterCount != 0) continue
            if (m.returnType != String::class.java &&
                !CharSequence::class.java.isAssignableFrom(m.returnType) &&
                m.returnType != Long::class.javaPrimitiveType &&
                m.returnType != Int::class.javaPrimitiveType) continue
            try {
                m.isAccessible = true
                val v = m.invoke(o) ?: continue
                val s = v.toString()
                if (s.isEmpty() || s == "0") continue
                sb.append(m.name).append("='").append(s.take(100))
                    .append("' ")
            } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    // ---------- android (system_server) ----------
    private fun hookSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG loaded in system_server (${lpparam.packageName})")
        // Modules are injected DURING SystemServer.run() — any hook we put
        // on run() is installed too late to fire this boot. Skip the hook
        // and arm directly: poll until the system context materializes
        // (it is created inside run(), so it isn't ready here either).
        Thread {
            var ctx: Context? = null
            for (i in 0..120) {
                try {
                    ctx = systemContext(i % 6 == 0)
                    if (ctx != null) break
                    Thread.sleep(5_000)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG ctx poll die: $t")
                    return@Thread
                }
            }
            if (ctx == null) {
                XposedBridge.log("$TAG system ctx timeout")
                return@Thread
            }
            startPinger(ctx, SYS_PING_MS, SRC_SYSTEM)
            // besides pinging our own app, the system-side loop revives
            // Doubao itself so its IM push channel (and our in-process
            // hooks) stay alive in background
            startDoubaoWaker(ctx, SYS_PING_MS)
            // ColorOS HANS 冻结豁免（真机取证的目标类，见 installHansExempt）
            installHansExempt(lpparam.classLoader, ctx)
            // the AM binder may not be up yet this early in boot — retry
            // the relay registration until it sticks
            for (i in 0..60) {
                if (startEventRelay(ctx)) break
                Thread.sleep(5_000)
            }
            XposedBridge.log("$TAG system pinger armed")
        }.start()
    }

    /** Services that bring com.larus.nova's process + push/IM channel up.
     *  system_server (uid 1000) may start them regardless of background
     *  restrictions and exported flags. */
    private val DOUBAO_WAKE = arrayOf(
        "com.ss.android.message.NotifyService",
        "com.bytedance.mira.stub.p0.StubService1",
        "com.bytedance.mira.stub.p0.StubService2",
        "com.bytedance.mira.stub.p1.StubService1")

    /** The process this module is currently loaded into, e.g. "com.larus.nova"
     *  or "com.larus.nova:push". Read from ActivityThread so it works from any
     *  thread and needs no context. */
    private fun currentProcessName(): String? = runCatching {
        val at = XposedHelpers.findClass("android.app.ActivityThread", null)
        val th = XposedHelpers.callStaticMethod(at, "currentActivityThread")
        XposedHelpers.callMethod(th, "getProcessName") as? String
    }.getOrNull()

    private fun wakeDoubao(ctx: Context) {
        // already running — nothing to do
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            @Suppress("DEPRECATION")
            if (am.runningAppProcesses?.any {
                    it.processName == PKG_DOUBAO } == true) return
        } catch (_: Throwable) {}
        for (cls in DOUBAO_WAKE) {
            try {
                ctx.startService(Intent().setComponent(
                    ComponentName(PKG_DOUBAO, cls)))
                XposedBridge.log("$TAG woke doubao via $cls")
                return
            } catch (t: Throwable) {
                XposedBridge.log("$TAG wake $cls fail: " +
                    "${t.javaClass.simpleName}")
            }
        }
        // NOTE: intentionally no activity-launch fallback — popping Doubao to
        // the foreground would be worse than a missed capture. If every
        // service rejects, the log shows which ones were tried.
    }

    private fun startDoubaoWaker(ctx: Context, periodMs: Long) {
        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                try { wakeDoubao(ctx) } catch (_: Throwable) {}
                h.postDelayed(this, periodMs)
            }
        }
        h.postDelayed(r, 20_000)   // first kick shortly after boot
    }

    /** system_server-side relay: a broadcast target that is frozen gets its
     *  queue DEFER_BY_OPLUS'd and the queue is NOT flushed on a later thaw,
     *  so the uid-1000 re-broadcast was never enough on its own. Re-deliver
     *  over Binder instead — that transaction is what actually unfreezes the
     *  app (UNFREEZE_REASON_BINDER_TXNS). Kept as a receiver so anything
     *  still emitting the legacy relay action keeps working. */
    private fun startEventRelay(ctx: Context): Boolean {
        try {
            val rcv = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    // 安全整改：这个接收器住在 system_server，转发时会以 uid 1000
                    // 的身份投递给本 App，因此必须校验发送方 —— 否则任意应用发一条
                    // 隐式广播即可绕过 App 侧的白名单，往岛上注入任意内容。
                    val uid = com.tg.dbisland.BridgeSecurity.senderUid(this)
                    if (!com.tg.dbisland.BridgeSecurity
                            .allowEventRelaySender(c, uid)) {
                        XposedBridge.log("$TAG relay rejected: sender uid=$uid")
                        return
                    }
                    val ev = i.getStringExtra("ev") ?: return
                    relayToApp(c, ev)
                }
            }
            ctx.registerReceiver(rcv,
                android.content.IntentFilter(ACT_EVENT_RELAY),
                Context.RECEIVER_EXPORTED)
            XposedBridge.log("$TAG event relay registered")
            return true
        } catch (t: Throwable) {
            XposedBridge.log("$TAG relay fail: ${t.javaClass.simpleName} ${t.message}")
            return false
        }
    }

    /** Deliver one raw JSON event to the bridge app. Binder first (thaws a
     *  frozen target), broadcast only as a fallback. */
    private fun relayToApp(ctx: Context, evJson: String) {
        try {
            val o = JSONObject(evJson)
            if (callProvider(ctx, o)) {
                XposedBridge.log("$TAG relayed event to app (binder)")
                return
            }
        } catch (_: Throwable) {}
        try {
            ctx.sendBroadcast(Intent(ACT_EVENT)
                .setComponent(ComponentName(PKG_SELF, RC_EVENT))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("v", 1).putExtra("ev", evJson))
            XposedBridge.log("$TAG relayed event to app (bcast fallback)")
        } catch (_: Throwable) {}
    }

    // ---------- ColorOS HANS 冻结豁免（真机取证，P0 根因修复） ----------
    // 证据来源：/system/framework/oplus-services.jar → classes.dex，用与豆包 APK
    // **同一套** DEX 结构解析法读出（dev/coloros_framework/dexclasses.py）：
    //
    //   CLASS Lcom/android/server/am/OplusHansProcessFreeze;  source=OplusHansProcessFreeze.java
    //     private  void freezeProcess(int uid, int pid)        ← 冻结唯一入口
    //     public static OplusHansProcessFreeze getInstance()    ← 单例，可直接取
    //     private  void unfreezeProcess(int uid, int pid, String reason)
    //     private  void unfreezeTemporarily(int uid, int pid, String reason, long ms)
    //     freezeProcess 体内：mProcFrzStateRecList.get(uid) → ProcessRecord
    //           → ProcessRecord.getThread() → 写 cgroup 冻结位
    //
    // 为什么改这里、而不是继续"提升优先级"：FREEZE_FIX.md 已实测
    // deviceidle 白名单 / standby bucket / appops / oom_score_adj=-1000 /
    // not_restrict.xml **全部无效** —— 这套冻结器不读 AOSP 那些字段。
    // 只有拦它的决策点，或者不依赖它的存活，才真正有效。
    //
    // 为什么是**动态窗口**而不是永久豁免：永久豁免 = 豆包常驻后台，功耗代价大。
    // 窗口由 App 侧续期（App 每收到一帧都被 Binder 事务解冻，是"确定在跑"的一方），
    // 并同时豁免本 App 自己 —— 否则 App 被冻时，它自己的静默判定定时器也会停。

    /** 豁免窗口上限 / 默认值：防止 App 异常导致永久豁免。 */
    private val HANS_MAX_MS = 180_000L
    private val HANS_MIN_MS = 5_000L
    private val HANS_DEFAULT_MS = 60_000L

    @Volatile private var hansExemptUntil = 0L
    @Volatile private var hansTargets: List<Pair<Int, String>> = emptyList()
    @Volatile private var hansInstalled = false
    @Volatile private var hansCls: Class<*>? = null
    private var hansSkipLogged = 0L
    private var hansKeepAliveAt = 0L
    private var hansThawLoopStarted = false
    private var hansExpiry: Runnable? = null
    /** 豁免窗口的**代际**：每次开/续窗口 +1，过期回调只在代际未变时才清窗口。 */
    private var hansGen = 0
    /** 专用 Handler。**必须是同一个实例** —— 见 [hansArm] 里第 68 条的事故说明。 */
    private val hansHandler by lazy { Handler(Looper.getMainLooper()) }
    private val HANS_THAW_MS = 1_500L

    private fun uidOfPkg(c: Context?, pkg: String): Int = try {
        c?.packageManager?.getPackageUid(pkg, 0) ?: -1
    } catch (_: Throwable) { -1 }

    /** 要豁免的两个 uid：豆包 + 本 App（App 被冻时它自己的判定定时器也会停）。 */
    private fun resolveHansTargets(c: Context?): List<Pair<Int, String>> {
        val l = ArrayList<Pair<Int, String>>(2)
        uidOfPkg(c, PKG_DOUBAO).takeIf { it > 0 }?.let { l.add(it to PKG_DOUBAO) }
        uidOfPkg(c, PKG_SELF).takeIf { it > 0 }?.let { l.add(it to PKG_SELF) }
        return l
    }

    private fun hansManager(): Any? = try {
        val cls = hansCls
            ?: XposedHelpers.findClass("com.android.server.am.OplusHansManager", null)
        XposedHelpers.callStaticMethod(cls, "getInstance")
    } catch (t: Throwable) {
        XposedBridge.log("$TAG hans getInstance fail: ${t.javaClass.simpleName}")
        null
    }

    /** 官方保活名单：`addHansKeepAliveApp(uid, pkg)` / `removeHansKeepAliveApp`。
     *
     *  为什么优先用这个而不是逐个拦冻结决策点：这是 HANS **自己**的语义
     *  （真机取证：`IOplusHansManager` 接口里就有 `addHansKeepAliveApp`
     *  与 `hasImpCaseOrKeepAlive`），它内部的冻结判定本来就会读这份名单 ——
     *  顺着它的设计走，比在十几个冻结入口上打补丁稳得多。
     *  决策点拦截只作为兜底（见 hansFreeze hook）。 */
    private fun hansSetKeepAlive(on: Boolean, why: String) {
        val inst = hansManager() ?: run {
            XposedBridge.log("$TAG hans keepAlive=$on 跳过：拿不到 OplusHansManager 实例")
            return
        }
        for ((uid, pkg) in hansTargets) {
            try {
                XposedHelpers.callMethod(inst,
                    if (on) "addHansKeepAliveApp" else "removeHansKeepAliveApp",
                    uid, pkg)
                XposedBridge.log("$TAG hans keepAlive=$on uid=$uid pkg=$pkg（$why）")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hans keepAlive=$on uid=$uid fail: " +
                    "${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /** 主动解冻：`hansUnFreeze(uid, pkgName, reason)`。
     *  已经冻住的进程不会因为"以后不再冻"而自己醒来，所以每次开窗都补一次。
     *  成功与否以 HANS 自己的 logcat（`unfreeze uid: 10375 ... reason: X`）为准。 */
    private fun hansThaw(why: String) {
        val inst = hansManager() ?: return
        for ((uid, pkg) in hansTargets) {
            try {
                val r = XposedHelpers.callMethod(inst, "hansUnFreeze",
                    uid, pkg, "island-$why")
                // 只在"真的解开了"时记日志 —— 这条循环每 1.5s 跑一次，否则刷屏
                if (r == true) XposedBridge.log("$TAG hans thaw uid=$uid → true（$why）")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hans thaw uid=$uid fail: " +
                    "${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /** 目标 uid 且处于豁免窗口内？ */
    private fun hansInWindow(uid: Int): Boolean =
        hansTargets.any { it.first == uid } &&
            android.os.SystemClock.elapsedRealtime() < hansExemptUntil

    private fun hansLogSkip(uid: Int, where: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        // HANS 大约每 12s 重试一次冻结；这里 60s 记一次就够自证了，
        // 免得一块日志面板被同一条记录刷满
        if (now - hansSkipLogged < 60_000L) return
        hansSkipLogged = now
        XposedBridge.log("$TAG HANS 已拦下冻结 uid=$uid via=$where " +
            "剩余=${(hansExemptUntil - now) / 1000}s")
    }

    /** 从对象图里找出"值等于目标 uid"的 int/Integer 字段。
     *
     *  为什么不直接读厂商字段名：DEX 里字段名/索引的解析对这类超大类并不可靠
     *  （实测 dump `OplusHansPackage` 字段表时出现大量错位重复），
     *  而 uid 是**值** —— 靠值识别不需要知道它叫什么。
     *  深度受限，避免在 system_server 的对象图里迷路。 */
    private fun findUidIn(o: Any?, depth: Int): Int? {
        if (o == null || depth < 0) return null
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val t = f.type
                    if (t == Int::class.javaPrimitiveType) {
                        val v = f.getInt(o)
                        if (hansTargets.any { it.first == v }) return v
                    } else if (t == Integer::class.java) {
                        val v = f.get(o) as? Int
                        if (v != null && hansTargets.any { it.first == v }) return v
                    } else if (depth > 0 && !t.isPrimitive &&
                        t != String::class.java && !t.name.startsWith("java.")) {
                        findUidIn(try { f.get(o) } catch (_: Throwable) { null },
                            depth - 1)?.let { return it }
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    /** 状态机 → 它所属应用的 uid：先走 DEX 里确认存在的 `mHansPkg` 字段，
     *  失败再退回按值在状态机自身字段里找。 */
    private fun smUid(sm: Any?): Int? = try {
        val pkgObj = XposedHelpers.getObjectField(sm, "mHansPkg")
        findUidIn(pkgObj, 1) ?: findUidIn(sm, 1)
    } catch (_: Throwable) {
        findUidIn(sm, 1)
    }

    /** 豁免窗口内周期性补一次解冻。
     *
     *  兜底逻辑：如果你拦不住"冻"，那就保证"很快解"。
     *  `hansUnFreeze(uid, pkg, reason)` 是**实测可用**的厂商 API
     *  （真机日志：`hans thaw uid=10045 → true`）。只在窗口内跑，
     *  窗口由 App 在有回答在飞的时候续期，所以空闲时这条循环是空转的。 */
    private fun startHansThawLoop() {
        if (hansThawLoopStarted) return
        hansThawLoopStarted = true
        val h = Handler(Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                try {
                    if (hansExemptUntil > android.os.SystemClock.elapsedRealtime() &&
                        hansTargets.isNotEmpty()) hansThaw("keepalive")
                } catch (_: Throwable) {}
                h.postDelayed(this, HANS_THAW_MS)
            }
        })
    }

    /** 开/续豁免窗口。窗口到期由 system_server 自己的定时器撤销保活 ——
     *  system_server 永远不会被冻，这个定时器是本方案里最可信的一环。 */
    private fun hansArm(ctx: Context, ms: Long, why: String) {
        if (hansTargets.isEmpty()) hansTargets = resolveHansTargets(ctx)
        val now = android.os.SystemClock.elapsedRealtime()
        hansExemptUntil = now + ms
        XposedBridge.log("$TAG HANS 豁免窗口 ${ms / 1000}s why=$why uids=" +
            hansTargets.joinToString(",") { it.first.toString() })
        // 保活名单 30s 内只真调一次（要不太吵；它本身是幂等的）
        if (now - hansKeepAliveAt > 30_000L) {
            hansKeepAliveAt = now
            hansSetKeepAlive(true, why)
        }
        hansThaw(why)
        // 第 68 条（真机事故）：原来这里每次 `Handler(Looper.getMainLooper())` **新建
        // 一个实例**再 `removeCallbacks` —— message 在队列里是按 (Handler, Runnable)
        // 配对的，用新实例移不掉旧实例 post 的那条，于是每次续期都留下一个"旧窗口
        // 到期"回调，几秒后把**当前**窗口清掉：
        //   22:19:59.028  HANS 豁免窗口 60s why=chat.delta
        //   22:19:59.046  hans keepAlive=false（窗口到期）   ← 18ms 后就"到期"
        //   22:20:09 / 22:20:19 / …  每 10s 一次
        // 后果：轮次进行中窗口被反复清空 → 出现最长约一个续期间隔的无保护间隙，
        // 豆包仍会被冻。现在用**同一个 Handler** + **代际判断**双保险。
        val gen = ++hansGen
        hansExpiry?.let { hansHandler.removeCallbacks(it) }
        val r = Runnable {
            if (gen != hansGen) return@Runnable      // 已被更新的窗口取代，不许清
            hansExemptUntil = 0L
            hansExpiry = null
            hansSetKeepAlive(false, "窗口到期")
        }
        hansExpiry = r
        hansHandler.postDelayed(r, ms)
    }

    private fun installHansExempt(cl: ClassLoader?, ctx: Context) {
        if (hansInstalled) return
        hansInstalled = true
        val cn = "com.android.server.am.OplusHansProcessFreeze"
        try {
            hansTargets = resolveHansTargets(ctx)
            // ① 兜底：真正执行冻结的类（void，跳过无返回值歧义）
            val cls = XposedHelpers.findClass(cn, cl)
            XposedHelpers.findAndHookMethod(cls, "freezeProcess",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!hansShouldSkip(p, 0)) return
                        p.result = null          // void 方法：跳过本次冻结
                    }
                })
            // ② 兜底：冻结决策入口。
            //    真机实测：只看 ① 不够 —— 关掉豆包后仍出现
            //      OplusHansManager : freeze uid: 10375 ... scene: |StrictMode-3|LcdOn
            //    说明这条路径的子类/分支没走到 OplusHansProcessFreeze。
            //    DEX 证据：`hansFreeze(int,String)` 直接转发到
            //    `hansFreeze(int,String,boolean)`，后者做 scene 判定并驱动
            //    `HansAppStateMachine.forceEnterFrozen()`。这里按 uid 拦下。
            val mgr = XposedHelpers.findClass("com.android.server.am.OplusHansManager", cl)
            hansCls = mgr
            XposedHelpers.findAndHookMethod(mgr, "hansFreeze",
                Int::class.javaPrimitiveType, String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!hansShouldSkip(p, 1)) return
                        p.result = false         // boolean 返回：false = 未冻结
                    }
                })
            // ③ 兜底：状态机。真机实测 ①② 都没被触发（关豆包后仍然
            //    `OplusHansManager : freeze uid: 10375`），说明这条 StrictMode
            //    路径不走 `hansFreeze`。但所有冻结最终都要落到"每个应用一个"的
            //    `HansAppStateMachine`（DEX：`OplusHansPackage.getSM()` →
            //    `forceEnterFrozen()`），所以拦这里是**汇聚点**。
            val smCls = XposedHelpers.findClass(
                "com.android.server.hans.states.HansAppStateMachine", cl)
            XposedHelpers.findAndHookMethod(smCls, "forceEnterFrozen",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val uid = smUid(p.thisObject) ?: return
                            if (!hansInWindow(uid)) return
                            p.result = null      // void：不放行"进入冻结"
                            hansLogSkip(uid, "forceEnterFrozen")
                        } catch (_: Throwable) {}
                    }
                })
            XposedHelpers.findAndHookMethod(smCls, "forceTransitionState",
                Int::class.javaPrimitiveType, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val uid = smUid(p.thisObject) ?: return
                            if (!hansInWindow(uid)) return
                            val sid = (p.args?.getOrNull(0) as? Int) ?: return
                            val name = XposedHelpers.callStaticMethod(
                                smCls, "getStateName", sid) as? String
                            if (name == null ||
                                !name.contains("rozen", ignoreCase = false)) return
                            p.result = null
                            hansLogSkip(uid, "forceTransition→$name")
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG hans SM hooks installed (forceEnterFrozen/" +
                "forceTransitionState)")

            // ⑤ ★真正的执行点：离线 x-ref 定位（dev/coloros_framework/dexfindstr.py）。
            //    方法：在 oplus-services.jar 的常量池里找到 'freeze uid: ' 的下标，
            //    再扫描每个 code_item 的原始字节，找 `const-string`（format 21c
            //    `1a AA | BBBB`）引用该下标的方法 —— 结果**唯一**：
            //        Lcom/android/server/hans/freeze/HansCGroup;->hansFreezeLocked
            //    其方法体内写 '/dev/freezer/frozen/cgroup.procs'
            //    （常量池同时有 'cgroup failed, freeze uid: '）。
            //    也就是说：不管上层走 StrictMode / cached / fast freezer 哪条决策路径，
            //    最后**都必须**经过这里才能把某个 uid 冻住。
            //    前几轮拦 OplusHansProcessFreeze / OplusHansManager.hansFreeze /
            //    状态机都一次没命中，就是因为它们不是这条路径的必经点。
            try {
                val cg = XposedHelpers.findClass(
                    "com.android.server.hans.freeze.HansCGroup", cl)
                XposedHelpers.findAndHookMethod(cg, "hansFreezeLocked",
                    Int::class.javaPrimitiveType, String::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            try {
                                val uid = (p.args?.getOrNull(0) as? Int) ?: return
                                if (!hansInWindow(uid)) return
                                p.result = false     // 如实返回"没冻成"
                                hansLogSkip(uid, "HansCGroup.hansFreezeLocked(int)")
                            } catch (_: Throwable) {}
                        }
                    })
                XposedHelpers.findAndHookMethod(cg, "hansFreezeLocked",
                    XposedHelpers.findClass(
                        "com.android.server.hans.OplusHansPackage", cl),
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            try {
                                val uid = findUidIn(p.args?.getOrNull(0), 1)
                                    ?: return
                                if (!hansInWindow(uid)) return
                                p.result = false
                                hansLogSkip(uid, "HansCGroup.hansFreezeLocked(pkg)")
                            } catch (_: Throwable) {}
                        }
                    })
                XposedBridge.log("$TAG hans cgroup hooks installed " +
                    "(HansCGroup.hansFreezeLocked ×2)")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hans cgroup hook fail: " +
                    "${t.javaClass.simpleName} ${t.message}")
            }

            // 取证用的 `android.util.Log.i` 探针已经拿到结论（定位到 HansCGroup
            // 的 x-ref 记录见 dev/coloros_framework/dexfindstr.py），
            // 不再保留：`Log.i` 在 system_server 里是超热点，挂它等于给每条日志
            // 加一次字符串比较，不值得留在成品里。

            XposedBridge.log("$TAG hans hooks installed cn=$cn + OplusHansManager.hansFreeze" +
                " uids=" + hansTargets.joinToString(",") { it.first.toString() } +
                " receiver=$ACT_HANS")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hans hook install fail: " +
                "${t.javaClass.simpleName} ${t.message}")
            return
        }
        // 窗口续期接收器：**必须重试**。
        // 真机日志（首次实装）：
        //   hans install fail: NullPointerException
        //     Attempt to invoke interface method
        //     'IActivityManager.registerReceiverWithFeature(...)' on a null object reference
        // 原因：模块在 SystemServer.run() 期间就注入了，那时 `getSystemContext()`
        // 已经有了，但 AMS binder 还没挂上 —— 这与 startEventRelay 当初要重试
        // 60 次是同一个坑。钩子（上面）不依赖 AMS，所以立刻装；
        // 接收器分开重试，否则整个豁免都拿不到续期。
        Thread {
            for (i in 0..60) {
                if (registerHansReceiver(ctx)) {
                    XposedBridge.log("$TAG hans receiver registered (第 $i 次)")
                    startHansThawLoop()
                    return@Thread
                }
                try { Thread.sleep(5_000) } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            XposedBridge.log("$TAG hans receiver 注册失败（60 次重试后放弃）")
        }.start()
    }

    /** 注册豁免窗口续期接收器；成功 true。失败（多为 AMS 未就绪）由调用方重试。 */
    private fun registerHansReceiver(ctx: Context): Boolean = try {
        val rcv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val uid = com.tg.dbisland.BridgeSecurity.senderUid(this)
                if (!com.tg.dbisland.BridgeSecurity.allowCommandSender(c, uid)) {
                    XposedBridge.log("$TAG HANS 豁免请求被拒 uid=$uid")
                    return
                }
                val ms = i.getLongExtra("ms", HANS_DEFAULT_MS)
                    .coerceIn(HANS_MIN_MS, HANS_MAX_MS)
                hansArm(c, ms, i.getStringExtra("why") ?: "app")
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.registerReceiver(rcv, android.content.IntentFilter(ACT_HANS),
                com.tg.dbisland.BridgeSecurity.PERM_CONTROL, null,
                Context.RECEIVER_EXPORTED)
        else
            ctx.registerReceiver(rcv, android.content.IntentFilter(ACT_HANS),
                com.tg.dbisland.BridgeSecurity.PERM_CONTROL, null)
        true
    } catch (t: Throwable) {
        XposedBridge.log("$TAG hans receiver try fail: " +
            "${t.javaClass.simpleName}")
        false
    }

    /** 两个冻结 Hook 共用的"该不该跳过"判定。
     *  [uidArg] 是 uid 在参数表里的下标（两个类不一样）。
     *  只跳过**当前豁免窗口内、且属于目标 uid** 的调用；其余一律放行。 */
    private fun hansShouldSkip(p: XC_MethodHook.MethodHookParam, uidArg: Int): Boolean = try {
        val uid = (p.args?.getOrNull(uidArg) as? Int)
        if (uid == null || hansTargets.none { it.first == uid }) false
        else {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= hansExemptUntil) false
            else {
                if (now - hansSkipLogged > 5_000L) {
                    hansSkipLogged = now
                    XposedBridge.log("$TAG HANS 已拦下冻结 uid=$uid " +
                        "剩余=${(hansExemptUntil - now) / 1000}s")
                }
                true
            }
        }
    } catch (t: Throwable) {
        XposedBridge.log("$TAG hans skip fail: ${t.javaClass.simpleName}")
        false
    }

    private fun systemContext(logErr: Boolean = false): Context? = try {
        val at = XposedHelpers.findClass("android.app.ActivityThread", null)
        val th = XposedHelpers.callStaticMethod(at, "currentActivityThread")
        XposedHelpers.callMethod(th, "getSystemContext") as? Context
    } catch (t: Throwable) {
        if (logErr) XposedBridge.log(
            "$TAG ctx err: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    /** Keep-alive ping. `src` tells the app WHERE this ping came from: the
     *  copy that runs inside Doubao is proof the module is actually injected
     *  (the system_server copy keeps running even when Doubao is dead or the
     *  scope is unchecked), so the two must not be conflated. */
    private fun startPinger(ctx: Context, periodMs: Long, src: String) {
        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                sendTo(ctx, ACT_KEEPALIVE, RC_KEEPALIVE, src = src)
                h.postDelayed(this, periodMs)
            }
        }
        h.post(r)
    }
}
