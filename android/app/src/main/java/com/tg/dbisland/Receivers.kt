package com.tg.dbisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Woken by explicit keep-alive broadcasts from the LSPosed module
 *  (DoubaoHookEntry pings while com.larus.nova runs, and periodically from
 *  system_server). Wakes the foreground service so the process (and the
 *  island session) survives while Doubao is being kept alive. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // 保活广播只接受可信来源（豆包进程 / system_server / root / 自己）。
        // 第三方伪造这里最多让服务多活一次，但仍按最小权限拒掉。
        if (!BridgeSecurity.allowBroadcast(ctx, this)) return
        if (i.action == ACTION_KEEPALIVE || i.action == BridgeService.ACTION_PING) {
            // Only the copy of the module running INSIDE Doubao stamps the
            // heartbeat ("src"="doubao"). The system_server copy pings too,
            // and it keeps pinging even when Doubao is dead or the scope was
            // never checked — counting that would report "模块已生效" on a
            // module that does nothing. See EnvCheck.
            if (i.action == ACTION_KEEPALIVE &&
                i.getStringExtra("src") == "doubao") {
                EnvCheck.noteModulePing()
                // 第 36 条：界面「日志」页的运行状态卡也要这份心跳。
                // 只有豆包进程内那份算数 —— 与 EnvCheck 同一判据（见上面的注释）。
                com.tg.dbisland.ui.BridgeHub.markAlive()
            }
            BridgeService.start(ctx)
        }
    }

    companion object {
        const val ACTION_KEEPALIVE = "com.tg.dbisland.KEEPALIVE"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // BOOT_COMPLETED 由 system_server(uid 1000) 投递，白名单内。
        if (!BridgeSecurity.allowBroadcast(ctx, this)) return
        if (i.action == Intent.ACTION_BOOT_COMPLETED) BridgeService.start(ctx)
    }
}

/** Receives normalized events captured inside the Doubao process by the
 *  LSPosed module and feeds the island pipeline.
 *
 *  授权：exported 是必需的（发送方是豆包进程 / system_server，签名与本应用
 *  不同），所以改为在 onReceive 里按 uid 白名单校验发送方
 *  （BridgeSecurity）。伪造事件最多污染岛卡片显示，触发不了写操作；真正的
 *  写操作（发消息 / 删会话）走签名级权限通道，见 AndroidManifest 与
 *  DoubaoHookEntry.registerCommandReceiver。
 *
 *  NOTE: this is now the *fallback* transport. Broadcasts to a frozen
 *  cached app are DEFER_BY_OPLUS'd and never flushed on thaw, so the
 *  primary path is EventProvider (a Binder txn, which unfreezes us). */
class BridgeEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!BridgeSecurity.allowBroadcast(ctx, this)) return
        // v=1: "ev" is raw JSON (sent from the Doubao process or the
        // system_server relay). v=2: "evb" is base64(JSON) — root/adb 手工重投
        // 时用的编码形式。
        val ev = when (i.getIntExtra("v", 0)) {
            1 -> i.getStringExtra("ev")
            2 -> i.getStringExtra("evb")?.let {
                try { String(android.util.Base64.decode(it,
                    android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) { null }
            }
            else -> null
        } ?: return
        android.util.Log.i("IslandBridge", "recv bcast ${i.action}")
        EventSink.submit(ev)
    }

    companion object {
        const val ACTION_EVENT = "com.tg.dbisland.EVENT"
    }
}

/** Island card blank-area tap (openIntent PendingIntent targets this):
 *  opens Doubao and ends the card —「操作过才消失」。
 *
 *  多会话（v1.2 第 32 条）起每个会话一个 PendingIntent，extra 里带会话 key
 *  （见 IslandBridge.openIntentFor），这里原样透传 → 只收掉被点的那一张卡。
 *
 *  PendingIntent 由本应用创建（FLAG_IMMUTABLE），触发时发送方 uid 是本应用；
 *  星河岛宿主代为 send 的情况也在白名单里。第三方直接发同 action 的广播会被拒。 */
class OpenDoubaoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!BridgeSecurity.allowBroadcast(ctx, this)) return
        val app = ctx.applicationContext as BridgeApp
        app.bridge.cardTapped(i.getStringExtra("cid"))
    }
}
