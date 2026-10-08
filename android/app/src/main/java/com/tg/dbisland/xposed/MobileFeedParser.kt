package com.tg.dbisland.xposed

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Parses Doubao *Android* SSE chunks (captured inside com.larus.nova via
 *  OmniHttpCallByNative.writeChunkData) into the same normalized events the
 *  PC bridge emits: chat.start/delta/end/reply, plan.start/progress/end.
 *
 *  Field shapes per D:\aiwork\apk\REVERSE_NOTES:
 *    /text_block/text, /thinking_block/summary, /patch_value/content,
 *    /msg_finish_attr/brief, complex_task_block{thread_id,title,status},
 *    thread_info via /im/thread/info, /im/chain/thread_message.
 *
 *  The walker is deliberately defensive: it recursively scans every JSON
 *  node and reacts to known keys, so unknown envelope nesting is tolerated.
 */
class MobileFeedParser(private val emit: (JSONObject) -> Unit) {

    private var buf = StringBuilder()
    private var started = false
    private var mid = ""
    /** 本轮回答**自己的**消息号：在 [startChat] 那一刻把 [mid] 钉下来，之后整轮
     *  的 chat.start/delta/end/reply 都用它。
     *
     *  为什么必须钉（第 47 条，真机事故）：[mid] 是一个共享可变字段，而
     *  [react] 会在**任何**嵌套节点上看到 message_id/msg_id/mid/server_message_id
     *  就改它（`walk` 的遍历顺序还不保证）。用户「中途再发一条」时，同一条 SSE
     *  流里会夹带那条**用户消息**的回声帧 —— `mid` 于是漂到用户消息号上：
     *  ① 同一条回答的增量被拆到好几个 mid（App 里变成好几条消息，前半截永远停在
     *  头部、`ended` 还是 false）；
     *  ② 更致命的是它漂进 [userMids] 之后 `isUser` 判真，**后面所有增量被静默
     *  丢掉** —— 这就是真机报的「第二次的内容只显示头部的一些输出然后不会流式
     *  显示下文」。钉住之后，一条回答的增量永远只挂在一个 mid 上，也不会因为
     *  别的消息的 id 路过就被当成用户回声丢掉。 */
    private var curMid = ""
    /** 上一轮用过的消息号 + 回合序号：同一个消息号**不跨轮复用**（见 [startChat]）。 */
    private var lastTurnMid = ""
    private var turnSeq = 0
    private var cid = ""                          // current conversation_id
    private val convNames = HashMap<String, String>()  // cid -> title
    private var text = StringBuilder()
    private var lastThink = ""
    private val threads = LinkedHashMap<String, JSONObject>()
    private var planStarted = false

    /** 等真实消息号期间攒下的增量（见 [emitGrown]），以及等待上界。 */
    private val pendingDeltas = ArrayList<Pair<String, String>>()
    private var pendingSince = 0L
    private val midWaitHandler = Handler(Looper.getMainLooper())
    private var midWaitPosted = false
    private val MID_WAIT_MS = 250L

    fun feedBytes(b: ByteArray) = feed(String(b, Charsets.UTF_8))

    // ---------- OmniMessage path ----------
    // Doubao Android delivers streaming replies through
    // OmniMessageDispatcher.onStreamingMessage(String, OmniMessage, ...)
    // rather than HTTP writeChunkData. OmniMessage.content is a cumulative
    // JSON array of content blocks ([{block_id, block_type, content:{...}}]),
    // the same schema the desktop SSE carries. We diff per block_id so
    // re-feeding a grown snapshot only emits the new tail.
    private val blockText = HashMap<String, String>()   // block_id -> emitted text
    private val finishHandler = Handler(Looper.getMainLooper())
    @Volatile private var finishGeneration = 0L
    private var finishRunnable: Runnable? = null

    private val QUIET_END_MS = 4_000L
    private var quietRunnable: Runnable? = null

    private fun armQuietEnd() {
        quietRunnable?.let { finishHandler.removeCallbacks(it) }
        val generation = finishGeneration
        val r = Runnable {
            if (generation != finishGeneration || !started) return@Runnable
            quietRunnable = null
            endChat()
        }
        quietRunnable = r
        finishHandler.postDelayed(r, QUIET_END_MS)
    }

    fun feedOmniMessage(mid0: String, cid0: String,
                        brief: String, contentJson: String) {
        finishRunnable?.let { finishHandler.removeCallbacks(it) }
        finishRunnable = null
        finishGeneration++
        armQuietEnd()
        if (cid0.isNotEmpty()) cid = cid0
        try {
            val arr = JSONArray(contentJson)
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val bid = b.optString("block_id").ifEmpty { "#$i" }
                val inner = b.optJSONObject("content") ?: continue
                emitGrown(bid + ":t",
                    inner.optJSONObject("text_block")?.optString("text") ?: "",
                    "text")
                emitGrown(bid + ":k",
                    inner.optJSONObject("thinking_block")?.let {
                        it.optString("streaming_title").ifEmpty {
                            it.optString("summary") }.ifEmpty {
                            it.optString("title") } } ?: "", "think")
                inner.optJSONObject("complex_task_block")?.let { ctb ->
                    val tid = ctb.optString("thread_id")
                        .ifEmpty { ctb.optString("id") }
                    if (tid.isNotEmpty()) noteThread(tid, ctb)
                }
            }
        } catch (e: Exception) { }
        // brief is the accumulated plain text — backstop if blocks lack text
        if (brief.isNotEmpty() && text.isEmpty()) {
            val prev = blockText["#brief"] ?: ""
            if (brief != prev && brief.startsWith(prev)) {
                emitGrown("#brief", brief, "text")
            }
        }
    }

    private fun emitGrown(key: String, cur: String, kind: String) {
        if (cur.isEmpty()) return
        val prev = blockText[key] ?: ""
        if (cur == prev) return
        val delta = if (cur.startsWith(prev)) cur.substring(prev.length)
                    else cur                          // non-prefix: resend whole
        blockText[key] = cur
        if (delta.isEmpty()) return
        // 还没拿到真实消息号时**先攒着**，等 mid 到位再开轮。
        // 不攒的话会用占位号开轮，而 `finishOmni(mid0)` 到结束时才把真实号写进
        // `mid` —— 于是同一段正文分裂成两条会话记录（真实号那条 ended=false、
        // 占位号那条 ended=true），重建历史时前者就是"回答已结束却仍显示
        // 回答进行"。等待有上界（MID_WAIT_MS），不会为了等号把流式压住。
        if (mid.isEmpty()) {
            if (pendingDeltas.isEmpty())
                pendingSince = android.os.SystemClock.elapsedRealtime()
            pendingDeltas.add(kind to delta)
            if (!midWaitPosted) {
                midWaitPosted = true
                midWaitHandler.postDelayed({
                    midWaitPosted = false
                    if (!started) flushPendingDeltas()
                }, MID_WAIT_MS)
            }
            return
        }
        flushPendingDeltas()          // 真实号到了：先把攒下的一次性发出去
        startChat()
        if (kind == "text") text.append(delta)
        emit(conv(JSONObject().put("t", "chat.delta").put("mid", curMid)
            .put("kind", kind).put("text", delta)))
        armQuietEnd()
    }

    /** 把攒下的增量按原顺序发出去；只在还没有消息号时才用本轮唯一占位号。 */
    private fun flushPendingDeltas() {
        if (pendingDeltas.isEmpty()) return
        val batch = ArrayList(pendingDeltas)
        pendingDeltas.clear()
        if (mid.isEmpty()) mid = placeholderMid()
        startChat()
        for ((k, d) in batch) {
            if (k == "text") text.append(d)
            emit(conv(JSONObject().put("t", "chat.delta").put("mid", curMid)
                .put("kind", k).put("text", d)))
        }
        armQuietEnd()
    }

    /** 本轮唯一占位号。**不能是共享常量**（原来是字面量 "m0"）。 */
    private fun placeholderMid(): String = "pending#$turnSeq"

    /** 学到真实消息号时调用。若这一轮是**用占位号开的**，把整轮迁到真实号上，
     *  并先给占位号那一轮补一个 `chat.end` —— 否则它在会话里会留下一条
     *  `ended=false` 的半截记录，也就是用户看到的"已结束却仍显示回答进行"。 */
    private fun adoptMid(real: String) {
        if (real.isEmpty()) return
        val wasPlaceholder = started && curMid.startsWith("pending#")
        if (wasPlaceholder) {
            emit(conv(JSONObject().put("t", "chat.end").put("mid", curMid)))
            if (lastTurnMid.startsWith("pending#")) lastTurnMid = real
        }
        mid = real
        if (wasPlaceholder) curMid = real
        else if (started && curMid.isEmpty()) curMid = real
    }

    fun isStarted(): Boolean = started

    fun finishOmni(mid0: String = "") {
        if (mid0.isNotEmpty()) {
            adoptMid(mid0)
            flushPendingDeltas()      // 结束时真实号才到：别把攒下的尾巴丢了
        }
        val generation = ++finishGeneration
        finishRunnable?.let { finishHandler.removeCallbacks(it) }
        val r = Runnable {
            if (generation != finishGeneration) return@Runnable
            finishRunnable = null
            endChat()
        }
        finishRunnable = r
        finishHandler.postDelayed(r, 800L)
    }

    fun feed(s: String) {
        buf.append(s)
        // split SSE frames on blank line
        var i: Int
        while (true) {
            i = buf.indexOf("\n\n")
            if (i < 0) break
            val frame = buf.substring(0, i)
            buf.delete(0, i + 2)
            handleFrame(frame)
        }
        // a whole JSON body may arrive without SSE framing — try whole buffer
        tryJson(buf.toString().trim())?.let { buf.setLength(0) }
    }

    // ---------- frames ----------
    private fun handleFrame(frame: String) {
        val data = StringBuilder()
        var eventName = ""
        for (line in frame.split("\n")) {
            when {
                line.startsWith("data:") ->
                    data.append(line.removePrefix("data:").trimStart())
                line.startsWith("event:") ->
                    eventName = line.removePrefix("event:").trim()
            }
        }
        // The event name alone is not enough: some reply-end envelopes carry
        // intermediate metadata. Let the JSON walker validate end_type=1.
        // FULL_MSG_NOTIFY echoes the just-sent USER message back — desktop
        // ignores it entirely; without this its text_block leaks to the island
        if (eventName == "FULL_MSG_NOTIFY") return
        // CHUNK_DELTA frames are bare {"text": ...} reply increments —
        // no envelope, no text_block wrapper. Handle directly; a generic
        // walk would double-count the "text" key inside other blocks.
        if (eventName.contains("CHUNK_DELTA")) {
            val s0 = data.toString()
            try {
                val t = JSONObject(s0).optString("text")
                if (t.isNotEmpty() && !isUserEcho(mid)) {
                    startChat()
                    text.append(t)
                    emit(conv(JSONObject().put("t", "chat.delta").put("mid", curMid)
                        .put("kind", "text").put("text", t)))
                }
            } catch (e: Exception) { }
            return
        }
        val s = data.toString()
        if (s.isEmpty()) return
        if (s == "[DONE]") {
            finishOmni()
            return
        }
        tryJson(s)
    }

    private fun tryJson(s: String): JSONObject? {
        if (s.isEmpty()) return null
        return try {
            val o = JSONObject(s)
            walk(o)
            o
        } catch (e: Exception) {
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) walk(a.opt(i))
                JSONObject()
            } catch (e2: Exception) { null }
        }
    }

    // ---------- recursive walk ----------
    private fun walk(n: Any?) {
        when (n) {
            is JSONObject -> {
                react(n)
                val it = n.keys()
                while (it.hasNext()) walk(n.opt(it.next()))
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
        }
    }

    // ids of messages sent BY the user (meta.user_type==1) — the SSE feed
    // echoes them back (FULL_MSG_NOTIFY) and we must not show them as replies
    private val userMids = HashSet<String>()

    private fun react(o: JSONObject) {
        // message id hints
        for (k in arrayOf("message_id", "msg_id", "mid", "server_message_id")) {
            val v = o.optString(k)
            if (v.isNotEmpty()) adoptMid(v)
        }
        o.optJSONObject("meta")?.let { meta ->
            // claim the real message_id before children are walked, so the
            // first text_block isn't emitted under the previous reply's mid
            val mm = meta.optString("message_id")
            if (mm.isNotEmpty()) adoptMid(mm)
            if (meta.optInt("user_type") == 1 && mm.isNotEmpty())
                userMids.add(mm)
            meta.optString("local_conversation_id").let {
                if (it.length > 5 && it != "0" && cid.isEmpty()) cid = it
            }
            // conversation id: STREAM_MSG_NOTIFY.meta
            meta.optString("conversation_id").let {
                if (it.length > 5 && it != "0") cid = it
            }
        }
        val cv = o.optString("conversation_id")
        if (cv.length > 5 && cv != "0") cid = cv
        // title pairing: {conversation_id, name} — e.g. conversation_info
        val nm = o.optString("name").ifEmpty { o.optString("title") }
        if (cv.length > 5 && cv != "0" && nm.isNotEmpty()
                && convNames[cv] != nm) {
            convNames[cv] = nm
            emit(JSONObject().put("t", "chat.conv")
                .put("cid", cv).put("cname", nm))
        }
        // stream begin
        val s = o.toString()
        if (s.contains("STREAM_MSG_NOTIFY") || s.contains("reply_begin"))
            startChat()

        // text block -> lyric/chat text (skipped only for a *user* echo frame;
        // 已经开始的回答不再看 mid 脸色 —— 见 isUserEcho)
        o.optJSONObject("text_block")?.let { tb ->
            val t = tb.optString("text").ifEmpty { tb.optString("append") }
            if (t.isNotEmpty() && !isUserEcho(mid)) {
                startChat()
                text.append(t)
                emit(conv(JSONObject().put("t", "chat.delta").put("mid", curMid)
                    .put("kind", "text").put("text", t)))
            }
        }
        // thinking block -> lyric line
        o.optJSONObject("thinking_block")?.let { tb ->
            val t = tb.optString("streaming_title").ifEmpty {
                tb.optString("summary") }.ifEmpty { tb.optString("title") }
            if (t.isNotEmpty() && t != lastThink && !isUserEcho(mid)) {
                lastThink = t
                startChat()
                emit(conv(JSONObject().put("t", "chat.delta").put("mid", curMid)
                    .put("kind", "think").put("text", t)))
            }
        }
        // `msg_finish_attr` also appears on non-terminal metadata frames. The
        // observed Android/PC protocol uses end_type=1 for the final answer;
        // ending on mere field presence reopens the same turn and drops the
        // later stream into a new card state.
        val finish = o.optJSONObject("msg_finish_attr")?.optInt("end_type", -1) == 1 ||
            (s.contains("SSE_REPLY_END") && o.optInt("end_type", -1) == 1) ||
            o.optString("fin_reason").isNotBlank()
        if (finish) finishOmni()
        if (o.optInt("status") == 2 && s.contains("async_job")) finishOmni()

        // plan: complex_task_block / thread entries
        o.optJSONObject("complex_task_block")?.let { ctb ->
            val tid = ctb.optString("thread_id")
                .ifEmpty { ctb.optString("id") }
            if (tid.isNotEmpty()) noteThread(tid, ctb)
        }
        o.optJSONObject("thread_info")?.let { scanThreads(it) }
        // a bare object with thread_id + status counts as a thread too
        val tid = o.optString("thread_id")
        if (tid.isNotEmpty() && (o.has("status") || o.has("thread_status")))
            noteThread(tid, o)
    }

    private fun scanThreads(ti: JSONObject) {
        for (key in arrayOf("threads", "thread_list", "items")) {
            ti.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val id = t.optString("thread_id").ifEmpty {
                        t.optString("id") }
                    if (id.isNotEmpty()) noteThread(id, t)
                }
            }
        }
    }

    private fun noteThread(id: String, t: JSONObject) {
        threads[id] = t
        if (!planStarted) {
            planStarted = true
            emit(JSONObject().put("t", "plan.start").put("tid", id)
                .put("title", t.optString("title").ifEmpty {
                    t.optString("name") }))
        }
        val done = threads.values.count { isDone(it.optString("status")
            .ifEmpty { it.optString("thread_status") }) }
        val cur = t.optString("title").ifEmpty { t.optString("name") }
        emit(JSONObject().put("t", "plan.progress")
            .put("done", done).put("total", threads.size).put("name", cur))
        if (done == threads.size && threads.isNotEmpty()) {
            emit(JSONObject().put("t", "plan.end")
                .put("success", true).put("text", "全部子任务完成"))
            threads.clear(); planStarted = false
        }
    }

    /** 完成判定。`complex_task_block.status` 的实测语义（`capture/hook.jsonl`
     *  逐条 JSON 解析复核：4/「已开始工作」/organizer 282 条，
     *  2/「已完成工作」/supertask 60 条）：**只有 2 算完成**。
     *
     *  原实现把 3/4 也当完成 —— 计划刚铺开（status=4）就会被判「全部子任务
     *  完成」，发出假的 plan.end 并 `threads.clear()`，接着新线程又触发
     *  plan.start，卡片反复重开。已与 `pc/feed_parser.py:_thread_seen` 对齐。 */
    private fun isDone(s: String) = s in setOf(
        "completed", "complete", "done", "finished", "success", "2")

    // ---------- chat lifecycle ----------
    /** Attach conversation fields (cid + resolved title) to an event. */
    private fun conv(o: JSONObject): JSONObject {
        if (cid.isNotEmpty()) {
            o.put("cid", cid)
            convNames[cid]?.let { o.put("cname", it) }
        }
        return o
    }

    /** 这一帧的 mid 是不是「用户自己发的消息」的回声（要丢掉，别当回答）？
     *
     *  **只在还没有开始一轮回答时**才丢：这时丢的是豆包回显的用户消息，
     *  不能拿它当回答的开头。回答一旦开始就一律不再丢 —— 因为 [mid] 会漂，
     *  一条回答的帧完全可能带着**别的消息号**（包括用户消息号）过来，
     *  按消息号一刀切就会把这条回答剩下的增量**全部静默丢掉**
     *  （真机现象：「第二次的内容只显示头部的一些输出然后不会流式显示下文」）。
     *  能这么改的前提是 [startChat] 拒绝在用户消息号上开一轮（见那里）。 */
    private fun isUserEcho(frameMid: String): Boolean =
        !started && frameMid in userMids

    private fun startChat() {
        if (started) return
        // 用户消息的回声不能开一轮回答 —— 否则 curMid 会被钉在用户消息号上
        if (mid in userMids) return
        started = true
        // 见 curMid 的说明：整轮回答只用这一个消息号。
        // 再补一道：**同一个消息号不跨轮复用**（豆包中断/重发时会复用同一条
        // 消息号，回收站里那条旧回答就会被当成「同一条消息」继续写下去，
        // 表现就是新一轮回答只显示头部、旧那条越写越长）—— 复用时就另起一个
        // 回合号，保证「一轮回答 = 一个消息号」这个 App 侧的前提成立。
        val base = if (mid.isEmpty()) placeholderMid() else mid
        curMid = if (base == lastTurnMid) "$base#$turnSeq" else base
        turnSeq++
        emit(conv(JSONObject().put("t", "chat.start").put("mid", curMid)))
    }

    private fun endChat() {
        if (!started) return
        started = false
        emit(conv(JSONObject().put("t", "chat.end").put("mid", curMid)))
        if (text.isNotEmpty()) {
            emit(conv(JSONObject().put("t", "chat.reply").put("mid", curMid)
                .put("text", text.toString())))
            text.setLength(0)
        }
        lastThink = ""
        // 记住这一轮用过哪个消息号，下一轮若复用就换一个（见 startChat）
        lastTurnMid = curMid
    }
}
