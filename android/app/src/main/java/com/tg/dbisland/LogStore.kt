package com.tg.dbisland

import android.content.Context
import com.tg.dbisland.ui.HubLogLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 日志落盘（CHANGELOG 第 41 条）。
 *
 * 现状（第 38 条之前的遗留）：[BridgeApp.logLine] 只往 logcat 与
 * [com.tg.dbisland.ui.BridgeHub] 的内存环形缓冲写 —— App 进程一被杀，
 * 出了问题就只能靠用户「再复现一次 + 当场连着电脑看 logcat」。
 * 这里把同一份日志**同时**落到 App 私有目录：
 *
 * ```
 * filesDir/logs/2026-10-05_140233.log      ← 一片，UTF-8 文本，一行一条
 * ```
 *
 * ## 分片与上限（用户要求：总占用 ≤ 100 MB，超出删最旧的）
 * · 每片写到 [SHARD_LIMIT] 就换新片（文件名带时间，字典序即写入序）；
 *   每写一条顺带检查一次，超了就换片 + 裁总量，所以「总量」的上界是
 *   `上限 + 一片`（≤ 106 MB），不会无界增长。
 * · 总量超过 [LOG_LIMIT] 时**从最旧的片开始整片删**，删到低于上限为止，
 *   并往新片里留一行说明（「已按上限裁掉 N 片 / M 字节」）。
 *
 * ## 线程与降级（用户要求：后台线程写、IO 异常不影响功能）
 * · 写盘全在单线程 [io] 上排队，主线程只做一次 submit；队列满也不阻塞
 *   （DiscardPolicy），最多丢日志，绝不拖慢 UI 或岛回调。
 * · 任何 IOException / SecurityException 都只把 [degraded] 置真并往 logcat
 *   记**一次**，之后静默降级为「只打 logcat」，功能照常。
 *
 * ## 为什么不是「模块也一起固化」
 * LSPosed 模块跑在**豆包进程**里（`com.larus.nova`），它没有本 App 的
 * filesDir 写入上下文，写别的应用私有目录会被 SELinux 拦；模块日志**仍然
 * 只在 logcat**。本类固化的是 App 侧（岛桥自己）的日志 —— 也就是岛上卡片、
 * 上行动作、会话/回复这些「用户看得见」的动作。模块日志要看请用
 * `adb logcat -s IslandBridge`（模块 tag 同名，见 README）。
 */
object LogStore {

    /** 总量上限：100 MB（用户要求）。 */
    const val LOG_LIMIT = 100L * 1024 * 1024

    /** 单片上限：6 MB（落在用户建议的 4~8 MB 区间内）。 */
    const val SHARD_LIMIT = 6L * 1024 * 1024

    /** 上限可临时调小用于验证（见 [limits]）。 */
    private const val LIMIT_FILE = "ib_limits.txt"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-store").apply { isDaemon = true }
    }

    @Volatile private var dir: File? = null
    /** 应用上下文：把同一份日志**镜像**到公共「文档」目录（用户要求，
     *  见 [DocStore] 与 [mirror]）。null = 只有私有目录这一份。 */
    @Volatile private var appCtx: Context? = null
    @Volatile private var degraded = false
    @Volatile private var loggedFailure = false
    /** 日志出口（BridgeApp.logLine）——用于「已按上限裁掉…」这类说明性记录。 */
    @Volatile private var sink: ((String) -> Unit)? = null

    /**
     * 分片名的时间格式：`yyyy-MM-dd_HH_mm_ss` + `_SSS.log`。
     *
     * **第 46 条：这里原来写的是 `HH:mm:ss`（带冒号），是「重复镜像」的根因之一。**
     * 冒号是 FAT/exFAT 的非法字符，MediaStore 落盘时会把它换成 `_`，于是库里存的
     * `DISPLAY_NAME` 和我们拿去查的名字不一样 —— 精确匹配永远失败，每次都新建
     * 一条记录，MediaProvider 再自动编号成 `(1) (2) …`。私有分片自己也带着冒号
     * （在 `/data` 上无害，但它是公共目录那一份的来源），所以从源头去掉。
     */
    private val fmt = SimpleDateFormat("yyyy-MM-dd_HH_mm_ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 见 [attach]：init 阶段（后台线程）发出的说明先攒在这里，attach 时补投。 */
    private val pending = ArrayList<String>()

    /** 初始化（BridgeApp.onCreate 第一件事，**早于** logLine 打第一行，
     *  否则「启动」那几行会丢）。 */
    fun init(ctx: Context) {
        if (dir != null) return
        appCtx = ctx.applicationContext
        val d = File(ctx.filesDir, "logs")
        dir = d
        io.execute {
            runCatching { if (!d.exists()) d.mkdirs() }
            runCatching { trim(d) }
            // 第 46 条：清掉历史遗留的 `(1) (2)…` 编号副本，并把当前分片
            // 完整覆盖写回公共目录那一份，保证「文档里的那份 == 私有分片」。
            repairDocMirror()
        }
    }

    /**
     * 第 46 条自检（后台线程，[init] 里跑一次）。
     *
     * 1. [DocStore.dedupe]：同一份镜像被 MediaStore 编了号就删到只剩一份；
     * 2. 把**当前分片**的完整字节**覆盖写**回公共目录那一份 —— 编号副本里各存了
     *    一段碎片，删掉它们之后必须把完整内容补上，否则用户会以为日志丢了。
     *    覆盖写是幂等的：私有分片是唯一真相，文档那份就是它的镜像。
     */
    private fun repairDocMirror() {
        val c = appCtx ?: return
        val d = dir ?: return
        runCatching {
            val (dropped, kept) = DocStore.dedupe(c, DOC_SUB)
            if (dropped > 0) {
                note("清理了 $dropped 个重复镜像（Documents/${DocStore.ROOT}/$DOC_SUB/ " +
                    "只保留 1 份：${kept ?: "?"}；编号副本已被同名覆盖取代）")
            }
            val f = currentShard(d)
            if (!f.isFile || f.length() <= 0L) return@runCatching
            val bytes = f.readBytes()
            val uri = DocStore.write(c, DOC_SUB, f.name, bytes)
            if (uri == null) {
                mirrorFailOnce(IllegalStateException("DocStore.write 返回 null"))
            } else {
                note("文档镜像已对齐：${DocStore.displayPath(c, DOC_SUB, f.name)}" +
                    "（${hSize(bytes.size.toLong())}，与私有分片 ${f.name} 内容相同；" +
                    "同名覆盖，不再生成 (1)(2) 编号副本）")
            }
        }.onFailure { mirrorFailOnce(it) }
    }

    /** 公共「文档」目录下的日志子目录名。 */
    private const val DOC_SUB = "日志"

    /** 镜像用的缓冲：攒够 [MIRROR_FLUSH_BYTES] 或距上次 [MIRROR_FLUSH_MS] 才落一次，
     *  避免一行一次 binder 调用（MediaStore 是跨进程的）。只在 [io] 线程上读写。 */
    private val mirrorBuf = java.io.ByteArrayOutputStream()
    private var mirrorShard: String? = null
    private var mirrorAtMs = 0L
    private const val MIRROR_FLUSH_MS = 3000L
    private const val MIRROR_FLUSH_BYTES = 128 * 1024

    /**
     * 把刚写进私有分片的那几字节**追加**到公共文档目录里的同名文件
     * （`/sdcard/Documents/豆包岛桥/日志/<同一片名>`）。
     *
     * 为什么镜像而不是直接写公共目录：公共目录必须走 MediaStore（见 [DocStore]），
     * 那是跨进程调用，一行一次太贵；私有目录的 File 追加才是热的写路径。
     * 镜像按「3 秒或 128 KB」合批，用户看到的文档目录里的日志最多滞后几秒。
     * 失败只记一次，不影响私有那份（也绝不因此降级整个 LogStore）。
     */
    private fun mirror(shard: File, line: ByteArray) {
        val ctx = appCtx ?: return
        runCatching {
            if (mirrorShard != shard.name) {
                flushMirror()
                mirrorShard = shard.name
            }
            mirrorBuf.write(line)
            val now = android.os.SystemClock.elapsedRealtime()
            if (mirrorBuf.size() >= MIRROR_FLUSH_BYTES || now - mirrorAtMs >= MIRROR_FLUSH_MS) {
                flushMirror(ctx)
            }
        }.onFailure { mirrorFailOnce(it) }
    }

    private fun flushMirror(ctx: Context? = appCtx) {
        val c = ctx ?: return
        val name = mirrorShard ?: return
        if (mirrorBuf.size() == 0) return
        val bytes = mirrorBuf.toByteArray()
        mirrorBuf.reset()
        mirrorAtMs = android.os.SystemClock.elapsedRealtime()
        val uri = DocStore.append(c, DOC_SUB, name, bytes)
        if (uri == null) {
            mirrorFailOnce(IllegalStateException("DocStore.append 返回 null"))
        } else if (!mirrorOkLogged) {
            mirrorOkLogged = true
            note("日志镜像已开启：${DocStore.displayPath(c, DOC_SUB, name)}" +
                "（私有热路径仍是 filesDir/logs，文档目录最多滞后 3 秒）")
        }
    }

    @Volatile private var mirrorOkLogged = false
    @Volatile private var mirrorFailLogged = false

    private fun mirrorFailOnce(t: Throwable) {
        if (mirrorFailLogged) return
        mirrorFailLogged = true
        note("日志镜像到文档目录失败（私有日志不受影响）：" +
            "${t.javaClass.simpleName} ${t.message}")
    }

    /** 「日志」页的「导出 TXT」：把**当前所有私有分片**拼成一份文本写进
     *  `Documents/豆包岛桥/导出/豆包岛桥日志_<时间>.txt`，返回给用户看的路径。 */
    fun exportToDocuments(): String? {
        val c = appCtx ?: return null
        val d = dir ?: return null
        val files = d.listFiles { x -> x.isFile && x.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: return null
        val head = buildString {
            append("# 豆包岛桥 日志导出\n")
            append("# 导出时间: ${fmt.format(Date())}\n")
            append("# 分片: ${files.size} 个 · 共 ${hSize(size(d))}\n")
            append("# 格式: 时间戳(ms)\\t级别\\t正文\n\n")
        }
        val name = "豆包岛桥日志_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date()) + ".txt"
        var bytes = head.toByteArray()
        for (f in files) {
            bytes += runCatching { f.readBytes() }.getOrDefault(ByteArray(0))
        }
        val uri = DocStore.write(c, "导出", name, bytes) ?: return null
        val path = DocStore.displayPath(c, "导出", name)
        note("日志已导出：$path（${hSize(bytes.size.toLong())}，$uri）")
        return path
    }

    /** 后台线程里调用（见 [init]）。 */
    fun attach(log: (String) -> Unit) {
        sink = log
        // init 的重复镜像清理/对齐跑在 [io] 线程上，可能**早于** attach 就写好了
        // 说明（第 46 条）—— 先攒着，attach 一到立刻补投，否则那几行会丢。
        val buffered = synchronized(pending) {
            val c = pending.toList()
            pending.clear()
            c
        }
        for (m in buffered) log(m)
    }

    /**
     * 唯一的「说明性记录」出口：有出口就直接发，没有就先攒着（见 [attach]）。
     * 注意它和 [append] 不同 —— [append] 写的是**日志条目**，这里是给用户看的
     * 一次性说明（清理了几份、镜像路径在哪）。
     */
    private fun note(msg: String) {
        val s = sink
        if (s != null) s(msg) else synchronized(pending) { pending.add(msg) }
    }

    /** 是否已经降级（只打 logcat）——「日志」页可以如实显示。 */
    fun isDegraded(): Boolean = degraded

    /** 目录不可用（尚未 init）时返回 null，调用方按「没固化」处理。 */
    private fun dir(): File? = dir

    /**
     * 追加一条。**非阻塞**：只做字符串拼装 + 一次 submit。
     * 格式与 logcat 行不同：`时间\t级别\t正文`（制表符分隔，正文里的制表符
     * 会被替换，避免解析歧义；正文里的换行也一并压平）。
     */
    fun append(level: HubLogLevel, text: String) {
        val d = dir ?: return
        if (degraded) return
        val ts = System.currentTimeMillis()
        val line = buildString {
            append(ts)
            append('\t')
            append(level.name)
            append('\t')
            append(text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '))
            append('\n')
        }
        runCatching {
            io.execute { write(d, ts, line) }
        }
    }

    private fun write(d: File, ts: Long, line: String) {
        val bytes = line.toByteArray()
        try {
            if (!d.exists()) d.mkdirs()
            var f = currentShard(d)
            if (f.length() >= SHARD_LIMIT) f = newShard(d, ts)
            java.io.FileOutputStream(f, true).use { it.write(bytes) }
            // 用户要求：日志默认存在公共「文档」文件夹 → 同一份镜像过去
            mirror(f, bytes)
        } catch (t: Throwable) {
            degrade(t)
            return
        }
        // 上限检查（每片一次换片检查已经做过，这里只在片变大后看一眼总量）
        runCatching {
            val total = size(d)
            if (total > limits(d).first) trim(d)
        }
    }

    /** 当前片：目录里字典序最大的 `.log`。 */
    private fun currentShard(d: File): File {
        val f = d.listFiles { x -> x.isFile && x.name.endsWith(".log") }
            ?.maxByOrNull { it.name }
        return if (f == null) newShard(d, System.currentTimeMillis()) else f
    }

    private fun newShard(d: File, ts: Long): File {
        val name = fmt.format(Date(ts)) + "_" +
            String.format(Locale.US, "%03d", (ts % 1000).toInt()) + ".log"
        val f = File(d, name)
        runCatching { if (!f.exists()) f.createNewFile() }
        return f
    }

    /** 从最旧的片开始整片删，直到总量 ≤ 上限。返回删掉的字节目数。 */
    private fun trim(d: File): Pair<Long, Int> {
        val (limit, _) = limits(d)
        val files = d.listFiles { x -> x.isFile && x.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: return 0L to 0
        var total = files.sumOf { it.length() }
        if (total <= limit) return 0L to 0
        var dropped = 0L
        var n = 0
        for (f in files) {
            if (total <= limit) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                dropped += len
                n++
            }
        }
        if (n > 0) {
            note(
                "日志已按上限裁掉最旧的 $n 片（${dropped / 1024} KB），" +
                    "当前 ${hSize(total)} / 上限 ${hSize(limit)}"
            )
            // 文档目录里那份镜像也按同一上限收（用户要求日志放文档目录）
            appCtx?.let { runCatching { DocStore.trim(it, DOC_SUB, limit) } }
        }
        return dropped to n
    }

    /** 人类可读的大小（上限被调小时 MB 会显示成 0.0，用 KB 更清楚）。 */
    private fun hSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB",
            bytes.toDouble() / 1024 / 1024)
        else "${bytes / 1024} KB"

    /** 目录总占用（字节）。 */
    fun usedBytes(): Long = dir?.let { size(it) } ?: 0L

    /** 当前总量上限（字节）——正常就是 [LOG_LIMIT]。 */
    fun limitBytes(): Long = dir?.let { limits(it).first } ?: LOG_LIMIT

    fun shardCount(): Int =
        dir?.listFiles { x -> x.isFile && x.name.endsWith(".log") }?.size ?: 0

    private fun size(d: File): Long =
        d.listFiles { x -> x.isFile && x.name.endsWith(".log") }?.sumOf { it.length() } ?: 0L

    /**
     * 「日志」页的数据源：**从最新的片往最旧的片读**，最多读 [limit] 行。
     * 只读、不缓存（一页几百条，读几十 KB 很快，且都在 Dispatchers.IO 上）。
     * 返回**新→旧**顺序。
     */
    fun readNewest(limit: Int = 800): List<Triple<Long, HubLogLevel, String>> {
        val d = dir ?: return emptyList()
        val out = ArrayList<Triple<Long, HubLogLevel, String>>(limit)
        val files = d.listFiles { x -> x.isFile && x.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: return emptyList()
        for (f in files) {
            if (out.size >= limit) break
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            for (i in lines.indices.reversed()) {
                if (out.size >= limit) break
                parse(lines[i])?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parse(line: String): Triple<Long, HubLogLevel, String>? {
        if (line.isBlank()) return null
        val t1 = line.indexOf('\t')
        if (t1 <= 0) return null
        val t2 = line.indexOf('\t', t1 + 1)
        if (t2 <= t1) return null
        val ts = line.substring(0, t1).toLongOrNull() ?: return null
        val lv = when (line.substring(t1 + 1, t2)) {
            "WARN" -> HubLogLevel.WARN
            "ERROR" -> HubLogLevel.ERROR
            else -> HubLogLevel.INFO
        }
        return Triple(ts, lv, line.substring(t2 + 1))
    }

    /** 清空全部日志（设置页按钮）。删完后立刻写一行说明，便于一眼看出「刚清过」。
     *  文档目录里的镜像**一起清掉**（否则用户会看到「清空了但文件还在」）。 */
    fun clearAll() {
        val d = dir ?: return
        io.execute {
            runCatching {
                d.listFiles { x -> x.isFile && x.name.endsWith(".log") }?.forEach { it.delete() }
            }
            appCtx?.let { runCatching { DocStore.trim(it, DOC_SUB, 0L) } }
            runCatching {
                mirrorBuf.reset()
                mirrorShard = null
            }
        }
        append(HubLogLevel.INFO, "日志已被用户清空（filesDir/logs + Documents/豆包岛桥/日志 全部删除）")
    }

    /** 目录名（给界面显示用）：**用户能在文件管理器里找到的那个目录**。 */
    fun dirName(): String = "Documents/${DocStore.ROOT}/$DOC_SUB/"

    /** 导出目录名（设置页「固化与清理」显示用，与 [exportToDocuments] 一致）。 */
    fun exportDirName(): String = "Documents/${DocStore.ROOT}/导出/"

    /**
     * 公共文档目录「日志」里现在有几个文件（第 46 条）。
     *
     * 用途：设置页把这一行显示出来，正好当**重复镜像有没有被清干净**的证据 ——
     * 修好之后这里应该稳定在 1（同一次运行只会有一个分片在被镜像），
     * 不再是 7 个 `(1)…(6)`。未初始化 / 取不到返回 -1。
     * 走 MediaStore（binder 调用），**必须在 IO 线程上调**。
     */
    fun docMirrorCount(): Int = appCtx?.let { DocStore.count(it, DOC_SUB) } ?: -1

    /** 目录路径（启动日志/界面用；未初始化返回 null）。用户要求日志放公共文档目录，
     *  所以这里显示 /sdcard/Documents/… ，私有热路径仍在 [dir]。 */
    fun dirPath(): String? =
        appCtx?.let { DocStore.displayDir(it, DOC_SUB) } ?: dir?.absolutePath

    private fun degrade(t: Throwable) {
        degraded = true
        if (!loggedFailure) {
            loggedFailure = true
            android.util.Log.w("IslandBridge",
                "日志落盘失败，降级为只打 logcat: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /**
     * 上限可临时调小用于**验证裁剪路径**（真机 100 MB 太大，跑不出来）：
     * `filesDir/ib_limits.txt` 写
     * ```
     * log=20971520
     * chat=20971520
     * ```
     * 就立刻生效（每片换片时读一次）。正常用户不会写这个文件；
     * 删除它即回到 100 MB。和 `ib_lyric.txt` 是同一套「不重装调参」套路。
     */
    fun limits(d: File): Pair<Long, Long> {
        var log = LOG_LIMIT
        var chat = 100L * 1024 * 1024
        runCatching {
            val f = File(d.parentFile ?: d, LIMIT_FILE)
            if (f.isFile) {
                for (raw in f.readText().lineSequence()) {
                    val s = raw.trim()
                    val v = s.substringAfter('=', "").trim().toLongOrNull() ?: continue
                    if (v <= 0L) continue
                    when {
                        s.startsWith("log") -> log = v
                        s.startsWith("chat") -> chat = v
                    }
                }
            }
        }
        return log to chat
    }

    /** 目录文件名里那一天（给「按天看」留的入口，暂未使用）。 */
    internal fun dayOf(f: File): String = dayFmt.format(Date(f.lastModified()))
}
