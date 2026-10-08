package com.tg.dbisland

import android.app.Application
import com.astraisland.sdk.EndReason
import com.astraisland.sdk.IslandCallback
import com.astraisland.sdk.IslandClient
import com.tg.dbisland.ui.BridgeHub
import com.tg.dbisland.ui.HubLogLevel
import org.json.JSONObject

class BridgeApp : Application() {
    companion object {
        /** Set on the main thread in onCreate — lets Binder-side entry points
         *  (EventProvider) reach the singleton without a Context cast chain. */
        @Volatile var instance: BridgeApp? = null
            private set
    }

    lateinit var island: IslandClient
        private set
    lateinit var bridge: IslandBridge
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 主题（第 46 条）：**首帧之前**把用户在设置页选的那一档（跟随系统 /
        // 浅色 / 深色）从 prefs 读进来，再按系统当前值算出首帧亮暗。
        // 悬浮窗面板不是 Compose 画的，它走 AppColor.resolveFor(ctx) 读**同一份**
        // 判断，所以 App 与悬浮窗的明暗永远一致。
        runCatching { com.tg.dbisland.ui.AppColor.init(this) }
        // ---- 第 41 条：先把「固化」准备好，再开始打日志 ----
        // 顺序很关键：LogStore.init 必须早于第一条 logLine，否则「启动」那几行
        // 会只进 logcat；ChatStore.init 必须早于 BridgeHub 第一次被访问
        // （BridgeHub 的 init 块会用 BridgeApp.instance 去读 filesDir/chat）。
        LogStore.init(this)
        ChatStore.init(java.io.File(filesDir, "chat"))
        LogStore.attach { msg -> logLine(msg) }
        logLine("启动 v" + runCatching {
            val p = packageManager.getPackageInfo(packageName, 0)
            "${p.versionName}(${p.longVersionCode})"
        }.getOrDefault("?"))
        // 固化自检（第 41 条）：一眼能看出「有没有在读/写磁盘、读到了多少」。
        // 日志里带 filesDir 绝对路径与聊天记录现状 —— 真机排查只靠这两行。
        logLine("固化: 日志目录=${LogStore.dirPath()}（${LogStore.shardCount()} 片 / " +
            "${LogStore.usedBytes()}B）· 聊天记录=${ChatStore.stats()}")
        logLine("上限: 日志 ${LogStore.limitBytes() / 1024 / 1024} MB（单片 " +
            "${LogStore.SHARD_LIMIT / 1024 / 1024} MB）· 聊天记录 " +
            "${ChatStore.limitBytes() / 1024 / 1024} MB（每会话最多 " +
            "${ChatStore.MAX_LINES_PER_CONV} 行）—— 超额都从最旧的删起")
        // 星河岛 SDK 0.1.0：客户端 + 回调（回调全部在主线程到达）
        island = IslandClient(this)
        island.setCallback(object : IslandCallback() {
            override fun onStateChanged(state: IslandClient.State) {
                logLine("岛状态: $state")
                BridgeHub.noteIslandState(state.toString())
                // 重新连上（岛重启 / 冷启动握手完成）后补发暂存的帧与 end
                if (state == IslandClient.State.READY &&
                    ::bridge.isInitialized) {
                    bridge.resync()
                }
            }

            override fun onAction(activityId: String, actionId: String) {
                // island action buttons (回复/删除会话)
                if (::bridge.isInitialized) bridge.onAction(activityId, actionId)
            }

            override fun onReply(activityId: String, text: String) {
                // 用户在回复输入条里发的文字 —— 由本应用负责真正发出
                // （官方语义：宿主只把文字交出来）
                noteIslandReply(activityId, text)
            }

            override fun onExpanded(activityId: String) {
                // 第 40 条：用户把卡片展开了 → 用**同一个 id** 换 MessageCard，
                // 岛上于是出现回复输入条（收起时还会换回 GenericCard 保常驻）。
                noteIslandExpanded(activityId)
            }

            override fun onCollapsed(activityId: String) {
                noteIslandCollapsed(activityId)
            }

            override fun onDismissed(activityId: String) {
                // 用户把胶囊划走。注意 0.1.0 的语义：**内容并未结束**，之后
                // 还可能被召回（新提醒 / 用户进出本应用 / 点摄像头区域）。
                // 所以这里只记日志，不动账目 —— 否则后续 chat.end 的更新会被
                // 误判成「已经消失」而丢失。
                logLine("岛收起 $activityId（内容仍在）")
            }

            override fun onEnded(activityId: String, reason: EndReason) {
                // EXPIRED: 到点自动消失 / 满 8 小时；REMOVED: 用户在星流里关掉
                // 本应用内容，或岛停止运行。这才是「真的没了」，清账目。
                logLine("岛结束 $activityId ($reason)")
                if (::bridge.isInitialized) bridge.userDismissed(activityId)
            }
        })
        bridge = IslandBridge(this, island) { msg -> logLine(msg) }
        island.connect()
        // a provider call can beat onCreate here (cold Binder wake) — replay
        // whatever EventSink had to queue, now that island/bridge exist
        EventSink.onAppReady(this)
    }

    /** 统一的日志出口：Logcat + 界面「日志」页 + **磁盘**（第 41 条）。
     *
     *  第 36 条把界面重做成 Compose 之后 [BridgeHub] 是唯一数据源，旧的
     *  `ChatStore`（给 chat.html WebView 用的平铺事件转发器）已删除 ——
     *  它没有会话维度，撑不起「左边会话框 / 右边聊天页」的新布局。
     *  （注意注释里别写 `filesDir` 加通配星号的写法：Kotlin 的块注释可以
     *  嵌套，一个「斜杠 + 星号」会把后面整段吞掉 —— 这一条是构建时踩出来的。）
     *
     *  第 41 条起同一条日志**同时**落 `filesDir/logs`（总占用 ≤ 100 MB，
     *  超出删最旧的片，写盘在后台线程，失败降级为只打 logcat）。
     *  **诚实边界**：LSPosed 模块住在豆包进程里，它没有本 App 的写入上下文，
     *  模块自己的日志**仍然只在 logcat**（`adb logcat -s IslandBridge`）。 */
    fun logLine(msg: String) {
        android.util.Log.i("IslandBridge", msg)
        BridgeHub.publishLog(HubLogLevel.INFO, msg)
        LogStore.append(HubLogLevel.INFO, msg)
    }

    /** 岛上的回复输入条交出来的文字（宿主 `onReply` / 模拟通道 `sim.reply`）
     *  —— 两个入口都走这一个函数，保证「模拟通道验证的链路」与真机一致。 */
    fun noteIslandReply(activityId: String, text: String) {
        if (::bridge.isInitialized) bridge.onReplyText(activityId, text)
    }

    /** 宿主 `onExpanded` / 模拟通道 `sim.expand` 共用的入口。 */
    fun noteIslandExpanded(activityId: String) {
        if (::bridge.isInitialized) bridge.onExpanded(activityId)
    }

    /** 宿主 `onCollapsed` / 模拟通道 `sim.collapse` 共用的入口。 */
    fun noteIslandCollapsed(activityId: String) {
        if (::bridge.isInitialized) bridge.onCollapsed(activityId)
    }

    // 事件只有一个来源：豆包进程内的 LSPosed 模块（手机侧）。
    // 电脑端（PC daemon / SSE）已从本项目分离，`owner` 那张「手机优先于电脑」
    // 的归属表随之删除 —— 没有第二来源就不需要仲裁（CHANGELOG 第 34 条）。
    fun handleEvent(o: JSONObject) {
        if (o.optString("t") == "ping") return
        // UI 数据源（第 36 条）：会话 / 消息 / 日志 / 运行状态都归它管
        // （第 41 条起它同时把消息镜像到 filesDir/chat/<cid>.jsonl）
        BridgeHub.handle(o)
        // 岛上卡片
        bridge.handle(o)
    }
}
