package com.tg.dbisland.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg.dbisland.BridgeApp
import com.tg.dbisland.ChatStore
import com.tg.dbisland.EnvCheck
import com.tg.dbisland.IslandBridge
import com.tg.dbisland.LogStore
import com.tg.dbisland.ReplyOverlay
import com.tg.dbisland.StartupCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「设置」页（第 46 条**重整过信息架构**：分组 + 组标题 + 组内统一行样式）。
 *
 * 五组，自上而下 —— 顺序按「用户最可能来改的东西」排：
 *   1. **外观**：主题模式（跟随系统 / 浅色 / 深色），存 prefs，**立刻全 App 生效**；
 *   2. **岛显示**：正文一行字数、胶囊分组长（写 `filesDir/ib_lyric.txt`）、岛上预览；
 *   3. **运行环境**：root / LSPosed / 模块注入三项自检 + 豆包进程 + root 管理器、
 *      自启动跳转与检测、悬浮窗权限、后台冻结白名单；
 *   4. **固化与清理**：日志 / 聊天记录占用 + 清空 + Documents 镜像与导出；
 *   5. **关于**：包名、版本、签名权限、提供者、清空内存。
 *
 * 第 71 条（发布清理）：**原来第 5 组「调试」的三条测试事件入口已删除**，
 * 连同它后面没人再用的隐藏调试开关（`detailsCard` 明细卡形态、
 * `island_reply_bar` 通用卡/消息卡对比开关）与 `SmallAction` 组件一起清掉 ——
 * 发布版里不再留任何伪造事件或对比开关的代码路径。
 * `island_reply_bar` 这个 key 也就不再被读写（旧版写过的值留在 prefs 里也不影响）。
 *
 * 第 46 条**只动信息架构与文案**：每个开关/按钮的 key 与既有行为一个都没改
 * （`ib_lyric.txt` 仍是两行、0=胶囊分组长 1=正文一行字数）。同时删掉过期文案
 * （例如上一版留下的「常驻优先（通用卡）」），改成与现在行为一致的描述。
 */
@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? BridgeApp
    val scope = rememberCoroutineScope()

    // ---- 岛显示参数（写在 filesDir/ib_lyric.txt，IslandBridge 3s 内生效）----
    var bodyMax by remember { mutableStateOf(readTunable(ctx, 1, 14)) }
    var lyricGroup by remember { mutableStateOf(readTunable(ctx, 0, 12)) }
    // 第 71 条：卡片形态对比开关（island_reply_bar）已随发布清理删除。
    // 第 45 条（最终形态）：点岛上「回复」= 弹**本应用自己定制的悬浮窗**
    // （ReplyPanel.kt），它要「显示在其他应用上层」这个特殊权限。
    var canOverlay by remember { mutableStateOf(ReplyOverlay.canShow(ctx)) }
    // 第 46 条（真机诊断出来的）：ColorOS 的 OplusHansManager 会反复冻结本应用
    // （logcat 实证 freeze/unfreeze + OsenseKillAction「non perceptible fgs app」）。
    // 被冻住期间豆包模块推来的事件只能排队，一解冻「扎堆」涌出，60 秒未确认
    // 通知也不会响。缓解办法：把本应用加进「忽略电池优化」白名单。
    val power = remember {
        ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    }
    var noFreeze by remember {
        mutableStateOf(power.isIgnoringBatteryOptimizations(ctx.packageName))
    }
    // 特殊权限只能在系统页里开，用户切回来时不一定重组，所以在前台每秒复核一次。
    LaunchedEffect(Unit) {
        while (true) {
            canOverlay = ReplyOverlay.canShow(ctx)
            noFreeze = runCatching {
                power.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(false)
            kotlinx.coroutines.delay(1000)
        }
    }

    // ---- 第 47 条（用户要求）：自启动 / 后台保活的跳转 + 检测 ----
    // ColorOS 不开放那个开关的读取（真机验证：StartupProvider 所有常见 URI 都回
    // IllegalArgumentException，safe.db 的 pp_auto_start 空表），所以检测是
    // **行为推断**（root 下看豆包进程 + 模块心跳），界面里如实写明依据。
    var startup by remember { mutableStateOf<StartupCheck.Result?>(null) }
    var startupBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        startupBusy = true
        startup = withContext(Dispatchers.IO) { runCatching { StartupCheck.probe() }.getOrNull() }
        startupBusy = false
    }

    // ---- 固化占用（第 41 条）：日志 / 聊天记录各一份 + 文档镜像文件数（第 46 条）----
    var logUsed by remember { mutableStateOf(0L) }
    var logLimit by remember { mutableStateOf(LogStore.LOG_LIMIT) }
    var chatUsed by remember { mutableStateOf(0L) }
    var chatLimit by remember { mutableStateOf(ChatStore.CHAT_LIMIT) }
    var docFiles by remember { mutableStateOf(-1) }
    fun reloadStats() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                listOf(
                    LogStore.usedBytes(), LogStore.limitBytes(),
                    ChatStore.usedBytes(), ChatStore.limitBytes(),
                )
            }
            logUsed = r[0]; logLimit = r[1]; chatUsed = r[2]; chatLimit = r[3]
            docFiles = withContext(Dispatchers.IO) {
                runCatching { LogStore.docMirrorCount() }.getOrDefault(-1)
            }
        }
    }
    LaunchedEffect(Unit) { reloadStats() }

    // ---- 环境自检（后台 probe，会 fork 一次 su）----
    var env by remember { mutableStateOf<EnvCheck.Env?>(EnvCheck.cached()) }
    var probing by remember { mutableStateOf(false) }
    fun reprobe() {
        probing = true
        scope.launch {
            val e = withContext(Dispatchers.IO) { EnvCheck.probe(force = true) }
            env = e
            probing = false
        }
    }
    LaunchedEffect(Unit) { if (env == null) reprobe() }

    // ---- 第 45 条（最终形态）：不用系统自由窗口，也不用 ColorOS 小窗 ----
    // 用户决定：「放弃打开悬浮窗，使用我们自己定制的悬浮窗」。
    // 为什么不留小窗那条路：ColorOS 的小窗（zoom window）在系统框架内部，普通
    // App 没有入口（实测 `android:activity.mWindowFlags=8` 被整份忽略），
    // 唯一能浮起来的是 AOSP 自由窗口 + root 改系统开关 —— 用户看到那套「原生
    // 框架」后明确否掉。证据留在 CHANGELOG 第 45 条。

    val scrollState = rememberScrollState()
    // 滚动期间把 dock 的磨砂关掉（见 LocalDockFrost 的说明）
    val scrolling by remember { derivedStateOf { scrollState.isScrollInProgress } }
    CompositionLocalProvider(LocalDockFrost provides dockFrostFor(scrolling)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp),
        ) {
            PageHeader(
                title = "设置",
                subtitle = "外观 · 岛显示 · 运行环境 · 固化与清理 · 关于",
                trailing = {
                    GlassIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = if (probing) "正在重新检测…" else "重新检测运行环境",
                        onClick = { reprobe() },
                    )
                },
            )

            // ================= 1. 外观 =================
            GroupTitle("外观", "立即生效")
            GlassCard {
                ThemePicker(ctx)
            }
            Spacer(Modifier.height(14.dp))

            // ================= 2. 岛显示 =================
            GroupTitle("岛显示", "3 秒内生效")
            GlassCard {
                StepperRow(
                    label = "正文一行字数",
                    hint = "放不下就调小",
                    value = bodyMax, range = 6..40,
                    onChange = {
                        bodyMax = it
                        writeTunables(ctx, g = lyricGroup, b = it)
                    },
                )
                Spacer(Modifier.height(10.dp))
                StepperRow(
                    label = "胶囊分组长",
                    hint = "越小越不容易截断",
                    value = lyricGroup, range = 4..40,
                    onChange = {
                        lyricGroup = it
                        writeTunables(ctx, g = it, b = bodyMax)
                    },
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    icon = Icons.Outlined.Visibility,
                    text = "在岛上预览样例文字",
                    onClick = {
                        app?.bridge?.previewIsland()
                        BridgeApp.instance?.logLine(
                            "岛显示：点了「岛上预览」（正文=$bodyMax 字 / 胶囊组=$lyricGroup）")
                        Toast.makeText(
                            ctx,
                            "已推到岛上。本 App 在前台时岛不显示本 App 内容，请按 Home 或切到豆包查看。",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
                Spacer(Modifier.height(12.dp))
                HairLine()
                Spacer(Modifier.height(10.dp))
                KeyValueRow(
                    "结束态回复模板",
                    "MessageCard · 官方原生回复栏",
                    AppColor.ok,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "本 App 在前台时岛不显示本 App 内容，请按 Home 或切到豆包查看。",
                    color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(14.dp))

            // ================= 3. 运行环境 =================
            GroupTitle("运行环境", if (probing) "检测中…" else env?.let {
                if (it.allOk) "全部正常" else "有待处理项"
            } ?: "尚未检测")
            GlassCard {
                val e = env
                if (e == null) {
                    Text("正在检测（会申请 root）…",
                        color = AppColor.textDim, fontSize = 13.sp)
                } else {
                    EnvRow(e.root, Icons.Outlined.Security)
                    EnvRow(e.lsposed, Icons.Outlined.Science)
                    EnvRow(e.module, Icons.Filled.CheckCircle)
                    Spacer(Modifier.height(6.dp))
                    HairLine()
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("豆包进程", if (e.doubao) "在运行" else "未运行",
                        if (e.doubao) AppColor.ok else AppColor.textDim)
                    KeyValueRow("root 管理器", e.rootMgr.ifBlank { "—" })
                    if (e.lsposed.ok && !e.module.ok) {
                        Spacer(Modifier.height(8.dp))
                        HintBox(
                            "模块没生效？在 LSPosed 启用「豆包岛桥」并勾选作用域 " +
                                "com.larus.nova + android，再强行停止并重启豆包。"
                        )
                    }

                    // ---- 自启动跳转与检测（第 47 条）----
                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(10.dp))
                    Text("自启动 / 后台保活", color = AppColor.text,
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val st = startup
                    KeyValueRow(
                        "当前状态",
                        when {
                            startupBusy -> "检测中…（会 fork 一次 root）"
                            st == null -> "还没测"
                            else -> st.label
                        },
                        when (st?.state) {
                            StartupCheck.State.ON -> AppColor.ok
                            StartupCheck.State.MAYBE_OFF -> AppColor.warn
                            else -> AppColor.textDim
                        },
                    )
                    if (st != null) {
                        Spacer(Modifier.height(6.dp))
                        HintBox(st.detail)
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        icon = Icons.Outlined.Security,
                        text = "去打开自启动",
                        onClick = {
                            val used = StartupCheck.openAutoStart(ctx)
                            Toast.makeText(
                                ctx,
                                used?.let { "已打开：$it" }
                                    ?: "这个 ROM 没有自启动页，已跳到应用详情",
                                Toast.LENGTH_LONG,
                            ).show()
                            LogStore.append(HubLogLevel.INFO, "自启动入口：$used")
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        icon = Icons.Outlined.Refresh,
                        text = if (startupBusy) "正在重新检测…" else "重新检测自启动",
                        onClick = {
                            if (startupBusy) return@ActionButton
                            startupBusy = true
                            scope.launch {
                                startup = withContext(Dispatchers.IO) {
                                    runCatching { StartupCheck.probe() }.getOrNull()
                                }
                                startupBusy = false
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ColorOS 不让读该开关，结果由「豆包进程 + 模块心跳」推断。",
                        color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                    )

                    // ---- 悬浮窗权限（第 45 条）----
                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(10.dp))
                    Text("悬浮窗权限", color = AppColor.text,
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow(
                        "显示在其他应用上层",
                        if (canOverlay) "已授权 · 可回复" else "未授权 · 无法回复",
                        if (canOverlay) AppColor.ok else AppColor.warn,
                    )
                    Spacer(Modifier.height(6.dp))
                    ActionButton(
                        icon = Icons.Outlined.OpenInNew,
                        text = if (canOverlay) "已授权" else "去授权",
                        onClick = {
                            runCatching {
                                ctx.startActivity(Intent(
                                    android.provider.Settings
                                        .ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                // 个别 ROM 没有这个页面，退回应用详情页
                                runCatching {
                                    ctx.startActivity(Intent(
                                        android.provider.Settings
                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:${ctx.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "「回复」弹的是本应用自绘悬浮窗：可直接打字，发送后经模块发回" +
                            "这条豆包会话；聊天页只读，这是唯一回复入口。",
                        color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                    )

                    // ---- 后台冻结白名单（第 46 条）----
                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(10.dp))
                    Text("后台冻结（省电策略）", color = AppColor.text,
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow(
                        "忽略电池优化",
                        if (noFreeze) "已加入"
                        else "未加入 · 会被 ColorOS 冻结",
                        if (noFreeze) AppColor.ok else AppColor.warn,
                    )
                    Spacer(Modifier.height(6.dp))
                    ActionButton(
                        icon = Icons.Outlined.Security,
                        text = if (noFreeze) "已在白名单" else "加入白名单",
                        onClick = {
                            runCatching {
                                ctx.startActivity(Intent(
                                    android.provider.Settings
                                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                // 少数 ROM 没这个弹窗页，退回电池优化列表
                                runCatching {
                                    ctx.startActivity(Intent(
                                        android.provider.Settings
                                            .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "依据：真机 logcat 里 OplusHansManager 反复冻结本应用，" +
                            "被冻期间事件只能排队、解冻后扎堆冒出。",
                        color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ================= 4. 固化与清理（第 41 条）=================
            GroupTitle("固化与清理", "上限各 100 MB，超出删最旧")
            GlassCard {
                KeyValueRow("日志占用",
                    "${mbText(logUsed)} / 上限 ${mbText(logLimit)} · " +
                        "${LogStore.shardCount()} 片")
                KeyValueRow("日志目录", "filesDir/logs", mono = true)
                KeyValueRow("文档镜像",
                    when {
                        docFiles < 0 -> "读取中…"
                        else -> "Documents/豆包岛桥/日志/ · $docFiles 个文件"
                    },
                    mono = false)
                Spacer(Modifier.height(6.dp))
                HairLine()
                Spacer(Modifier.height(6.dp))
                KeyValueRow("聊天记录占用",
                    "${mbText(chatUsed)} / 上限 ${mbText(chatLimit)} · " +
                        "${ChatStore.fileCount()} 个会话文件")
                KeyValueRow("聊天记录目录", ChatStore.dirName(), mono = true)
                if (LogStore.isDegraded() || ChatStore.isDegraded()) {
                    Spacer(Modifier.height(8.dp))
                    HintBox("落盘已降级（IO 异常）：只保证 logcat / 内存；重开 App 会再试。")
                }
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    icon = Icons.Outlined.Download,
                    text = "导出日志 TXT 到 ${LogStore.exportDirName()}",
                    onClick = {
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                runCatching { LogStore.exportToDocuments() }.getOrNull()
                            }
                            Toast.makeText(
                                ctx,
                                path?.let { "已导出到 $it" } ?: "导出失败：写不进文档目录（见日志）",
                                Toast.LENGTH_LONG,
                            ).show()
                            reloadStats()
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    icon = Icons.Filled.DeleteSweep,
                    text = "清空日志（私有 + 文档目录）",
                    danger = true,
                    onClick = {
                        LogStore.clearAll()
                        BridgeHub.clearLogMemory()
                        Toast.makeText(ctx, "已清空磁盘日志", Toast.LENGTH_SHORT).show()
                        reloadStats()
                    },
                )
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    icon = Icons.Filled.DeleteSweep,
                    text = "清空聊天记录（filesDir/chat）",
                    danger = true,
                    onClick = {
                        val n = ChatStore.clearAll()
                        BridgeHub.clearAll()
                        Toast.makeText(ctx, "已清空 $n 个会话文件", Toast.LENGTH_SHORT).show()
                        reloadStats()
                    },
                )
            }
            Spacer(Modifier.height(14.dp))

            // ================= 5. 关于 =================
            GroupTitle("关于")
            GlassCard {
                KeyValueRow("应用包名", "com.tg.dbisland", mono = true)
                KeyValueRow(
                    "版本",
                    runCatching {
                        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        "${pi.versionName} (${pi.longVersionCode})"
                    }.getOrDefault("—"),
                )
                KeyValueRow("签名权限", "com.tg.dbisland.permission.CONTROL", mono = true)
                KeyValueRow("提供者", "com.tg.dbisland.events", mono = true)
                Spacer(Modifier.height(8.dp))
                HairLine()
                Spacer(Modifier.height(8.dp))
                Text(
                    "事件只来自豆包进程内的 LSPosed 模块；App 不联网、不监听端口、" +
                        "不写 /data/adb。日志与聊天记录都在 filesDir 下。",
                    color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    icon = Icons.Filled.Warning,
                    text = "清空内存里的会话与日志",
                    danger = true,
                    onClick = {
                        BridgeHub.clearAll()
                        Toast.makeText(ctx, "已清空（内存 + 磁盘）", Toast.LENGTH_SHORT).show()
                        reloadStats()
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ------------------------------------------------------------------ 分组与主题

/**
 * 组标题（第 46 条）：左侧一根 3dp 的 accent 竖条 + 组名 + 可选的右侧说明。
 *
 * 为什么不用 `GlassCard(title=…)` 当组标题：那样「标题」被包在玻璃卡片里面，
 * 六个卡片各有一个标题，视觉上仍是「六张并列的卡」，看不出分组层级。组标题
 * 独立放在卡片之上，一眼就能数出有几组。
 */
@Composable
private fun GroupTitle(text: String, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColor.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = AppColor.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(hint, color = AppColor.textFaint, fontSize = 11.sp)
        }
    }
}

/**
 * 主题三选（第 46 条）：跟随系统 / 浅色 / 深色。
 *
 * 为什么是三选而不是「一个跟随开关 + 一个深浅开关」：两个开关有四种组合，
 * 其中「不跟随 + 深浅」这一维才是真正的选择，做成两个开关既重复又会产生
 * 「跟随关了但没选颜色」的空档。三选一格，状态只有一个。
 *
 * 点了就 `AppColor.setMode` —— 写 prefs + 改 snapshot state，**全 App 立刻重组**。
 */
@Composable
private fun ThemePicker(ctx: Context) {
    val cur = AppColor.themeMode
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { m ->
            val on = m == cur
            Box(
                Modifier
                    .weight(1f)
                    .clip(AppShape.chip)
                    .background(
                        if (on) AppColor.accent.copy(alpha = 0.22f)
                        else AppColor.fill.copy(alpha = 0.06f)
                    )
                    .clickableNoRipple {
                        if (m == cur) return@clickableNoRipple
                        AppColor.setMode(ctx, m)
                        BridgeApp.instance?.logLine(
                            "外观：主题模式 → ${m.label}（生效=" +
                                "${if (AppColor.isDark) "深色" else "浅色"}，" +
                                "系统=${if (AppColor.systemIsDark) "深色" else "浅色"}）")
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    color = if (on) AppColor.accent else AppColor.textDim,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "当前：${if (AppColor.isDark) "深色" else "浅色"}" +
            "（系统${if (AppColor.systemIsDark) "深色" else "浅色"}）",
        color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
    )
}

// ------------------------------------------------------------------ 子组件

@Composable
private fun EnvRow(c: EnvCheck.Check, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    StatusRow(
        color = if (c.ok) AppColor.ok else AppColor.warn,
        title = c.label,
        detail = c.detail,
        icon = icon,
    )
}

@Composable
private fun HintBox(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(AppShape.cardSmall)
            .background(AppColor.warn.copy(alpha = 0.10f))
            .padding(10.dp),
    ) {
        Text(text, color = AppColor.warn, fontSize = 11.5.sp, lineHeight = 17.sp)
    }
}

/** 一行「−/＋」步进器（岛参数用）。 */
@Composable
private fun StepperRow(
    label: String,
    hint: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AppColor.text, fontSize = 14.sp)
            Text(hint, color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundStep(Icons.Filled.Remove, enabled = value > range.first) {
                onChange((value - 1).coerceIn(range))
            }
            Text(
                "$value",
                color = AppColor.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            RoundStep(Icons.Filled.Add, enabled = value < range.last) {
                onChange((value + 1).coerceIn(range))
            }
        }
    }
}

@Composable
private fun RoundStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(AppColor.fill.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickableNoRipple(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            icon, null,
            tint = if (enabled) AppColor.text else AppColor.textFaint.copy(alpha = 0.4f),
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AppColor.text, fontSize = 14.sp)
            Text(hint, color = AppColor.textFaint, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppColor.accent,
                uncheckedThumbColor = AppColor.textDim,
                uncheckedTrackColor = AppColor.fill.copy(alpha = 0.10f),
            ),
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (danger) AppColor.bad.copy(alpha = 0.12f)
                else AppColor.fill.copy(alpha = 0.06f)
            )
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            icon, null,
            tint = if (danger) AppColor.bad else AppColor.textDim,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = if (danger) AppColor.bad else AppColor.text,
            fontSize = 13.5.sp,
        )
    }
}

// ------------------------------------------------------------------ 调参文件

/** 字节 → 「x.yz MB」（占用行用）。 */
private fun mbText(bytes: Long): String {
    val m = bytes.toDouble() / 1024 / 1024
    return if (m >= 10) String.format(java.util.Locale.US, "%.1f MB", m)
    else String.format(java.util.Locale.US, "%.2f MB", m)
}

private fun tunableFile(ctx: Context) = java.io.File(ctx.filesDir, "ib_lyric.txt")

/** 读第 [index] 行（0=胶囊分组长，1=正文一行字数），越界/非法就取 [def]。 */
private fun readTunable(ctx: Context, index: Int, def: Int): Int = try {
    tunableFile(ctx).readText().lineSequence().toList()
        .getOrNull(index)?.trim()?.toIntOrNull() ?: def
} catch (_: Throwable) {
    def
}

/** 写两行（与 [IslandBridge.tunables] 读的格式一致）。 */
private fun writeTunables(ctx: Context, g: Int? = null, b: Int? = null) {
    val cg = g ?: readTunable(ctx, 0, 12)
    val cb = b ?: readTunable(ctx, 1, 14)
    try {
        tunableFile(ctx).writeText("$cg\n$cb\n")
    } catch (_: Throwable) {
    }
}
