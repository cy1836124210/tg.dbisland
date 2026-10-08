package com.tg.dbisland

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 公共「文档」目录读写（用户要求：**日志默认存在安卓手机都有的 Documents 文件夹**）。
 *
 * ## 真机事实
 * · 这台机器上 `/sdcard/Documents` 本来就在（用户自己也有文件在里面），
 *   `Environment.DIRECTORY_DOCUMENTS` = `Documents`；
 * · Android 10（API 29）起**不允许**普通应用直接 `File` 写 `/sdcard/...`，
 *   想往公共 `Documents` 里放文件的正规通路是 **MediaStore**（`RELATIVE_PATH`
 *   指到 `Documents/豆包岛桥/…`，**不需要任何存储权限**，只写自己插入的文件）。
 *   所以这里走 MediaStore，不申请 `MANAGE_EXTERNAL_STORAGE` /
 *   `WRITE_EXTERNAL_STORAGE`（本应用「不联网、不多要权限」的既定原则不变）。
 * · API < 29（minSdk 26）没有 MediaStore 这一套：退回**应用专属外部目录**
 *   `/sdcard/Android/data/com.tg.dbisland/files/Documents/豆包岛桥/…`（同样零权限），
 *   并在 [displayPath] 里如实告诉用户文件在哪。
 *
 * 目录结构：
 * ```
 * /sdcard/Documents/豆包岛桥/日志/<私有分片同名>.log      ← 与私有分片同名，持续镜像
 * /sdcard/Documents/豆包岛桥/导出/豆包岛桥日志_20261005_171230.txt ← 日志页「导出 TXT」
 * ```
 *
 * ## 第 46 条：为什么以前会一直生成 `(1) (2) (3)` 编号副本
 *
 * 真机证据（`dev/mq.sh` 查 MediaStore 行）：
 * ```
 * _id=27933 _display_name=2026-10-05_17_35_25_774.log.txt      mime=text/plain size=108
 * _id=27934 _display_name=2026-10-05_17_35_25_774.log (1).txt  mime=text/plain size=93
 * …                                                            （一直到 (6)）
 * ```
 * 三个事实叠在一起就成了这个 bug：
 *   1. 私有分片名里带 `:`（`HH:mm:ss`）。MediaStore 落盘时把 FAT 非法字符**原地
 *      换成 `_`**，于是**存进数据库的 DISPLAY_NAME 和我们要找的名字不一样**；
 *   2. [find] 以前是「`RELATIVE_PATH=? AND DISPLAY_NAME=?` 精确匹配」—— 拿带
 *      `:` 的原名去查被换成 `_` 的名字，**永远查不到** → 每次都走 [create]；
 *   3. `insert` 同一个名字时 MediaProvider 会自动去重（`X.log` → `X.log (1)`），
 *      并且按 MIME 给名字**补后缀**（`.log` → `.log.txt`）。于是每刷一次镜像
 *      就多一个 `(N)` 文件，7 次之后就是 `(1)…(6)`。
 *
 * 修法（两边一起）：
 *   · [safeName]：查询前把名字归一化（非法字符 → `_`），查询本身也改成
 *     「拉整个目录再比对」，不再依赖精确 SQL 匹配；
 *   · 认得下 MIME 补的后缀与 ` (N)`：归一化后**以目标名开头**的也算同一个文件，
 *     并固定取 `_id` 最小的那一份（最早创建的那份）—— 于是每次都认领同一个文件；
 *   · [LogStore] 侧把分片名里的 `:` 去掉（`HH_mm_ss`），从源头不再触发改名；
 *   · [dedupe] 启动时清掉历史遗留的编号副本，只留一份并把它补成完整镜像。
 */
object DocStore {

    /** 公共目录下的应用文件夹名。 */
    const val ROOT = "豆包岛桥"

    /**
     * FAT/exFAT 不允许出现在文件名里的字符 + 控制字符。
     * MediaProvider 落盘时会把它们换成 `_`，所以**查库前必须做同样的归一化**。
     */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** 见 [ILLEGAL]：把名字归一化成「MediaStore 会把我们写进去的名字变成的样子」。 */
    fun safeName(name: String): String = ILLEGAL.replace(name, "_")

    private fun collection(): Uri =
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relPath(sub: String): String =
        "${Environment.DIRECTORY_DOCUMENTS}/$ROOT/${sub.trim('/')}"

    /** 目录里的一行（`_id` 用于删除/定位，`name` 用来判断是不是同一个文件）。 */
    private data class Row(val id: Long, val name: String, val size: Long, val added: Long)

    /** 拉出 `Documents/豆包岛桥/<sub>/` 下**本应用写过的**全部行（API 29+）。 */
    private fun rows(ctx: Context, sub: String): List<Row> = runCatching {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val out = ArrayList<Row>()
        ctx.contentResolver.query(
            collection(), cols,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("${relPath(sub)}/"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Row(
                    c.getLong(0),
                    c.getString(1) ?: "",
                    c.getLong(2),
                    c.getLong(3),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * MediaStore 里找已经插入过的那个文件（**只可能找到本应用自己的**）。
     *
     * 第 46 条：不再用精确 SQL 匹配（见类注释的三条真机事实），改成
     *   1. 先找**归一化后完全相同**的；
     *   2. 再找**归一化后以目标名开头**的（`X.log` 认领 `X.log.txt` /
     *      `X.log (1).txt`）；
     *   3. 同名有多个时固定取 `_id` 最小的（最早那一份）—— 保证每次认领同一个，
     *      而不会这次写 A、下次写 B。
     */
    private fun find(ctx: Context, sub: String, name: String): Uri? {
        val want = safeName(name)
        val all = rows(ctx, sub)
        if (all.isEmpty()) return null
        val hit = all.filter { safeName(it.name) == want }.minByOrNull { it.id }
            ?: all.filter { safeName(it.name).startsWith(want) }.minByOrNull { it.id }
        return hit?.let { ContentUris.withAppendedId(collection(), it.id) }
    }

    /** 旧系统 / MediaStore 失败时的兜底目录（应用专属外部目录，零权限）。 */
    private fun legacyDir(ctx: Context, sub: String): File? {
        val base = ctx.getExternalFilesDir(null) ?: return null
        return File(base, "Documents/$ROOT/${sub.trim('/')}")
    }

    /** 给用户看的路径（和设备文件管理器里看到的尽量一致）。 */
    fun displayPath(ctx: Context, sub: String, name: String): String =
        if (Build.VERSION.SDK_INT >= 29) "/sdcard/${relPath(sub)}/$name"
        else legacyDir(ctx, sub)?.let { "${it.absolutePath}/$name" } ?: "（外部存储不可用）"

    /** 目录（给「日志页」显示用）。 */
    fun displayDir(ctx: Context, sub: String): String =
        if (Build.VERSION.SDK_INT >= 29) "/sdcard/${relPath(sub)}/"
        else legacyDir(ctx, sub)?.absolutePath ?: "（外部存储不可用）"

    /** 目录里现在有几个文件（设置页「文档镜像」行显示用；取不到返回 -1）。 */
    fun count(ctx: Context, sub: String): Int =
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { rows(ctx, sub).size }.getOrDefault(-1)
        } else {
            runCatching { legacyDir(ctx, sub)?.listFiles()?.size ?: 0 }.getOrDefault(-1)
        }

    /** 分组用的规范名：剥掉 MediaProvider 可能加的两层壳（` (N)` 与 MIME 补的 `.txt`）。 */
    private fun groupKey(displayName: String): String {
        var n = safeName(displayName)
        repeat(3) {
            n = n.replace(Regex(" \\(\\d+\\)$"), "")
            if (n.endsWith(".txt")) n = n.removeSuffix(".txt")
        }
        return n
    }

    /**
     * 清掉「同一个文件被 MediaStore 编了号」的历史副本，**每组只留一份**。
     *
     * @return `删掉的数量 to 留下的文件名`（没有重复时是 `0 to null`）。
     *
     * 留哪一份：`_id` 最小的那一份 —— 与 [find] 认领的规则一致（见 [find] 第 3 点）。
     * 只删本应用在这个目录里写过的行，不碰用户的其它文件。
     */
    fun dedupe(ctx: Context, sub: String): Pair<Int, String?> {
        if (Build.VERSION.SDK_INT < 29) {
            val d = legacyDir(ctx, sub) ?: return 0 to null
            val fs = runCatching { d.listFiles() }.getOrNull() ?: return 0 to null
            val groups = fs.groupBy { groupKey(it.name) }
            var del = 0
            var kept: String? = null
            for ((_, list) in groups) {
                if (list.size <= 1) continue
                val sorted = list.sortedBy { it.lastModified() }
                kept = sorted.first().name
                for (f in sorted.drop(1)) if (runCatching { f.delete() }.getOrDefault(false)) del++
            }
            return del to kept
        }
        val all = rows(ctx, sub)
        if (all.size <= 1) return 0 to null
        var del = 0
        var kept: String? = null
        for ((_, list) in all.groupBy { groupKey(it.name) }) {
            if (list.size <= 1) continue
            val sorted = list.sortedBy { it.id }
            kept = sorted.first().name
            for (r in sorted.drop(1)) {
                val ok = runCatching {
                    ctx.contentResolver.delete(
                        ContentUris.withAppendedId(collection(), r.id), null, null)
                }.getOrNull() ?: 0
                if (ok > 0) del++
            }
        }
        return del to kept
    }

    /**
     * **追加**一段字节（不存在就建）。返回实际用的 Uri（null = 两条路都失败）。
     * 运行在调用方的后台线程上（MediaStore 是 binder 调用，别放主线程）。
     */
    fun append(ctx: Context, sub: String, name: String, bytes: ByteArray,
               mime: String = "application/octet-stream"): Uri? {
        if (bytes.isEmpty()) return find(ctx, sub, name)
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = find(ctx, sub, name) ?: create(ctx, sub, name, ByteArray(0), mime)
            if (uri != null) {
                // "wa" = 追加写（API 29+ MediaProvider 支持）；不支持就退化成「读+覆盖」
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wa")?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
                if (ok) return uri
                val old = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: ByteArray(0)
                return overwrite(ctx, uri, old + bytes)
            }
        }
        // 兜底：应用专属外部目录直接 File 追加
        val d = legacyDir(ctx, sub) ?: return null
        return runCatching {
            if (!d.exists()) d.mkdirs()
            val f = File(d, name)
            java.io.FileOutputStream(f, true).use { it.write(bytes) }
            Uri.fromFile(f)
        }.getOrNull()
    }

    /** **覆盖**写一个新文件（导出用）。返回 Uri 或 null。 */
    fun write(ctx: Context, sub: String, name: String, bytes: ByteArray,
              mime: String = "text/plain"): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = find(ctx, sub, name) ?: create(ctx, sub, name, bytes, mime)
            return if (uri != null) overwrite(ctx, uri, bytes) else null
        }
        val d = legacyDir(ctx, sub) ?: return null
        return runCatching {
            if (!d.exists()) d.mkdirs()
            val f = File(d, name)
            f.writeBytes(bytes)
            Uri.fromFile(f)
        }.getOrNull()
    }

    /** 插入一条 MediaStore 记录并写内容（API 29+）。 */
    private fun create(ctx: Context, sub: String, name: String, bytes: ByteArray,
                       mime: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // **MIME 会决定落盘文件名**：日志分片叫 `xxx.log`，要是这里写 text/plain，
            // MediaProvider 会自动补成 `xxx.log.txt`（真机上就这么出现过）。
            // 所以 .log 用 application/octet-stream，导出的 .txt 才用 text/plain。
            // （第 46 条的真机复查：ColorOS 的 MediaProvider 仍然把 `.log` 认成
            //  text/plain 并补了 `.txt`，所以 [find] 也认「以目标名开头」的变体。）
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath(sub))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection(), values) ?: return null
        overwrite(ctx, uri, bytes)
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        uri
    }.getOrNull()

    private fun overwrite(ctx: Context, uri: Uri, bytes: ByteArray): Uri? = runCatching {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        uri
    }.getOrNull()

    /**
     * 把公共目录里本应用写过的分片按上限裁剪（默认 100 MB，与私有分片同口径）。
     * 只删自己插入的文件；查询不到（老系统）就跳过。
     */
    fun trim(ctx: Context, sub: String, limitBytes: Long) {
        if (Build.VERSION.SDK_INT < 29) {
            val d = legacyDir(ctx, sub) ?: return
            val fs = d.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = fs.sumOf { it.length() }
            for (f in fs) {
                if (total <= limitBytes) break
                total -= f.length()
                runCatching { f.delete() }
            }
            return
        }
        runCatching {
            val rows = rows(ctx, sub)
            var total = rows.sumOf { it.size }
            for (r in rows.sortedBy { it.added }) {
                if (total <= limitBytes) break
                total -= r.size
                runCatching {
                    ctx.contentResolver.delete(
                        ContentUris.withAppendedId(collection(), r.id), null, null)
                }
            }
        }
    }
}
