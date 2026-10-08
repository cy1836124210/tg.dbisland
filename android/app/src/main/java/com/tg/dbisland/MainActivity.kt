package com.tg.dbisland

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tg.dbisland.ui.AppShell
import com.tg.dbisland.ui.IslandBridgeTheme

/**
 * 主界面。
 *
 * 第 36 条把这一整个 Activity 从「LinearLayout 手搭 + chat.html WebView」
 * 换成了 **Compose**：底部 dock 三个页面（聊天 / 日志 / 设置），聊天页左边是
 * 可折叠的会话框、右边是具体聊天页。
 *
 * 这里刻意保持**很薄**：只负责
 *   1. 把窗口设成 edge-to-edge（深/浅两种主题都成立：两条系统栏都是**透明**的，
 *      背景由 Compose 画，留白交给 WindowInsets）；
 *   2. `setContent { IslandBridgeTheme { AppShell() } }`，并在亮暗变化时翻转
 *      系统栏**图标**颜色（第 46 条：设置页切主题不重建 Activity，只能靠这里）；
 *   3. 保留 debug 包专用的 `-e reply <text>` 调试入口。
 *
 * 所有「读到什么数据 / 显示成什么样」都在 [com.tg.dbisland.ui.BridgeHub] 与
 * `ui/` 下的三个页面里 —— Activity 不再持有任何业务状态（旧版把状态、调参、
 * 日志全塞在这一个文件里的做法一并去掉）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 主题（第 46 条）：用户在设置页可以固定「浅色 / 深色」，默认「跟随系统」。
        // `BridgeApp.onCreate` 已经从 prefs 读好了，这里只把**系统当前值**再报一次
        // （Activity 重建时配置可能已经变了），最终亮暗由 AppColor 算。
        com.tg.dbisland.ui.AppColor.applySystemDark(
            com.tg.dbisland.ui.AppColor.nightByConfig(this))

        // Android 15 起系统强制全面屏（edge-to-edge）。这里显式声明并存底，
        // 状态栏/导航栏留白交给 Compose 的 WindowInsets（见 AppShell）。
        //
        // **两条都设成透明**（关键是导航栏）：页面自己的背景（含底部那一档渐变）
        // 才能一直画到屏幕最下面 —— 浅色主题下底部就不会再压出一条系统黑带。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            IslandBridgeTheme {
                // 状态栏/导航栏**图标**的深浅要跟着生效亮暗走（亮底 → 深色图标）。
                // 放在 setContent 里用 LaunchedEffect(key = 亮暗) 而不是 onCreate
                // 里设一次：用户在设置页切主题时 Activity 不会重建，只有这里能
                // 立刻把图标翻过来（否则浅色页面上是白图标 = 看不见）。
                val dark = com.tg.dbisland.ui.AppColor.isDark
                LaunchedEffect(dark) {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                AppShell()
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1,
            )
        }

        // 调试入口（**只在 debuggable 包里生效**）：
        //   adb shell am start -S -n com.tg.dbisland/.MainActivity -e reply test
        // 直接走一遍「回复 → 豆包进程」的命令通道。
        // 为什么需要它：`com.tg.dbisland.SEND` 是签名级权限保护的广播，
        // `adb shell`（uid 2000）**没有**该权限，无法用 `am broadcast` 复现；
        // 从本 App 内部发起才是真实调用路径（也是 uid 归因那一环的唯一真实环境）。
        // release 包必须失效：任何应用都能 start 别人的 Activity，如果这里还认
        // `-e reply`，等于给第三方开了一条「借本 App 的名义替用户给豆包发消息」的路。
        val debuggable = (applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            intent?.getStringExtra("reply")?.takeIf { it.isNotBlank() }?.let { t ->
                android.util.Log.i("IslandBridge", "adb reply(debug only): len=${t.length}")
                window.decorView.postDelayed(
                    { (application as BridgeApp).bridge.sendReply(t) }, 1200,
                )
            }
        }
    }
}
