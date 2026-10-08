package com.tg.dbisland.ui

import androidx.compose.runtime.Immutable
import com.tg.dbisland.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 一条被推送到岛上的「答复」（会话内的一条消息）。
 *
 *  [mid] 是豆包的消息号（模块侧 `chat.*` 事件的 mid），用来做增量合并：
 *  同一条回复的 `chat.delta` 会不停追加到同一个 [mid] 上。
 *
 *  [role] / [atMs] 是**第 41 条落盘**加的：磁盘上每行一条 JSON，恢复时要知道
 *  这条是豆包说的还是用户自己发的（聊天页要分别画），以及它的时间。 */
@Immutable
data class HubMessage(
    val mid: String,
    /** 累积的正文（`kind=text` 的增量）。 */
    val text: String = "",
    /** 最后一条 think/tool 状态行（`kind=think`）。 */
    val think: String = "",
    /** 已收到 chat.end（或 chat.reply 的全量文本）。 */
    val ended: Boolean = false,
    val atMs: Long = System.currentTimeMillis(),
    /** `bot` = 豆包的回答；`user` = 用户从 App / 岛上回过去的那句。 */
    val role: String = "bot",
)

/** 一个豆包会话（左侧会话框里的一行）。
 *
 *  [cid] 是豆包真正的 conversation id —— 岛上那张卡用的 `reply:<cid>` 也是它。
 *  没有 cid 的老测试事件落到 key `-`（与 [com.tg.dbisland.IslandBridge] 的
 *  `keyOf()` 口径一致）。 */
@Immutable
data class HubConversation(
    val cid: String,
    /** 会话名（豆包侧自动标题；拿不到就留空，UI 显示「豆包 <尾6位>」）。 */
    val name: String = "",
    /** 顺序 = 事件到达顺序（也就是岛上「先到者占主岛」的那个顺序）。 */
    val messages: List<HubMessage> = emptyList(),
    /** 还在推流（收到过 chat.start/delta 且还没 end）。 */
    val streaming: Boolean = false,
    val lastAtMs: Long = System.currentTimeMillis(),
) {
    val title: String
        get() = name.ifBlank {
            if (cid.isBlank() || cid == "-") "豆包会话" else "豆包 " + cid.takeLast(6)
        }

    /** 侧栏一行的摘要（限长，避免长正文把侧栏撑开）。 */
    val preview: String
        get() = (messages.lastOrNull()?.text ?: "").replace('\n', ' ').trim().takeLast(48)

    /** 答复条数（侧栏角标）。 */
    val replies: Int get() = messages.size
}

/** 日志级别 —— 对应 `ChatStore.log()` 推来的 `sys.log`。 */
enum class HubLogLevel { INFO, WARN, ERROR }

@Immutable
data class HubLogEntry(
    val atMs: Long,
    val level: HubLogLevel,
    val text: String,
)

/** 模块运行情况（「日志」页顶部那几张状态卡）。
 *
 *  [alive] 的判据与 [com.tg.dbisland.EnvCheck] 一致：只有**豆包进程内**那份
 *  模块心跳（广播 extra `src=doubao`）才算数 —— system_server 那份在豆包死掉、
 *  作用域没勾的时候照样发，不能拿它当「模块生效」的证据。 */
@Immutable
data class HubRuntime(
    val alive: Boolean = false,
    val lastPingAtMs: Long = 0L,
    val pings: Int = 0,
    /** 保活窗是否开着（模块上报 `keepalive armed/stopped`）。 */
    val keepAlive: Boolean = false,
    /** 收到的推流帧数（chat./plan.）。 */
    val streamFrames: Int = 0,
    val lastFrameAtMs: Long = 0L,
    val sends: Int = 0,
    val lastSendOk: Boolean? = null,
    val lastSendDetail: String = "",
    /** 岛连接状态文案（`IslandClient.State` 的 toString）。 */
    val islandState: String = "-",
)

/** UI 一次性读到的整份状态。 */
@Immutable
data class HubState(
    val conversations: List<HubConversation> = emptyList(),
    val logs: List<HubLogEntry> = emptyList(),
    val runtime: HubRuntime = HubRuntime(),
)

/**
 * 应用内 UI 的唯一数据源。
 *
 * 为什么新起一份而不是复用 [com.tg.dbisland.ChatStore]：旧的 ChatStore 是给那个
 * `chat.html` WebView 用的「**平铺**事件转发器」—— 它按 `mid` 存正文、每条事件
 * 原样 `listener` 出去，**完全没有会话维度**。新界面左边要列会话、右边要看某一条
 * 会话的完整对话，就必须按 `cid` 归档 —— 这是数据模型问题，不是换皮。
 *
 * 线程：事件从 Binder（provider）/ 广播 / 模拟通道线程进来，统一在 [lock] 里改
 * 内部模型；UI 只读 [state]。发布策略（性能关键）：`chat.delta` 每个 token 都会
 * 改模型，但**最多每 [DELTA_THROTTLE_MS] 发布一次**，其余事件（start/end/日志/
 * 心跳）立刻发布，所以「结束」永远不会被节流吞掉。
 */
object BridgeHub {

    private const val MAX_CONVERSATIONS = 40
    private const val MAX_MESSAGES_PER_CONV = 60
    private const val MAX_LOGS = 800
    private const val DELTA_THROTTLE_MS = 120L
    /** 同一条消息最多每 [MIRROR_THROTTLE_MS] 落一次盘（见 [mirrorConv]）。 */
    private const val MIRROR_THROTTLE_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    private val lock = Any()

    // ---- 内部可变模型（只在 lock 内改）----
    private val convs = ArrayList<HubConversation>()
    private val logs = ArrayList<HubLogEntry>()
    private var runtime = HubRuntime()

    // ---- 发布节流状态（也只在 lock 内改）----
    private var dirty = false
    private var flushScheduled = false
    private var lastPublishAtMs = 0L
    private var flushJob: Job? = null

    /** 落盘节流：key = "cid\tmid" → 上次写盘时刻（见 [mirrorConv]）。 */
    private val lastMirrorAt = HashMap<String, Long>()

    init {
        // 第 41 条：启动就从磁盘把聊天记录读回来（修掉 R9「进程被杀即丢」）。
        // 必须在第一次 handle() 之前完成 —— object 初始化本身就保证这一点。
        // ChatStore 的目录也在这里交给它（BridgeHub 没有 Context，
        // 用 Application 单例；BridgeApp.onCreate 里已经设好了 instance）。
        val app = com.tg.dbisland.BridgeApp.instance
        if (app != null) {
            ChatStore.init(java.io.File(app.filesDir, "chat"))
            restoreFromDisk()
        }
    }

    /** 把磁盘上的聊天记录灌回内存模型（只在 [init] 里调一次）。 */
    private fun restoreFromDisk() {
        val loaded = runCatching { ChatStore.loadFor() }
        val saved = loaded.getOrNull()
        if (saved == null) {
            addRestoreLog("聊天记录读取失败: ${loaded.exceptionOrNull()?.javaClass?.simpleName} " +
                "${loaded.exceptionOrNull()?.message}")
            return
        }
        if (saved.isEmpty()) {
            // 冷启动且磁盘上还没有记录时走这里 —— 也要留一行，否则
            // 「到底读没读」在真机上无从判断（第 41 条排查用）。
            addRestoreLog("磁盘上没有可恢复的聊天记录（${ChatStore.stats()}）")
            return
        }
        synchronized(lock) {
            for (c in saved) {
                if (convs.any { it.cid == c.cid }) continue
                convs.add(c)
            }
            // 顺序 = 最后活动时间（就是磁盘上那份「先到者」顺序）
            convs.sortBy { it.lastAtMs }
        }
        addRestoreLog("已从磁盘恢复 ${saved.size} 条会话的聊天记录" +
            "（共 ${saved.sumOf { it.messages.size }} 条消息）")
        publish()
    }

    private fun addRestoreLog(text: String) {
        // 用 logcat 与界面两条路各记一次：这段代码跑在 object 初始化**期间**，
        // 此刻 logLine 那条路（LogStore/BridgeApp）还在自己的初始化里，
        // 不能再往里钻 —— 直接写 logcat 最稳，然后补一条内存记录。
        android.util.Log.i("IslandBridge", text)
        synchronized(lock) { addLogLocked(HubLogLevel.INFO, text) }
    }

    /** 把某条消息的当前全文镜像到磁盘（第 41 条）。
     *
     *  为什么需要节流：`chat.delta` 每来个 token 就改一次模型，如果每次都
     *  重写一行（ChatStore 是「读全文→改一行→写回」），磁盘会被写爆；
     *  但**又不能只在结束时写** —— 进程被杀正是「没有结束」的那种情况，
     *  所以按 [MIRROR_THROTTLE_MS] 落中间态，结束时 force 一次。
     *
     *  只存「用户能在聊天页看到的东西」：正文、think 行、是否结束、时间、
     *  角色 —— 不额外存任何标记（用户要求）。 */
    private fun mirrorConv(cid: String, m: HubMessage, force: Boolean = false) {
        if (force) {
            runCatching { ChatStore.mirror(cid, m.mid, m.text, m.think, m.ended, m.role) }
            return
        }
        val key = "$cid\t${m.mid}"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = lastMirrorAt[key] ?: 0L
            if (now - last < MIRROR_THROTTLE_MS) return
            lastMirrorAt[key] = now
            if (lastMirrorAt.size > 400) {
                // 防止键无限增长（会话/消息被裁掉后清一次）
                val keep = HashSet<String>()
                for (c in convs) for (x in c.messages) keep.add("${c.cid}\t${x.mid}")
                lastMirrorAt.keys.retainAll(keep)
            }
        }
        runCatching { ChatStore.mirror(cid, m.mid, m.text, m.think, m.ended, m.role) }
    }

    // ---------------------------------------------------------------- 事件入口

    /** 唯一入口 —— [com.tg.dbisland.BridgeApp] 分发事件时调这里。 */
    fun handle(o: JSONObject) {
        when (o.optString("t")) {
            // 保活 ping 的静默事件：只续存活时间，不进日志（否则每 3s 刷满）
            "silent.ping" -> markAlive(quiet = true)
            "keepalive" -> onKeepAlive(o)
            "sys.log" -> publishLog(HubLogLevel.INFO, o.optString("text"))
            "plan.start", "plan.progress", "plan.end" -> {
                val t = o.optString("t")
                val txt = when (t) {
                    "plan.start" -> "计划开始：${o.optString("title")}"
                    "plan.progress" ->
                        "计划进度 ${o.optInt("done")}/${o.optInt("total")} " +
                            o.optString("name")
                    else -> "计划结束：${o.optString("text")}"
                }
                bumpFrames()
                publishLog(HubLogLevel.INFO, txt.trim())
            }
            "chat.start" -> {
                upsertConversation(o) { it.copy(streaming = true) }
                bumpFrames()
                publish()
            }
            "chat.delta" -> {
                applyDelta(o)
                bumpFrames()
                publishThrottled()
            }
            "chat.end" -> {
                upsertConversation(o) { it.copy(streaming = false) }
                finishMessage(o.optString("cid"), o.optString("mid"), null,
                    o.optString("cname"))
                bumpFrames()
                publish()
            }
            "chat.reply" -> {
                // 全量文本：替换**这条 mid** 的正文（第 47 条：以前拿「最后一条」
                // 当替身，真机把回答全文写进了用户刚发的那句话里）
                upsertConversation(o) { it.copy(streaming = false) }
                finishMessage(
                    o.optString("cid"), o.optString("mid"), o.optString("text"),
                    o.optString("cname"),
                )
                publish()
            }
            "chat.conv" -> {
                val cid = o.optString("cid")
                val name = o.optString("cname")
                if (cid.isNotBlank() && name.isNotBlank()) {
                    upsertConversation(o) { it.copy(name = name) }
                    publish()
                }
            }
            "chat.async" -> publishLog(HubLogLevel.INFO, "已转为后台任务，岛上继续跟进")
            "reply.stalled" -> publishLog(HubLogLevel.WARN, "模块报告 8s 无任何推流 → 唤醒豆包")
            "send.result" -> onSendResult(o)
        }
    }

    /** 模块心跳（`KEEPALIVE` 且 `src=doubao`）—— 由
     *  [com.tg.dbisland.Receivers.KeepAliveReceiver] 调。 */
    fun markAlive(quiet: Boolean = false) {
        synchronized(lock) {
            runtime = runtime.copy(
                alive = true,
                lastPingAtMs = System.currentTimeMillis(),
                pings = runtime.pings + 1,
            )
            if (!quiet) addLogLocked(HubLogLevel.INFO, "模块心跳")
        }
        publish()
    }

    fun noteIslandState(text: String) {
        synchronized(lock) { runtime = runtime.copy(islandState = text) }
        publish()
    }

    /** 清空会话与日志（设置页「清空记录」）。
     *
     *  第 41 条起**连带磁盘**：`filesDir/chat` 下的每份 `.jsonl` 与
     *  `filesDir/logs` 下的每一片都一并删掉，否则重启后「清空」过的会话又回来了。 */
    fun clearAll() {
        synchronized(lock) {
            convs.clear()
            logs.clear()
            lastMirrorAt.clear()
        }
        runCatching { ChatStore.clearAll() }
        runCatching { com.tg.dbisland.LogStore.clearAll() }
        publish()
    }

    /** 只清内存里那份日志环形缓冲（磁盘上的分片归 [com.tg.dbisland.LogStore]
     *  管，「日志」页的清空按钮两个都会调）。 */
    fun clearLogMemory() {
        synchronized(lock) { logs.clear() }
        publish()
    }

    /** 用户从**悬浮窗回复面板**（第 48 条起唯一的上行入口）发出去的那句 ——
     *  也记进聊天记录（第 41 条：界面上能看到的对话，磁盘上要有同一份）。
     *
     *  同时进**内存模型**：这样用户发完立刻在聊天页看到自己那条，
     *  而不是等 App 重启后从磁盘恢复出来（磁盘只有重启后才会被读）。 */
    fun noteUserSend(cid: String, text: String) {
        if (cid.isBlank() || text.isBlank()) return
        val key = keyOf(cid)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            var i = convs.indexOfFirst { it.cid == key }
            if (i < 0) {
                convs.add(HubConversation(cid = key))
                i = convs.size - 1
            }
            val conv = convs[i]
            val msgs = conv.messages.toMutableList()
            msgs.add(HubMessage(mid = "u$now", text = text, ended = true,
                atMs = now, role = "user"))
            convs[i] = conv.copy(messages = trimMessages(msgs), lastAtMs = now)
            trimConversationsLocked()
        }
        runCatching { ChatStore.mirrorUser(key, text, now) }
        publish()
    }

    // ---------------------------------------------------------------- 模型改动

    private fun upsertConversation(o: JSONObject, f: (HubConversation) -> HubConversation) {
        val cid = keyOf(o.optString("cid"))
        val name = o.optString("cname")
        synchronized(lock) {
            val i = convs.indexOfFirst { it.cid == cid }
            val cur = if (i >= 0) convs[i] else HubConversation(cid = cid)
            val next = f(cur).copy(
                name = if (name.isNotBlank()) name else cur.name,
                lastAtMs = System.currentTimeMillis(),
            )
            if (i >= 0) convs[i] = next else convs.add(next)
            trimConversationsLocked()
        }
    }

    private fun applyDelta(o: JSONObject) {
        val cid = keyOf(o.optString("cid"))
        val mid = o.optString("mid").ifBlank { "m" }
        val kind = o.optString("kind")
        val text = o.optString("text")
        val cname = o.optString("cname")
        var miCid: String? = null
        var miMsg: HubMessage? = null
        synchronized(lock) {
            var i = convs.indexOfFirst { it.cid == cid }
            if (i < 0) {
                convs.add(HubConversation(cid = cid, name = cname, streaming = true))
                i = convs.size - 1
            }
            val conv = convs[i]
            val msgs = conv.messages.toMutableList()
            var j = msgs.indexOfLast { it.mid == mid }
            if (j < 0) {
                msgs.add(HubMessage(mid = mid))
                j = msgs.size - 1
            }
            val m = msgs[j]
            msgs[j] = when (kind) {
                "text" -> m.copy(text = m.text + text)
                else -> m.copy(think = text.ifBlank { m.think })
            }
            miCid = cid
            miMsg = msgs[j]
            convs[i] = conv.copy(
                name = if (cname.isNotBlank()) cname else conv.name,
                messages = trimMessages(msgs),
                streaming = true,
                lastAtMs = System.currentTimeMillis(),
            )
            trimConversationsLocked()
        }
        if (miCid != null && miMsg != null) mirrorConv(miCid, miMsg!!)
    }

    /** 一条回答收尾（`chat.end` / `chat.reply`）。
     *
     *  **必须按事件里的 [mid] 找那条回答**，不能用「最后一条」当替身。真机事故
     *  （第 47 条）：用户在回答推流中途又发了一条时，模块那轮回答的 mid 会漂，
     *  于是 `chat.reply` 到达时 App 里的最后一条是**用户自己刚发的那句话** ——
     *  旧代码把回答的全文（388 字）写进了用户那条（用户原本只发了 4 字），
     *  而那条回答自己永远停在头部、`ended` 还是 false。
     *
     *  mid 缺失或对不上时退到「最后一条**机器人**消息」——**绝不碰 role=user
     *  的那条**（那是用户自己打的字）。 */
    private fun finishMessage(cidRaw: String, mid: String, fullText: String?,
                              cname: String) {
        val cid = keyOf(cidRaw)
        var miMsg: HubMessage? = null
        synchronized(lock) {
            val i = convs.indexOfFirst { it.cid == cid }
            if (i < 0) return
            val conv = convs[i]
            val msgs = conv.messages.toMutableList()
            if (msgs.isEmpty()) return
            var last = if (mid.isNotEmpty()) msgs.indexOfLast { it.mid == mid } else -1
            if (last < 0) last = msgs.indexOfLast { it.role != "user" }
            if (last < 0) return
            val m = msgs[last]
            msgs[last] = m.copy(
                text = if (!fullText.isNullOrEmpty()) fullText else m.text,
                ended = true,
            )
            miMsg = msgs[last]
            convs[i] = conv.copy(
                name = if (cname.isNotBlank()) cname else conv.name,
                messages = msgs.toList(),
                streaming = false,
                lastAtMs = System.currentTimeMillis(),
            )
        }
        if (miMsg != null) mirrorConv(cid, miMsg!!, force = true)
    }

    private fun trimMessages(msgs: List<HubMessage>): List<HubMessage> =
        if (msgs.size <= MAX_MESSAGES_PER_CONV) msgs.toList()
        else msgs.subList(msgs.size - MAX_MESSAGES_PER_CONV, msgs.size).toList()

    private fun trimConversationsLocked() {
        if (convs.size <= MAX_CONVERSATIONS) return
        val keep = convs.sortedByDescending { it.lastAtMs }.take(MAX_CONVERSATIONS)
        convs.clear()
        // 保留原来的到达顺序（它就是岛上「先到者」的顺序）
        convs.addAll(keep.sortedBy { it.lastAtMs })
    }

    private fun keyOf(cid: String): String = if (cid.isBlank()) "-" else cid

    // ---------------------------------------------------------------- 其余状态

    private fun onKeepAlive(o: JSONObject) {
        val armed = o.optString("state") != "stopped"
        synchronized(lock) {
            runtime = runtime.copy(keepAlive = armed)
            addLogLocked(
                HubLogLevel.INFO,
                "豆包保活${if (armed) "开始" else "结束"}（${o.optString("reason")}）",
            )
        }
        publish()
    }

    private fun onSendResult(o: JSONObject) {
        val ok = o.optBoolean("ok")
        val err = o.optString("err")
        val act = o.optString("act")
        synchronized(lock) {
            runtime = runtime.copy(
                sends = runtime.sends + 1,
                lastSendOk = ok,
                lastSendDetail = if (ok) "成功" else err.ifBlank { "失败" },
            )
            addLogLocked(
                if (ok) HubLogLevel.INFO else HubLogLevel.ERROR,
                if (ok) "上行动作完成（$act）" else "上行动作失败（$act）：$err",
            )
        }
        publish()
    }

    private fun bumpFrames() {
        synchronized(lock) {
            runtime = runtime.copy(
                streamFrames = runtime.streamFrames + 1,
                lastFrameAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** 界面日志的入口。
     *
     *  **落盘只有一条路**：App 侧的日志一律经
     *  [com.tg.dbisland.BridgeApp.logLine]（它写 logcat + 这里 + [LogStore]）。
     *  所以这里**不再**往磁盘写一份 —— 早先两处都写，真机日志每行出现两次
     *  （第 41 条复测时发现并修掉）。
     *
     *  唯一的例外是模块直接推来的 `sys.log`（模块目前并不发这个事件，
     *  见 xposed/ 里没有 `sys.log` 字样），它会被记进内存面板；要让模块日志
     *  也落盘，得在 [com.tg.dbisland.BridgeApp.handleEvent] 里显式调一次
     *  [LogStore.append]。 */
    fun publishLog(level: HubLogLevel, text: String) {
        if (text.isBlank()) return
        synchronized(lock) { addLogLocked(level, text) }
        publish()
    }

    private fun addLogLocked(level: HubLogLevel, text: String) {
        logs.add(HubLogEntry(System.currentTimeMillis(), level, text))
        while (logs.size > MAX_LOGS) logs.removeAt(0)
    }

    // ---------------------------------------------------------------- 发布

    private fun publish() {
        val snapshot: HubState
        synchronized(lock) {
            dirty = false
            lastPublishAtMs = System.currentTimeMillis()
            snapshot = HubState(
                conversations = convs.toList(),
                logs = logs.toList(),
                runtime = runtime,
            )
        }
        _state.value = snapshot
    }

    /** `chat.delta` 走节流：模型已经改好了，只是**先不发布**，
     *  [DELTA_THROTTLE_MS] 后统一发布一次（把几十个 token 合成一帧）。 */
    private fun publishThrottled() {
        val wait: Long
        synchronized(lock) {
            val elapsed = System.currentTimeMillis() - lastPublishAtMs
            if (elapsed >= DELTA_THROTTLE_MS) {
                dirty = false
                lastPublishAtMs = System.currentTimeMillis()
                _state.value = HubState(convs.toList(), logs.toList(), runtime)
                return
            }
            dirty = true
            if (flushScheduled) return
            flushScheduled = true
            wait = DELTA_THROTTLE_MS - elapsed
        }
        flushJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                flushScheduled = false
                if (!dirty) return@launch
                dirty = false
                lastPublishAtMs = System.currentTimeMillis()
                _state.value = HubState(convs.toList(), logs.toList(), runtime)
            }
        }
    }
}
