package com.tg.dbisland

import androidx.compose.runtime.Immutable
import com.tg.dbisland.ui.HubConversation
import com.tg.dbisland.ui.HubMessage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 聊天记录落盘（CHANGELOG 第 41 条）。
 *
 * 现状（第 38 条 R9）：会话与消息只在 [com.tg.dbisland.ui.BridgeHub] 的内存里，
 * **App 进程被杀即丢** —— 用户报的就是「聊天页历史清空」。
 *
 * ## 文件布局（每会话一份，一行一条 JSON）
 * ```
 * filesDir/chat/<cid>.jsonl
 * {"mid":"…","role":"bot|user","text":"…","think":"…","ended":true,"at":1759…}
 * ```
 * · `cid` 只用来选文件名，**内容里的 cid 不上盘**（正文里没有任何额外东西）；
 * · 一行一条 JSON（`JSONObject.quote` 转义 `"` `\` 控制符与换行），
 *   所以「正文里塞一个换行 + 一行假 JSON」这种注入不会破坏后面的记录；
 * · 不加 BOM、不写压缩，`adb pull` 出来就是可读的文本。
 *
 * ## 上限与裁剪策略（用户要求：全局 ≤ 100 MB，超出删最旧的）
 * · 单会话文件先按**行数**裁（[MAX_LINES_PER_CONV]，只留最新的），
 *   这是「同一会话内部太胖」的情况；
 * · 全局总量超过 [CHAT_LIMIT]（100 MB）时，**按最旧会话整份删**
 *   （排序键 = 文件最后修改时间，也就是那条会话最后一次有内容的时间），
 *   删到低于上限为止，并写一行说明性日志（`已按上限裁掉 N 条记录`）——
 *   宁可用「整份会话」这个粒度，也不把某条会话截成半截：用户在聊天页里
 *   看到一条只有后半段的对话，比看不到它更让人困惑。
 *
 * ## 线程
 * · 读（[loadFor]）只在 [BridgeHub] 初始化时调一次，调用线程就是加载线程；
 * · 写（[mirror]）在事件线程上做**内存索引**，真正的写文件排到单线程 [io]；
 * · 任何 IO 异常都只降级（置 [degraded]），绝不影响岛与界面的功能。
 */
object ChatStore {

    /** 全局上限：100 MB（用户要求）。 */
    const val CHAT_LIMIT = 100L * 1024 * 1024

    /** 单会话最多保留的行数（旧行在下次写入时被裁掉）。 */
    const val MAX_LINES_PER_CONV = 400

    /** 启动时最多恢复的会话数（与 BridgeHub.MAX_CONVERSATIONS 同口径）。 */
    const val MAX_CONVS = 40

    /** 启动时最多读入的字节数 —— 避免 100 MB 全量读进内存。 */
    private const val LOAD_BUDGET_BYTES = 6 * 1024 * 1024

    /** 单条正文上限（正文本身上限见 IslandBridge.BODY_MAX；这里是最后一道闸）。 */
    private const val TEXT_MAX = 8192

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-store").apply { isDaemon = true }
    }

    @Volatile private var dir: File? = null
    @Volatile private var degraded = false
    @Volatile private var loggedFailure = false

    /** 内存索引：key = "cid\tmid" → 该消息在文件里的第几行（0 基）。 */
    private val index = HashMap<String, Int>()
    /** 每个会话文件当前行数。 */
    private val linesOf = HashMap<String, Int>()
    private val lock = Any()

    fun init(ctx: File) {
        dir = ctx
        runCatching { if (!ctx.exists()) ctx.mkdirs() }
    }

    /** 磁盘上的现状（给启动日志与设置页看）：文件数 / 字节数。 */
    fun stats(): String {
        val d = dir ?: return "未初始化"
        val fs = d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray()
        val total = runCatching { fs.sumOf { it.length() } }.getOrDefault(0L)
        return "${fs.size} 个会话文件 / ${total}B（$d）"
    }

    fun isDegraded(): Boolean = degraded
    fun dirName(): String = "files/chat"

    private fun fileOf(cid: String): File? {
        val d = dir ?: return null
        val safe = cid.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").ifBlank { "_" }
        return File(d, "$safe.jsonl")
    }

    // ------------------------------------------------------------------ 读

    /**
     * 启动时从磁盘恢复：返回**新→旧**排序的会话（UI 会自己排）。
     *
     * 只读最近 [MAX_CONVS] 份文件、每份最多 [MAX_LINES_PER_CONV] 行、
     * 总量不超过 [LOAD_BUDGET_BYTES]，所以即使磁盘用满 100 MB，
     * 启动也只读几 MB。
     */
    fun loadFor(): List<HubConversation> {
        val d = dir ?: return emptyList()
        val files = runCatching {
            d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }?.take(MAX_CONVS)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<HubConversation>()
        var budget = LOAD_BUDGET_BYTES.toLong()
        for (f in files) {
            if (budget <= 0) break
            val len = runCatching { f.length() }.getOrDefault(0L)
            budget -= len
            val conv = runCatching { readConv(f) }.getOrNull() ?: continue
            if (conv.cid.isBlank()) continue
            // 让「先到者占主岛」这个顺序在重启后仍然按最后一次活动时间排
            out.add(conv.copy(lastAtMs = runCatching { f.lastModified() }
                .getOrDefault(System.currentTimeMillis())))
        }
        // 恢复顺序 = 最后活动时间（BridgeHub 按到达顺序摆放，先到的在前）
        return out.sortedBy { it.lastAtMs }
    }

    private fun readConv(f: File): HubConversation? {
        val cid = f.name.removeSuffix(".jsonl")
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val all = text.split('\n').filter { it.isNotBlank() }
        val lines = if (all.size > MAX_LINES_PER_CONV)
            all.subList(all.size - MAX_LINES_PER_CONV, all.size) else all
        val msgs = ArrayList<HubMessage>(lines.size)
        var n = 0
        for (line in lines) {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val mid = o.optString("mid").ifBlank { "r$n" }
            val role = o.optString("role", "bot")
            val body = o.optString("text")
            val think = o.optString("think")
            val at = o.optLong("at", 0L)
            // 用户自己发出去的那句也要显示（role=user），但**不参与**「回复正文」
            // 的语义 —— 聊天页把它当一条普通消息画出来即可。
            msgs.add(HubMessage(
                mid = mid,
                text = body,
                think = think,
                ended = o.optBoolean("ended", true),
                role = role,
                atMs = if (at > 0) at else f.lastModified(),
            ))
            n++
        }
        if (msgs.isEmpty()) return null
        // 索引（下次追加/替换要知道每条在第几行）
        synchronized(lock) {
            for ((i, m) in msgs.withIndex()) index["$cid\t${m.mid}"] = i
            linesOf[cid] = msgs.size
        }
        // 会话名**不在磁盘上**（文件名只有 cid，正文里也没有名字）——留空即可，
        // UI 会退回「豆包 <尾6位>」；下一次 chat.conv / chat.start 到达时自然补上。
        return HubConversation(
            cid = cid, name = "", messages = msgs,
            streaming = false, lastAtMs = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把一条消息镜像到磁盘。
     *
     * @param cid   会话号（决定文件名）
     * @param mid   消息号（决定行；同 mid 的增量**替换**同一行）
     * @param full  整条消息的当前全文（累积后的），不是增量
     */
    fun mirror(cid: String, mid: String, full: String, think: String,
               ended: Boolean, role: String = "bot") {
        if (cid.isBlank() || cid == "-") return
        if (degraded) return
        val f = fileOf(cid) ?: return
        val text = full.take(TEXT_MAX)
        val key = "$cid\t$mid"
        // 行内容在锁外拼好（快），下标在锁内取
        val obj = JSONObject()
        runCatching {
            obj.put("mid", mid)
            obj.put("role", role)
            obj.put("text", text)
            if (think.isNotBlank()) obj.put("think", think.take(512))
            obj.put("ended", ended)
            obj.put("at", System.currentTimeMillis())
        }
        val line = obj.toString()
        val idx: Int
        synchronized(lock) {
            idx = index[key] ?: -1
            if (idx < 0) {
                index[key] = linesOf[cid] ?: 0
                linesOf[cid] = (linesOf[cid] ?: 0) + 1
            }
        }
        runCatching { io.execute { put(f, idx, line) } }
    }

    /** 把 [line] 写到 [f] 的第 [idx] 行（不存在则追加），必要时裁行/裁总量。 */
    private fun put(f: File, idx: Int, line: String) {
        try {
            val old = if (f.exists()) f.readLines().toMutableList() else ArrayList()
            if (idx in old.indices) old[idx] = line else old.add(line)
            var trim = 0
            if (old.size > MAX_LINES_PER_CONV) {
                trim = old.size - MAX_LINES_PER_CONV
                repeat(trim) { old.removeAt(0) }
            }
            f.writeText(old.joinToString("\n", postfix = "\n"))
            synchronized(lock) {
                if (trim > 0) {
                    // 行号整体前移（前面被裁掉 trim 行）：同一会话里越界的键直接删掉
                    val cid = f.name.removeSuffix(".jsonl")
                    val prefix = "$cid\t"
                    val gone = ArrayList<String>()
                    val moved = HashMap<String, Int>()
                    for (e in index) {
                        if (!e.key.startsWith(prefix)) continue
                        val v = e.value - trim
                        if (v < 0) gone.add(e.key) else moved[e.key] = v
                    }
                    for ((k, v) in moved) index[k] = v
                    gone.forEach { index.remove(it) }
                }
                linesOf[f.name.removeSuffix(".jsonl")] = old.size
            }
        } catch (t: Throwable) {
            degrade(t)
            return
        }
        runCatching { trimTotal(f) }
    }

    /** 全局上限：删最旧的会话整份，直到低于上限。 */
    private fun trimTotal(justWrote: File) {
        val d = justWrote.parentFile ?: return
        val limit = LogStore.limits(d).second
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        var dropped = 0
        var droppedBytes = 0L
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            val len = f.length()
            val cid = f.name.removeSuffix(".jsonl")
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                droppedBytes += len
                synchronized(lock) {
                    val it = index.entries.iterator()
                    var n = 0
                    while (it.hasNext()) {
                        if (it.next().key.startsWith("$cid\t")) { it.remove(); n++ }
                    }
                    dropped += n
                    linesOf.remove(cid)
                }
            }
        }
        if (dropped > 0) {
            // 说明性日志（用户要求）：写进新片，同时进 logcat
            val msg = "聊天记录已按上限裁掉最旧的 $dropped 条（${droppedBytes / 1024} KB，" +
                "当前 ${hSize(total)} / 上限 ${hSize(limit)}）"
            (BridgeApp.instance)?.logLine(msg)
        }
    }

    /** 人类可读的大小（上限被调小时 MB 会显示成 0.0，用 KB 更清楚）。 */
    private fun hSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format(java.util.Locale.US, "%.1f MB",
            bytes.toDouble() / 1024 / 1024)
        else "${bytes / 1024} KB"

    // ------------------------------------------------------------------ 运维

    fun usedBytes(): Long =
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sumOf { it.length() } ?: 0L

    fun limitBytes(): Long = dir?.let { LogStore.limits(it).second } ?: CHAT_LIMIT

    fun fileCount(): Int =
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.size ?: 0

    /** 清空全部聊天记录（设置页按钮，用户明确要求）。 */
    fun clearAll(): Int {
        val d = dir ?: return 0
        var n = 0
        runCatching {
            d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.forEach {
                if (it.delete()) n++
            }
        }
        synchronized(lock) {
            index.clear()
            linesOf.clear()
        }
        return n
    }

    /** 写一行「用户动作」记录（用户在 App 聊天页发的回复）——让磁盘上的记录
     *  与界面上看到的对话一致（第 41 条：上行的那条也算一条消息）。 */
    fun mirrorUser(cid: String, text: String, at: Long = System.currentTimeMillis()) {
        if (cid.isBlank() || cid == "-") return
        val f = fileOf(cid) ?: return
        val obj = JSONObject()
        runCatching {
            obj.put("mid", "u$at")
            obj.put("role", "user")
            obj.put("text", text.take(TEXT_MAX))
            obj.put("ended", true)
            obj.put("at", at)
        }
        val idx: Int
        synchronized(lock) {
            idx = linesOf[cid] ?: 0
            index["$cid\tu$at"] = idx
            linesOf[cid] = idx + 1
        }
        runCatching { io.execute { put(f, idx, obj.toString()) } }
    }

    private fun degrade(t: Throwable) {
        degraded = true
        if (!loggedFailure) {
            loggedFailure = true
            android.util.Log.w("IslandBridge",
                "聊天记录落盘失败，降级为只在内存: ${t.javaClass.simpleName} ${t.message}")
        }
    }
}
