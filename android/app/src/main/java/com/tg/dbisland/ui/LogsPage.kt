package com.tg.dbisland.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg.dbisland.LogStore
import com.tg.dbisland.ui.glass.GlassBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志筛选。 */
private enum class LogFilter(val label: String) {
    All("全部"), Info("信息"), Warn("提醒"), Error("错误")
}

/**
 * 「日志」页 —— 模块的运行情况。
 *
 * 四块（自上而下）：
 *   1. **运行状态卡**：模块心跳 / 岛连接 / 推流帧数 / 保活窗 / 上行结果；
 *   2. **占用行**：`已用 12.3 MB / 上限 100 MB · N 片`（第 41 条）；
 *   3. **筛选条**：全部 / 信息 / 提醒 / 错误；
 *   4. **日志流**：**从磁盘读**（`filesDir/logs` 里最新的一片片，最新 800 条，
 *      按级别筛选）。
 *
 * 第 41 条的变化：这一页以前读 [BridgeHub] 里那份 800 条内存环形缓冲，
 *  App 一被杀就什么都没了；现在读**磁盘**上固化的日志（≤ 100 MB，超出删最旧的
 *  片，见 [LogStore]）。右上角两个按钮：刷新（重新读盘）/ 清空。
 *
 * **诚实边界**：LSPosed 模块住在豆包进程里，它自己的日志**只进 logcat**，
 * 这里固化的是本 App（岛桥）这一侧的日志。模块日志用
 * `adb logcat -s IslandBridge` 看。
 */
@Composable
fun LogsPage() {
    val st by BridgeHub.state.collectAsStateSafe()
    var filter by remember { mutableStateOf(LogFilter.All) }
    var entries by remember { mutableStateOf<List<Pair<Long, HubLogLevel>>>(
        emptyList()) }
    var texts by remember { mutableStateOf<List<String>>(emptyList()) }
    var usedBytes by remember { mutableStateOf(0L) }
    var limitBytes by remember { mutableStateOf(LogStore.LOG_LIMIT) }
    var shards by remember { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    /** 导出 TXT 进行中（防止连点重复写文件）。 */
    var exporting by remember { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    // 读盘在 IO 线程上（一页最多 800 行，几十 KB）
    LaunchedEffect(reload, st.logs.size) {
        val r = withContext(Dispatchers.IO) {
            LogStore.readNewest(800) to Triple(
                LogStore.usedBytes(), LogStore.limitBytes(), LogStore.shardCount())
        }
        entries = r.first.map { it.first to it.second }
        texts = r.first.map { it.third }
        usedBytes = r.second.first
        limitBytes = r.second.second
        shards = r.second.third
    }

    val shown = remember(entries, texts, filter) {
        indicesOf(entries, filter)
    }

    // 跟随最新一条（只在用户没往回翻时）
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (last >= shown.size - 3) listState.animateScrollToItem(shown.lastIndex)
        }
    }

    // 滚动期间把 dock 的磨砂关掉（见 LocalDockFrost 的说明）
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    CompositionLocalProvider(LocalDockFrost provides dockFrostFor(scrolling)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            PageHeader(
                title = "日志",
                subtitle = "磁盘上的固化日志 · 共 ${entries.size} 条",
                trailing = {
                    Row {
                        // 用户要求：「日志页没有给导出 txt」→ 一键写进公共文档目录
                        // /sdcard/Documents/豆包岛桥/导出/豆包岛桥日志_<时间>.txt
                        GlassIconButton(
                            icon = Icons.Filled.Download,
                            contentDescription = if (exporting) "正在导出…" else "导出 TXT 到 Documents",
                            onClick = {
                                if (exporting) return@GlassIconButton
                                exporting = true
                                exportScope.launch {
                                    val path = withContext(Dispatchers.IO) {
                                        LogStore.exportToDocuments()
                                    }
                                    exporting = false
                                    android.widget.Toast.makeText(
                                        ctx,
                                        path?.let { "已导出到 $it" }
                                            ?: "导出失败：写不进文档目录（见日志）",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                    reload++
                                }
                            },
                        )
                        GlassIconButton(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "重新读盘",
                            onClick = { reload++ },
                        )
                        GlassIconButton(
                            icon = Icons.Filled.DeleteSweep,
                            contentDescription = "清空日志",
                            onClick = {
                                LogStore.clearAll()
                                BridgeHub.clearLogMemory()
                                reload++
                            },
                        )
                    }
                },
            )

            RuntimeCard(st.runtime)
            Spacer(Modifier.height(10.dp))
            UsageRow(usedBytes, limitBytes, shards, entries.size)
            Spacer(Modifier.height(8.dp))
            FilterBar(filter, onFilter = { filter = it }, total = entries.size)
            Spacer(Modifier.height(8.dp))

            GlassBox(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = AppShape.card,
            ) {
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "还没有日志\n模块或被动的岛回调一有动作就会出现在这里\n" +
                                "（已固化到 filesDir/logs，上限 100 MB）",
                            color = AppColor.textFaint, fontSize = 12.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(shown) { i, e ->
                            LogRow(HubLogEntry(entries[e].first, entries[e].second,
                                texts[e]), i)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 级别筛选：返回**原下标**（key 用下标，避免同一毫秒的两条撞键）。 */
private fun indicesOf(entries: List<Pair<Long, HubLogLevel>>,
                      filter: LogFilter): List<Int> {
    val out = ArrayList<Int>(entries.size)
    for (i in entries.indices) {
        val lv = entries[i].second
        val ok = when (filter) {
            LogFilter.All -> true
            LogFilter.Info -> lv == HubLogLevel.INFO
            LogFilter.Warn -> lv == HubLogLevel.WARN
            LogFilter.Error -> lv == HubLogLevel.ERROR
        }
        if (ok) out.add(i)
    }
    return out
}

/** 「已用 x MB / 上限 100 MB · N 片」+ 目录名（第 41 条）。 */
@Composable
private fun UsageRow(used: Long, limit: Long, shards: Int, count: Int) {
    val text = buildString {
        append("已用 ")
        append(mb(used))
        append(" / 上限 ")
        append(mb(limit))
        if (shards > 0) append(" · $shards 片")
        append(" · ")
        append(LogStore.dirName())
        if (count >= 800) append("（只显示最新 800 条）")
    }
    Text(
        text,
        color = if (used > limit) AppColor.bad else AppColor.textDim,
        fontSize = 11.5.sp,
    )
}

private fun mb(bytes: Long): String {
    val m = bytes.toDouble() / 1024 / 1024
    return if (m >= 10) String.format(Locale.US, "%.1f MB", m)
    else String.format(Locale.US, "%.2f MB", m)
}

/** 运行状态卡：把 [HubRuntime] 翻译成一眼能看懂的四行。 */
@Composable
private fun RuntimeCard(r: HubRuntime) {
    val now = System.currentTimeMillis()
    val pingAge = if (r.lastPingAtMs > 0) (now - r.lastPingAtMs) / 1000 else -1L
    // 心跳 180s 内算活着 —— 与 EnvCheck.STALE_SEC 同一口径
    val alive = r.alive && pingAge in 0..180
    val frameAge = if (r.lastFrameAtMs > 0) (now - r.lastFrameAtMs) / 1000 else -1L

    GlassCard(modifier = Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (alive) "模块在线" else "模块未生效",
                    color = if (alive) AppColor.ok else AppColor.bad,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        r.pings == 0 -> "还没收到过心跳（豆包没开或作用域没勾）"
                        pingAge >= 0 -> "最近心跳 ${pingAge}s 前 · 累计 ${r.pings} 次"
                        else -> "累计 ${r.pings} 次"
                    },
                    color = AppColor.textDim, fontSize = 12.sp,
                )
            }
            Pill(
                if (r.keepAlive) "保活中" else "空闲",
                if (r.keepAlive) AppColor.warn else AppColor.textFaint,
                filled = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        HairLine()
        Spacer(Modifier.height(4.dp))
        StatusRow(
            color = if (alive) AppColor.ok else AppColor.bad,
            title = "豆包注入",
            detail = if (alive) "心跳正常（近 3 分钟内有）" else "需要 LSPosed 启用本模块并勾 com.larus.nova",
            icon = if (alive) Icons.Outlined.Sync else Icons.Outlined.CloudOff,
        )
        StatusRow(
            color = AppColor.accent,
            title = "岛连接",
            detail = "IslandClient 状态：${r.islandState}",
            icon = Icons.Filled.Bolt,
        )
        StatusRow(
            color = AppColor.textDim,
            title = "推流帧",
            detail = buildString {
                append("累计 ${r.streamFrames} 帧")
                if (frameAge >= 0) append(" · 最近 ${frameAge}s 前")
                if (frameAge in 0..60) append("（正在推流）")
            },
            icon = Icons.Filled.Download,
        )
        StatusRow(
            color = when (r.lastSendOk) {
                true -> AppColor.ok
                false -> AppColor.bad
                null -> AppColor.textFaint
            },
            title = "上行命令",
            detail = if (r.sends == 0) "还没发送过回复/删除"
            else "累计 ${r.sends} 次 · 最近：${r.lastSendDetail}",
            icon = if (r.lastSendOk == false) Icons.Outlined.CloudOff else Icons.Outlined.DownloadDone,
        )
    }
}

@Composable
private fun FilterBar(filter: LogFilter, onFilter: (LogFilter) -> Unit, total: Int) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogFilter.entries.forEach { f ->
            val on = f == filter
            Box(
                Modifier
                    .clip(AppShape.chip)
                    .background(
                        if (on) AppColor.accent.copy(alpha = 0.22f)
                        else AppColor.fill.copy(alpha = 0.05f)
                    )
                    .clickableNoRipple { onFilter(f) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    f.label,
                    color = if (on) AppColor.accent else AppColor.textDim,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        if (total > 0) {
            Text("$total 条", color = AppColor.textFaint, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun LogRow(e: HubLogEntry, key: Int) {
    val color = when (e.level) {
        HubLogLevel.INFO -> AppColor.textDim
        HubLogLevel.WARN -> AppColor.warn
        HubLogLevel.ERROR -> AppColor.bad
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColor.fill.copy(alpha = 0.025f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            TIME_FMT.format(Date(e.atMs)),
            color = AppColor.textFaint, fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(58.dp),
        )
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            e.text,
            color = if (e.level == HubLogLevel.INFO) AppColor.text else color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
