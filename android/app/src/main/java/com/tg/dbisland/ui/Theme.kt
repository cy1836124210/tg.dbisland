package com.tg.dbisland.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题模式（第 46 条：**用户要求在设置页里自己选**，不再只跟随系统）。
 *
 * 三个选项而不是两个：`跟随系统` 是「默认且不打扰」的那一档，去掉它会让
 * 「我不想管这件事」的用户被迫做一次选择。
 * 用枚举而不是三个 Boolean：状态只有一个，不可能出现「又浅又深」。
 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        /** prefs 里的字符串 → 枚举；任何脏值都退回 [SYSTEM]（永不崩、永不为空）。 */
        fun of(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * 界面配色（**跟随系统**，或由用户在设置页里固定成浅色/深色 —— 第 46 条）。
 *
 * 历史：第 38 条做「液体玻璃」时配色是**写死深色**的 —— 当时的理由是玻璃在白底上
 * 几乎看不出折射与反光。用户后来明确要求 App 与悬浮窗都自动跟随系统，所以现在
 * 每个颜色都是**亮/暗一对**，由 [dark] 这个 snapshot state 选边。
 *
 * 第 46 条又加了一层：**模式下再选边**。[mode] 是用户的选择（默认
 * [ThemeMode.SYSTEM]），[systemDark] 是系统当前值；真正决定配色的是
 * [dark] = `resolve(mode, systemDark)`。
 *
 * 怎么让它「自动重组」：这些值是 `get()` 出来的（不是常量），getter 里读的是
 * snapshot state [dark]，所以任何在组合/绘制里读过 `AppColor.xxx` 的地方，在
 * 主题变化时都会自动失效并重组 —— 不用改 100 多个调用点，也**不需要重启 App**。
 * 三个写入点：
 *   · [init]（`BridgeApp.onCreate`，首帧之前就把 prefs 里的选择读进来）；
 *   · [setSystemDark]（`BridgeApp` / `MainActivity` / [IslandBridgeTheme] 报告
 *     系统的亮暗变化 —— 注意它**只记系统值**，不会覆盖用户选的浅色/深色）；
 *   · [setMode]（设置页点「跟随系统 / 浅色 / 深色」→ 存 prefs + 立刻重组）。
 */
object AppColor {

    /** 主题模式在 `SharedPreferences("bridge")` 里的键。 */
    const val PREF_THEME = "theme_mode"

    /** 用户选的主题模式（默认跟随系统）。 */
    private var mode by mutableStateOf(ThemeMode.SYSTEM)

    /** 系统当前的亮暗（只在 [ThemeMode.SYSTEM] 下参与决策）。 */
    private var systemDark by mutableStateOf(true)

    /** 真正生效的亮暗（所有颜色的选边依据）。 */
    private var dark by mutableStateOf(true)

    /** 当前是不是深色（供 [com.tg.dbisland.ui.glass.GlassStyle] 等一起跟随）。 */
    val isDark: Boolean get() = dark

    /** 用户在设置页选的那一档（设置页的高亮态用它）。 */
    val themeMode: ThemeMode get() = mode

    /** 系统那一档当前是什么（设置页显示「系统为浅色/深色」用）。 */
    val systemIsDark: Boolean get() = systemDark

    /** `mode + 系统值` → 生效值。纯函数，方便单测/推理。 */
    fun resolve(m: ThemeMode, sysDark: Boolean): Boolean = when (m) {
        ThemeMode.SYSTEM -> sysDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /**
     * 从 `Resources.configuration.uiMode` 读系统亮暗。
     * 悬浮窗（[com.tg.dbisland.ReplyOverlay]）与 `BridgeApp` 都需要这一份判断，
     * 所以放在这里当**唯一实现**，避免两处各写一遍而漂移。
     */
    fun nightByConfig(ctx: Context): Boolean {
        val ui = runCatching { ctx.resources.configuration.uiMode }.getOrDefault(0)
        return (ui and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 启动时调一次（`BridgeApp.onCreate`）：把用户上次选的主题读进来，
     * 再按系统当前值算出首帧该用的亮暗。**必须在 setContent 之前**，
     * 否则会先画一帧错的再跳。
     */
    fun init(ctx: Context) {
        val saved = runCatching {
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getString(PREF_THEME, null)
        }.getOrNull()
        mode = ThemeMode.of(saved)
        systemDark = nightByConfig(ctx)
        recompute()
    }

    /** 只更新「系统当前亮暗」。跟随系统时会立刻生效；固定浅/深色时不改变外观。 */
    fun applySystemDark(v: Boolean) {
        if (systemDark != v) systemDark = v
        recompute()
    }

    /**
     * 用户在设置页选了一档：**先写 prefs（下次启动保持），再改内存状态**。
     * 改内存状态就是改 snapshot state → 全 App 立刻重组，不需要重启。
     */
    fun setMode(ctx: Context, m: ThemeMode) {
        mode = m
        runCatching {
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .edit().putString(PREF_THEME, m.id).apply()
        }
        // 固定浅/深色时，系统值仍然记着（切回「跟随系统」能立刻用上正确值）
        systemDark = nightByConfig(ctx)
        recompute()
    }

    /** 给非 Compose 窗口（悬浮窗）用：**prefs + 系统值**算这次该不该深色。 */
    fun resolveFor(ctx: Context): Boolean {
        val saved = runCatching {
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getString(PREF_THEME, null)
        }.getOrNull()
        return resolve(ThemeMode.of(saved), nightByConfig(ctx))
    }

    private fun recompute() {
        val v = resolve(mode, systemDark)
        if (dark != v) dark = v
    }

    val bg0: Color get() = if (dark) Color(0xFF0B0D12) else Color(0xFFF1F4FA)
    val bg1: Color get() = if (dark) Color(0xFF12151C) else Color(0xFFFFFFFF)

    /**
     * 页面背景渐变的**最下面那一档**（第 46 条修掉的「浅色主题底部一块黑」）。
     *
     * 根因：这一档以前在 [com.tg.dbisland.ui.AppShell] 里是**写死的常量**
     * `Color(0xFF080A0E)`（近黑），浅色下就变成页面从白一路渐变到黑 —— 底部
     * 连着导航栏一起是黑的。现在它和别的颜色一样是**亮/暗一对**：深色保持原来
     * 的近黑（观感不变），浅色给一个比 [bg0] 略深的浅灰蓝（同色系、只做层次，
     * 不是黑）。
     */
    val bgBottom: Color get() = if (dark) Color(0xFF080A0E) else Color(0xFFE7EBF5)

    val glassLine: Color get() = if (dark) Color(0x1FFFFFFF) else Color(0x14000000)
    val text: Color get() = if (dark) Color(0xFFE9ECF3) else Color(0xFF14171F)
    val textDim: Color get() = if (dark) Color(0xFF98A0B0) else Color(0xFF5C6478)
    val textFaint: Color get() = if (dark) Color(0xFF6B7383) else Color(0xFF8B93A5)
    val accent: Color get() = if (dark) Color(0xFF5B8CFF) else Color(0xFF3B6FE0)
    val accent2: Color get() = if (dark) Color(0xFF7C5CFF) else Color(0xFF6A47E8)
    val ok: Color get() = if (dark) Color(0xFF3FCE8A) else Color(0xFF0E9E5B)
    val warn: Color get() = if (dark) Color(0xFFFFB020) else Color(0xFFC97A00)
    val bad: Color get() = if (dark) Color(0xFFFF5C5C) else Color(0xFFD63A3A)
    /** 用户气泡（右侧）。 */
    val bubbleUser: Color get() = if (dark) Color(0xFF2A3552) else Color(0xFFD8E3FF)
    /** 豆包气泡（左侧）。 */
    val bubbleBot: Color get() = if (dark) Color(0xFF1A1E28) else Color(0xFFFFFFFF)

    /**
     * **覆盖层基色**（按钮底、选中底、行底、步进器底）。
     *
     * 第 46 条新增：以前这些地方一律是 `Color.White.copy(alpha = 0.0x)` ——
     * 深色下是「提亮一层」的正确做法，浅色下就变成「白上再铺白」，等于没有层次
     * （或者更糟：一块发灰的白）。现在统一成「深色叠白、浅色叠黑」，
     * 用法固定为 `AppColor.fill.copy(alpha = …)`。
     */
    val fill: Color get() = if (dark) Color.White else Color.Black
}

@Composable
fun IslandBridgeTheme(content: @Composable () -> Unit) {
    // 系统亮暗（`isSystemInDarkTheme()` 自己就是 Configuration 的订阅者）。
    // 注意这里交给 AppColor 的只是**系统值**：用户固定了浅色/深色时，
    // setSystemDark 不会改变最终外观。
    val sysDark = isSystemInDarkTheme()
    AppColor.applySystemDark(sysDark)
    val dark = AppColor.isDark
    val scheme = if (dark) {
        darkColorScheme(
            primary = AppColor.accent,
            onPrimary = Color.White,
            secondary = AppColor.accent2,
            background = AppColor.bg0,
            onBackground = AppColor.text,
            surface = AppColor.bg1,
            onSurface = AppColor.text,
            surfaceVariant = AppColor.bubbleBot,
            onSurfaceVariant = AppColor.textDim,
            error = AppColor.bad,
        )
    } else {
        lightColorScheme(
            primary = AppColor.accent,
            onPrimary = Color.White,
            secondary = AppColor.accent2,
            background = AppColor.bg0,
            onBackground = AppColor.text,
            surface = AppColor.bg1,
            onSurface = AppColor.text,
            surfaceVariant = AppColor.bubbleBot,
            onSurfaceVariant = AppColor.textDim,
            error = AppColor.bad,
        )
    }
    val typo = Typography(
        titleLarge = TextStyle(
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Default,
        ),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = scheme, typography = typo, content = content)
}

/** 全应用统一的圆角尺寸。 */
object AppShape {
    val card = RoundedCornerShape(20.dp)
    val cardSmall = RoundedCornerShape(14.dp)
    val dock = RoundedCornerShape(26.dp)
    val bubble = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(50)
}
