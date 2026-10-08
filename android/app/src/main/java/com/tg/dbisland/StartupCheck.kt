package com.tg.dbisland

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 「自启动 / 后台保活」的跳转与检测（用户要求：
 * 「给模块添加导航到自启动让用户打开，然后检测是否打开自启动」）。
 *
 * ## 一、跳转（这一半是确定的）
 * 本机（ColorOS / Android 16）真机 `dumpsys package` 查出来的自启动界面是：
 * ```
 * com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity     ← 应用自启动列表
 * com.oplus.battery/com.oplus.startupapp.view.OptimizationAutoStartActivity
 * com.oplus.battery/com.oplus.startupapp.view.AssociateStartActivity     ← 关联启动
 * ```
 * 老机型上这套界面在 `com.coloros.safecenter` / `com.oplus.safecenter` 里，
 * 所以 [openAutoStart] 按「新→旧→兜底」逐个试，并把**真正打开的那个**写进日志。
 *
 * ## 二、检测（这一半只能做「行为推断」，界面里也是这么写的）
 * ColorOS **没有公开接口**能读那个开关。真机上试过的路都堵着：
 *   · `com.oplus.battery` 的 Startupprovider（authority `com.oplus.startup.provider`）
 *     对所有常见路径都回 `IllegalArgumentException: Error Uri`（不是权限拒绝，
 *     但拿不到数据）；
 *   · `/data/data/com.oplus.safecenter/databases/safe.db` 里确实有
 *     `pp_auto_start(pkg_name, allowed)` 表，但真机上是**空表**（0 行）；
 *   · `settings_global.xml` 里出现的 `com.larus.nova` 属于
 *     `global_statistics_whitelist`（统计白名单），跟自启动无关。
 * 所以这里**不去猜那个开关**，而是查「自启动要保住的东西还在不在」：
 * 有了 root 就看 `ps` 里豆包进程还在不在 + 模块心跳新不新鲜。这正是用户要的答案
 * ——「是不是我们模块被杀了」。
 */
object StartupCheck {

    enum class State { ON, MAYBE_OFF, UNKNOWN }

    data class Result(
        val state: State,
        /** 给 `KeyValueRow` 用的一行状态。 */
        val label: String,
        /** 给 `HintBox` 用的依据说明（必须写清楚是推断）。 */
        val detail: String,
        /** 后台看到的豆包进程数（-1 = 没测出来）。 */
        val doubaoProcs: Int,
    )

    /** 自启动界面候选（新 ROM 在前，老 ROM 在后）。 */
    private val PAGES = listOf(
        "com.oplus.battery" to "com.oplus.startupapp.view.StartupAppListActivity",
        "com.oplus.battery" to "com.oplus.startupapp.view.OptimizationAutoStartActivity",
        "com.oplus.battery" to "com.oplus.startupapp.view.AssociateStartActivity",
        "com.oplus.safecenter" to
            "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    )

    /**
     * 打开系统「应用自启动」页。返回真正用上的组件名（没成功返回 null，
     * 调用方可以据此提示用户走「应用详情 → 允许自启动」）。
     * 不需要 root：这是普通的显式 Activity 跳转。
     */
    fun openAutoStart(ctx: Context): String? {
        for ((pkg, cls) in PAGES) {
            val ok = runCatching {
                val i = Intent().apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // 先问一句能不能解析，避免 startActivity 抛 ActivityNotFound
                if (ctx.packageManager.resolveActivity(i, 0) == null) return@runCatching false
                ctx.startActivity(i)
                true
            }.getOrDefault(false)
            if (ok) return "$pkg/$cls"
        }
        // 兜底：至少把用户送到本应用的详情页 / 电池优化列表
        return runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Settings/APPLICATION_DETAILS_SETTINGS"
        }.getOrNull()
    }

    /**
     * **行为推断**自启动是否生效（要 root；没有 root 就如实说测不了）。
     * 必须在后台线程调用（内部 fork 一次 `su`）。
     */
    fun probe(): Result {
        val env = EnvCheck.probe(force = true)
        if (!env.root.ok) {
            return Result(
                State.UNKNOWN, "测不了（没有 root 授权）",
                "本应用要 root 才能看别人的进程。没有 root 时只有一个笨办法能自证：" +
                    "把豆包从最近任务里划掉，等十几秒，如果在豆包进程里还收得到" +
                    "心跳（岛还会更新），说明自启动+后台保活是生效的。",
                -1,
            )
        }
        // ps 里数一下豆包进程（自己拿到 uid=0 之后能看别人）
        val n = EnvCheck.sh(
            "ps -A -o NAME 2>/dev/null | grep -c '^com\\.larus\\.nova'",
        )?.trim()?.lineSequence()?.lastOrNull()?.toIntOrNull() ?: -1
        val age = EnvCheck.moduleAgeSec()
        val ageText = when {
            age == null -> "从未收到过"
            age < 60 -> "${age}秒前"
            else -> "${age / 60}分钟前"
        }
        return when {
            n <= 0 -> Result(
                State.MAYBE_OFF, "大概没生效（ps 里看不到豆包进程）",
                "ps 里没有 com.larus.nova 进程，模块心跳 $ageText。" +
                    "去打开自启动，再把豆包划掉、回来点「重新检测」。",
                n,
            )
            age != null && age <= 180 -> Result(
                State.ON, "生效中（后台有 ${n} 个豆包进程 · 心跳 $ageText）",
                "ps 有豆包进程（${n} 个）且模块心跳 $ageText —— 要保住的都还在。",
                n,
            )
            else -> Result(
                State.MAYBE_OFF, "豆包进程在，但模块心跳 $ageText",
                "进程有 ${n} 个，但心跳 $ageText（超 3 分钟算掉线）：" +
                    "可能刚被冻结，或模块没注入（查 LSPosed 作用域）。",
                n,
            )
        }
    }
}
