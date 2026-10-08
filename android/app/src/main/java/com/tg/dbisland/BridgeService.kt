package com.tg.dbisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground keep-alive service.
 *
 *  它现在只做一件事：**把本进程留在前台/活跃状态**，好让星河岛那条长连接
 *  （`IslandClient`）与豆包保活窗的 ping 不被 ColorOS 冻结。
 *
 *  历史上这个服务还负责两件**电脑端专用**的事，已随电脑端一起删除
 *  （见 CHANGELOG 第 34 条）：
 *    · 维持与电脑端的 SSE 长连接 → 没有电脑端就没有连接可保活，
 *      `INTERNET` / `ACCESS_NETWORK_STATE` 权限也不再申请；
 *    · 安装/卸载 `/data/adb` 下的 root 中继组件（局域网 `listen.sh` +
 *      事件队列 `relay.sh`）→ `assets/root` 整个目录，以及
 *      installRoot / uninstallRoot / reinstallRoot / readListenToken
 *      全部删掉，App 不再向 /data/adb 写任何东西。设备上已存在的
 *      /data/adb/modules/islandbridge 需要用户/root 管理器自行移除。
 *
 *  Started by MainActivity, BootReceiver, and by the LSPosed keep-alive pings
 *  (KeepAliveReceiver). */
class BridgeService : Service() {

    companion object {
        private const val CH = "bridge"
        private const val NID = 42
        const val ACTION_PING = "com.tg.dbisland.PING"
        const val ACTION_ACTIVITY = "com.tg.dbisland.ACTIVITY"
        private const val ACTIVE_WAKE_MS = 15_000L

        fun activity(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java).setAction(ACTION_ACTIVITY)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        @Volatile private var lastFgsFail = 0L

        /** 尽力把保活服务拉起来。
         *
         *  Android 12+ 在**后台**起前台服务会被系统拒绝
         *  （ForegroundServiceStartNotAllowedException: mAllowStartForeground
         *  false）。真机实测：每条事件都会撞一次并刷一行 W，但功能本身不受影响 ——
         *  事件和岛回调都会把进程唤醒（provider 调用 / 岛 App 回调我们的进程），
         *  删除会话、回复等上行也都在进程内直接完成，不依赖这个服务。
         *  所以这里失败后 60s 内不再重试，把日志噪声压成一条；[force] 给前台
         *  入口（MainActivity）用 —— 那时系统允许，必须真的起。 */
        fun start(ctx: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastFgsFail < 60_000L) return
            val i = Intent(ctx, BridgeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (t: Throwable) {
                lastFgsFail = now
                android.util.Log.i("IslandBridge",
                    "保活服务后台启动被系统拒绝(Android 12+ 正常): ${t.javaClass.simpleName}")
            }
        }
    }

    private var wl: PowerManager.WakeLock? = null

    /** 只在活动窗口内持有 wakelock；空闲时释放，避免服务常驻耗电。 */
    fun holdActivityWindow(durationMs: Long = ACTIVE_WAKE_MS) {
        try {
            val lock = wl ?: (getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "islandbridge:island")
                .apply { setReferenceCounted(false) }
                .also { wl = it }
            if (lock.isHeld) lock.release()
            lock.acquire(durationMs)
        } catch (_: Exception) {}
    }

    private fun releaseActivityWindow() {
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CH, "豆包岛桥", NotificationManager.IMPORTANCE_MIN))
        }
        val b: Notification.Builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH) else Notification.Builder(this)
        val n: Notification = b
            .setContentTitle("豆包岛桥运行中")
            .setContentText("保持岛上连接与豆包保活")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForegroundCompat(NID, n)
        // Wakelock is acquired only by holdActivityWindow during an active operation.
    }

    private fun startForegroundCompat(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(id, n)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_ACTIVITY) holdActivityWindow()
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    /** 服务被回收 / 用户停用时顺手收掉「回复」悬浮窗（第 45 条）——
     *  否则面板会作为一个孤儿窗口留在屏幕上没人管。 */
    override fun onDestroy() {
        ReplyOverlay.closeIfShowing()
        super.onDestroy()
    }
}
