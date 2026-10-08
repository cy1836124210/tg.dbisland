package com.tg.dbisland

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.astraisland.sdk.ButtonStyle
import com.astraisland.sdk.BuiltinSymbol
import com.astraisland.sdk.Capsule
import com.astraisland.sdk.CapsuleTrailing
import com.astraisland.sdk.DismissPolicy
import com.astraisland.sdk.GenericCard
import com.astraisland.sdk.IslandActivity
import com.astraisland.sdk.IslandButton
import com.astraisland.sdk.IslandCard
import com.astraisland.sdk.IslandClient
import com.astraisland.sdk.IslandImage
import com.astraisland.sdk.IslandResult
import com.astraisland.sdk.LockScreenVisibility
import com.astraisland.sdk.MessageCard
import com.astraisland.sdk.Priority
import com.astraisland.sdk.ProgressCard
import com.astraisland.sdk.TextButton
import com.astraisland.sdk.ToggleButton
import org.json.JSONObject
import java.util.concurrent.Executors

/** Maps bridge events onto island content items.
 *
 *  星河岛 SDK 0.1.0（通信版本 7）。旧的 astraisland-client 接入库（协议 5/6）
 *  已被上游停止提供、新版星流不再接受其连接，因此这里整体改用 0.1.0 的
 *  类型化 API：`IslandActivity.Builder(id, Capsule, IslandCard)`。
 *
 *  plan.*  -> "plan"    ProgressCard + 胶囊进度环（下载样式）
 *  chat.*  -> "reply"   GenericCard（常驻卡片；第 45 条起形态固定不再切换）
 *    - 发送者 "手机-会话名"（显示名是「安卓包-会话名」）
 *    - 正文   回复流式滚动（卡片上最多两行，见 EXPAND_MAX 说明）
 *    - 按钮   **按状态换组（第 48 条）**：回答中 = 知道了 / 删除（两个，
 *              **没有回复**）；回答结束 = 回复 / 知道了 / 删除（三个）。
 *              回复 = 弹**本应用自己定制的悬浮窗**输入框（ReplyPanel.kt），
 *              也是**唯一**的回复入口 —— 卡片空白处点击 = 打开豆包 App。
 *    - 点卡片空白处 -> 打开豆包（openIntent）
 *    - 用户按了某一个按钮（回复 / 知道了 / 删除）之后，这条会话的推流**不再上岛**
 *      （`suppressed`）；`pushReplyItem` 开头是唯一的判据所在（第 48 条：
 *      删除后卡片**不会**被下一帧 delta 复活 —— 修的是 `chat.delta` 里
 *      「先 beginConv 兜底、后判 suppressed」的顺序问题）。
 *  chat.reply -> 回复页；**第 47 条起也做岛上兜底**：只在全量文本比岛上已累积的
 *      更长时补一帧（单向，绝不缩短），用来救「增量丢在路上 → 岛停在头部」。
 *
 *  ## 线程模型（0.1.0 起是硬性要求）
 *  SDK 的 start/update/end/endAll/listMine **会等星河岛处理完才返回**，文档
 *  明确要求在后台线程调用；在主线程直接调用会 ANR。所以所有投送都走这里的
 *  单线程 [worker]：既满足后台线程要求，又保证帧的先后次序（同一内容编号的
 *  start 一定早于 end）。回调（IslandCallback）反过来由 SDK 投到主线程。
 */
class IslandBridge(private val ctx: Context,
                   private val island: IslandClient,
                   private val log: (String) -> Unit = {}) {

    companion object {
        const val ID_PLAN = "plan"
        /** 回复卡的岛内容编号前缀 —— **按会话一份**：`reply:<cid>`。
         *
         *  旧版是全设备一个常量 `"reply"`：多会话时后到的会话会把前一张卡的
         *  账目顶掉，宿主那边也永远只可能有一张卡（没有第二个内容编号可以
         *  摆到副岛）。现在每个会话一个编号，多张卡才可能**同时**在线，
         *  宿主才会摆主岛 + 副岛（见 CHANGELOG 第 32 条）。 */
        const val ID_REPLY_PREFIX = "reply:"
        /** 会话表上限：超过就清「已收掉且已结束」的最老条目（见 pruneConvs）。 */
        const val MAX_CONVS = 8
        /** 模拟通道（filesDir/ib_sim.txt）的读取节拍。 */
        const val SIM_TICK_MS = 1_000L

        // ---- 文本上限（SDK 按 UTF-16 字符计，超长 Builder 直接抛异常）----
        const val TITLE_MAX = 128          // 标题 / 发送者 / 歌名
        const val SUBTITLE_MAX = 256       // 说明 / 歌手
        const val BODY_MAX = 4096          // 正文（消息内容）
        const val SHORT_MAX = 32           // 胶囊小标题、按钮文字等短文本
        const val CAPSULE_TEXT_MAX = 512   // 胶囊右侧文字
        const val OUTRO_MAX = 128          // 收尾文字
        /** 展开卡片正文的最大长度（推流中）。**用户要求：只显示一行，不要两行**
         *  —— 真机实测：**18 个汉字仍会折到第二行**（第二行只剩几个字），
         *  说明这一行大约只放得下 15 个字，所以按 14 取值。
         *  宽度随机型/系统字号而变 → 可用 `filesDir/ib_lyric.txt` 第 2 行实时调
         *  （见 [tunables]），不用重装。
         *  为什么宁短勿长：MessageCard 的正文不是滚动区，超长会从开头截断；
         *  而且正文跨行会让岛每帧重新测量窗口（`handleResized abandoned!`），
         *  一行则高度恒定，刷新最稳。 */
        const val EXPAND_MAX = 14
        /** 正文"一行字数"可调上限（再大一定放不下）。 */
        const val BODY_ONE_LINE_MAX = 60
        /** 胶囊右侧滚动文字的长度上限。api-doc:126 —— 整张卡片里只有「胶囊
         *  右侧文字」会滚动，所以用户要的「像歌词一样实时滚动」只能靠这里，
         *  比正文长一些（卡片正文只有两行）。 */
        const val ROLL_MAX = 160
        /** 歌词分组长度（用户方案）：胶囊右侧只有约一行宽，把整段尾巴塞进去
         *  我们只能截成「…尾部」——用户看到的那些省略号就是这里来的。
         *  改成**一组一组长出来**：组内随推流逐字流式显示，组满了换下一组，
         *  每组不超过这个长度 → 永远不需要截断，也就永远不会有「…」。 */
        const val LYRIC_GROUP = 10
        /** 分组长度可以**不重装**地实时调：`filesDir/ib_lyric.txt` 第一行写 4~40
         *  就立刻生效（3s 缓存）。胶囊右侧的实际宽度随机型/系统字号而变，真机上
         *  看着调这个最省事 —— 15 字在某些机器上仍然放不下，岛就会自己再截一个
         *  「…」，那正是用户看到的东西。 */
        const val LYRIC_GROUP_MAX = 40
        /** 换组/刷新节拍。**不能太快**：岛每次 startOrUpdate 都会重新 inflate 卡片并
         *  重新测量窗口，真机 logcat 里能直接看到
         *  `Relayout Window{AstraIsland}: req=1440x2066 / 1440x760` 反复来回
         *  加上 `handleResized abandoned!` —— 500ms 一帧会把岛刷到"看起来卡住"，
         *  直到最后那一帧才跳过去（用户报的「回复一定程度卡住」）。放慢到 1s 一帧，
         *  落后多了由播放器一次跨两组追上去，观感反而更连续。 */
        const val LYRIC_TICK_MS = 1_000L
        /** 回复结束后不再改发完整正文：卡片只有两行、读不到全文，给尾部（结论）
         *  比给开头有用。保留常量说明历史行为，仅用于日志/兼容。 */
        const val FINAL_MAX = 2000
        /** 胶囊信息区与时间戳用的状态文案 */
        const val ST_REPLYING = "回答进行"
        const val ST_DONE = "回答结束"
        const val ST_THINKING = "正在思考"
        /** 岛上回复框交出去之后的状态文案（消息卡的 subtitle/胶囊右侧）。
         *  **不动标题**（用户要求：状态只能出现在副标题/状态位）。 */
        const val ST_REPLY_SENT = "已交给豆包发送"
        /** 第 48 条：用户点了「删除」、等模块回执期间的状态文案。
         *  真机现象（用户报的）：「在豆包内的时候删除很顺畅，离开豆包后在岛上选择删除
         *  会卡一会，进入豆包之后才看见被删除了，**岛上还出现了结束回复才有的模板**」
         *  —— 因为删除期间卡片状态没变，等这一轮回答结束时它照规则换成了「回答结束」
         *  那套三按钮（带「回复」），看上去就像「点了删除，它反而让我回复」。
         *  有这一态之后：状态位显示「正在删除…」，按钮组按「非结束」处理 = **两个**
         *  （知道了 / 删除，没有回复），并触发 [armDeleteWake] 的 root 唤醒。 */
        const val ST_DELETING = "正在删除…"
        /** 看门狗：推流中超过这么久没有任何 chat.delta，就认为豆包的推流断了
         *  （最常见原因是豆包进程被 ColorOS 冻结/回收）。
         *  用户真机现象：「手动回复之后马上退出会接受不到内容，需要手动打开豆包」
         *  —— 钩子住在豆包进程里，进程没了/被冻住就没有事件，只能靠用户把豆包
         *  拉回前台恢复。这里至少要把状态说清楚，而不是让卡片静静停住。 */
        const val STALL_MS = 15_000L
        const val ST_STALLED = "内容已暂停"

        /** HANS 冻结豁免（真机取证见 DoubaoHookEntry.installHansExempt）：
         *  告诉 system_server 里的模块「这一轮回答还没结束，这段时间别冻豆包和本 App」。
         *  窗口到期自动失效 —— 不永久豁免，功耗回到系统默认。 */
        const val ACT_HANS = "com.tg.dbisland.HANS"
        const val HANS_WINDOW_MS = 60_000L
        const val HANS_RENEW_MS = 10_000L

        const val PKG_DOUBAO = "com.larus.nova"
        const val ACT_OPEN_DOUBAO = "com.tg.dbisland.OPEN_DOUBAO"
        const val ACT_DELETE = "delete"
        const val ACT_ACK = "ack"
        const val ACT_OPEN_APP = "open"
        /** 第 45 条（最终形态）：岛卡上的「回复」按钮 → **本应用自己定制的悬浮窗**
         *  （ReplyPanel.kt）。注意这与宿主自带的回复框回调 `IslandCallback.onReply`
         *  不是同一条路：那个回调只在 MessageCard 下才有（第 43/44 条已放弃）。
         *  卡片空白处点击是另一条链路 —— 宿主执行 `IslandActivity.setOpenIntent`，
         *  打开豆包 App（OpenDoubaoReceiver → [cardTapped]）。 */
        const val ACT_REPLY = "reply"

        // ---- 第 45 条留档：为什么没有「让小窗真的浮起来」这条路 ----
        // 用户要的是「用 ColorOS 自己的小窗打开豆包」，三轮实测都走不通，结论留在这里
        // （完整证据见 CHANGELOG 第 45 条），免得以后重复试：
        //  ① ColorOS 小窗 = 系统框架内部的 zoom window（`services.jar` 里的
        //     `IActivityStarterExt.startZoomWindow` / `isAllowedToStartActivityInZoom`），
        //     能触发它的客户端组件（`com.oplus.pscanvas` = OplusFlexibleWindowUI、
        //     `com.coloros.floatassistant` = 智能侧边栏）全部带签名级权限
        //     （`com.oplus.permission.safe.WINDOW/ASSISTANT`、`OPPO_COMPONENT_SAFE`）；
        //  ② ColorOS 自己用的 ActivityOptions key `android:activity.mZoomLaunchFlags`
        //     （FlexibleTaskView 里写死 = 8）普通 App 写进 bundle 后**被整份忽略**；
        //  ③ 唯一真能浮起来的是标准 Android 自由窗口（`--windowingMode 5` +
        //     `-f 0x18000000` 强制新任务，且要先 root 写
        //     `Settings.Global.enable_freeform_support=1`）—— 真机验证过（Task
        //     `mode=freeform visible=true`），但那套 AOSP 窗口被用户否掉；
        //  ④ 宿主那块盖在微信上的岛是 SystemUI 的 `STATUS_BAR_SUB_PANEL`
        //     （`NOT_FOCUSABLE`），弹不出键盘，「在岛上打字」物理上不成立。
        // 所以打字只能由本应用自己的悬浮窗承担；打开豆包 App 交给卡片点击。
        // broadcasts INTO the doubao process (module's dynamic receiver, 签名权限保护)
        const val BCAST_SEND = "com.tg.dbisland.SEND"
        const val BCAST_DELETE = "com.tg.dbisland.DELETE"
        /** 保活 ping：只把豆包进程"摸"一下，没有任何业务动作（见 KA_*）。 */
        const val BCAST_PING = "com.tg.dbisland.PING"

        // ---- 豆包后台保活窗（用户规则）----
        // 豆包**离开前台** → 保活；**有回复就持续保**（每条推流续期）；
        // **响应结束后重新计 30s**；这 30s 内始终没有新内容 → 停止保活，
        // 把豆包交回系统（ColorOS）处理。
        //
        // 计时为什么 App 也要做一份：钩子住在豆包进程里，豆包一旦被冻结，
        // 模块自己的定时器也跟着停 —— 那时"该放弃了"这件事只有**不被冻结的
        // 本 App 前台服务**说了算。所以模块只管开工/续期/上报，App 镜像同一
        // 个 30s 空闲窗，并在窗内每 3s ping 一次豆包进程（一来一回的 binder
        // 事务能让 ColorOS 冻结器把它放出来，实测 FREEZE_FIX 同机制）。
        const val KA_IDLE_MS = 20_000L
        const val KA_PING_MS = 3_000L

        // ---- 发送后的「root 唤醒」升级 ----
        // 真机现象（用户报）：豆包在后台时，在岛上/App 里提问，豆包**毫无反应**；
        // 手动打开豆包前台，它才把那条消息发出去并回答。
        // 原因：SEND 广播是投递给**豆包进程里**的模块接收器的。豆包被 ColorOS
        // 冻结/回收时，广播会被**推迟投递**（进程已死则根本没人收）——于是
        // 「发送即激活」这段代码自己先被冻住了，形成死锁。
        // 破解：由**不会被冻的一方**（App，借 root）把豆包捞起来，并由 root
        // 直接投递这条 SEND —— root 不受「后台启动限制 / Do not want to launch」
        // 约束（模块里 relay.sh 用同一依据）。
        const val SEND_WAKE_MS = 2_600L     // 等不到回执就怀疑被冻
        const val SEND_WAKE_TRIES = 2       // 最多升级两次

        /** 能拉起豆包进程的服务（与模块里的 DOUBAO_WAKE 同源）。 */
        val DOUBAO_WAKE_SVCS = arrayOf(
            "com.ss.android.message.NotifyService",
            "com.bytedance.mira.stub.p0.StubService1",
            "com.bytedance.mira.stub.p0.StubService2",
            "com.bytedance.mira.stub.p1.StubService1")
        /** 最后一次更新起多久自消。用户要求「展开时间拉满」——原来 1 分钟太短，
 *  改 10 分钟；真要收掉用户随时可以点「我知道了」。 */
    const val DISMISS_AFTER_MS = 600_000L

    /** 一分钟无操作：发状态栏通知「您的豆包消息未确认」并把岛折叠。
     *  用户要求：现状（卡片不自动结束）**保留**，额外加这一层。 */
    const val IDLE_NOTICE_MS = 60_000L
        /**
         * 等豆包进程回执的时长。
         *
         * 真机实测：模块侧走 Omni SDK 的 `sendMessageV2` + 回调，首次还要学发送
         * 模板，**1.5s 根本不够**。旧值造成的连锁反应很难查：
         *   ① 每帧都误判「豆包进程无回执」→ 回退剪贴板 → 还会 `openDoubao()`；
         *   ② 回退走的是 `dismissReply()`，它会把卡片 end 掉并把
         *      `replySuppressed` 置真 → **之后这条会话的推流全被丢掉**，
         *      用户看到的现象就是「点了一次回复，岛再也不刷新了」。
         * 现在放宽到 8s，且「只是没等到回执」不再做任何破坏性动作。
         */
        const val SEND_TIMEOUT_MS = 8_000L
        /** 第 48 条：**删除**的回执超时。比 SEND 长，因为删除多一条 root 唤醒路径
         *  （[armDeleteWake]：2.6s 起唤醒 + 三次重投，整段约 5.6s），
         *  8s 会在唤醒还没投完时就判「删除失败」。 */
        const val DELETE_TIMEOUT_MS = 14_000L

        // 第 71 条（发布清理）：`PREF_REPLY_BAR` / `useMessageCard`（通用卡 ↔
        // 消息卡对比开关）与 `useDetailsCard`（明细卡调试形态）已删除。
        // 它们是第 45 / 40 条留下的隐藏对比开关，发布版里没有任何 UI 入口，
        // 也没有代码读取 —— 卡形态现在只由会话状态决定：回答中 `GenericCard`、
        // 回答结束 `MessageCard`（见 [msgMode]）。
        //
        // 历史的 prefs 键（`island_reply_bar`、`detailsCard`）不再读写；旧版本
        // 写过的值留在 prefs 里不产生影响，所以不需要迁移。
    }

    private val main = Handler(Looper.getMainLooper())

    /** 所有投送调用的串行后台线程（见类注释的线程模型）。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "island-bridge")
    }

    // ============================================================
    //  按会话一份的回复状态
    // ============================================================
    //
    // 旧版下面这些字段（replyCid / replyBuf / replyDisp / replyTitle /
    // replyShown / replyEnded / replySuppressed / replySrc + 歌词播放器 +
    // 停滞看门狗 + 正文合帧 + 空闲计时器）全世界**只有一份**，于是「哪条
    // 会话正在回答」这件事会被后来的会话覆盖：多会话互相顶掉，宿主的副岛
    // 也永远等不到第二张卡。现在改为每个会话一份 [Conv]，岛内容编号变成
    // `reply:<cid>`，多会话可以**同时**在线。
    //
    //   旧字段（单份）                →  新位置（每会话一份）
    //   replyCid                      →  Conv.key / Conv.cid
    //   replyBuf                      →  Conv.buf
    //   replyDisp                     →  Conv.disp
    //   replyTitle / replySrc         →  Conv.title / Conv.src
    //   replyStartedAt                →  Conv.startedAt
    //   replyShown                    →  Conv.shown
    //   replySuppressed               →  Conv.suppressed
    //   replyEnded                    →  Conv.ended
    //   replyBotId                    →  Conv.botId
    //   lyricPos/lyricLast/lyricRunning →  Conv.*（+ Conv.lyricTick 一个 tick）
    //   stallWatchdog                 →  Conv.stallWatchdog
    //   pendingBody / flush           →  Conv.pendingBody / Conv.flush
    //   idleTimer                     →  Conv.idleTimer
    //
    // 主岛归属（用户确认的语义）：
    //   · 第一个收到 chat.start 的会话 = **先到者** → Priority.HIGH（主岛）；
    //   · 后来者 → Priority.DEFAULT（进副岛）；
    //   · **只有**先到者被收掉（点「我知道了」/「删除会话」/ 卡片被点 / 岛自己
    //     end）之后，按先到顺序的下一条**在线**会话才升到 HIGH —— 重新 post
    //     一次同一个编号即可（见 afterConvGone）。
    //   `convOrder` 就是那张「先到」有序表：chat.start 到达即入表，顺序即
    //   时间戳顺序（所有事件在同一处按序分发，同一毫秒也不会乱）。
    //
    // 线程：事件可能从 SSE 线程（BridgeApp.handleEvent）、主线程（provider /
    // 岛回调 / 模拟通道）到达，所以**结构性**改动（convs / convOrder）统一
    // 在 `synchronized(convs)` 里做；Conv 的字段用 @Volatile 保证可见性，
    // buf/disp 是**不可变 String**（不再用 StringBuilder 跨线程 append）。

    /** 一个会话的全部回复状态。 */
    private inner class Conv(val key: String) {
        /** 豆包真正的 conversation id（老测试事件不带 cid 时为空）。 */
        @Volatile var cid = ""
        /** 删除会话要用的 botId。 */
        @Volatile var botId = ""
        /** 会话名（卡片标题里 "-" 后面那段）。 */
        @Volatile var title = "豆包"
        /** 语义来源：手机 / 预览（显示名另见 srcWord()）。电脑端已分离，
         *  真实推流一律是「手机」；「预览」只由 App 内的调参按钮产生。 */
        @Volatile var src = "手机"
        /** 第一次收到 chat.start 的墙钟（给卡片时间戳 setPostedAt）。 */
        @Volatile var startedAt = 0L
        /** 单调序号 —— 「先到」顺序的排序键（同毫秒也稳定）。 */
        @Volatile var seq = 0L
        /** 空闲通知的通知号槽位：多条会话各发一条，互不覆盖。 */
        val slot = (slotSeq++) % 16

        /** 累积的回复正文（展示前的原文）。 */
        @Volatile var buf = ""
        /** 展示用文本：把豆包推流里的 markdown 记号去掉后的版本。
         *  豆包自己的界面会把 `**加粗**` 渲染成粗体（记号看不见），岛上只是纯
         *  文本 —— 用户看到的「实际回复没有 **，岛里却有」就是这个。歌词播放器
         *  和卡片正文都读这一份，保证两处字符下标一致（否则分组会错位）。 */
        @Volatile var disp = ""
        @Volatile var shown = false       // 本会话的卡现在应该在线
        @Volatile var ended = false       // 本会话已收到 chat.end
        /** 用户操作过（回复/删除/我知道了/点卡片）→ 本会话的推流不再上岛。 */
        @Volatile var suppressed = false
        /** 本会话的**悬浮窗回复面板**正开着（第 45 条：「悬浮窗出现，岛缩回不
         *  展开」）—— 期间岛上的这张卡已经被 `end()` 收掉，推流只更新本地状态、
         *  不再投岛，等面板关掉再 `start()` 补回。 */
        @Volatile var panelOpen = false

        /** 按字符容量投递的正文容器；buf/disp 始终保留全文。 */
        @Volatile var containerBody = ""
        @Volatile var containerPending = ""

        /** 岛内容编号：reply:<cid>（唯一标识这一张卡）。 */
        val liveId: String get() = ID_REPLY_PREFIX + key

        // ---- 歌词式播放器状态（见 LYRIC_GROUP 说明）----
        @Volatile var lyricPos = 0        // 当前组的起始下标
        @Volatile var lyricLast = ""      // 上一次送入歌词/胶囊容器的固定长度帧
        @Volatile var lyricRunning = false
        var lyricTick: Runnable? = null

        /** 推流停滞看门狗。 */
        var stallWatchdog: Runnable? = null

        /** 正文合帧（think/tool 行的节流）。 */
        @Volatile var pendingBody: String? = null
        var flush: Runnable? = null

        /** 一分钟无操作计时器。 */
        var idleTimer: Runnable? = null

        /** ---- 第 40 条：岛上的「展开态」----
         *  宿主回调 `onExpanded(reply:<cid>)` 置真、`onCollapsed` 置假。
         *  真 = 这张卡（**同一个 id**）现在应该是 MessageCard，带回复输入条。 */
        @Volatile var expanded = false

        /** 岛上回复框最近一次交出去的结果文案（null = 没交过）。
         *  只在**副标题/状态位**显示，绝不改标题（用户要求）。 */
        @Volatile var replyState: String? = null
    }

    /** key → Conv。key = cid（cid 为空的老测试事件用 "-"）。 */
    private val convs = LinkedHashMap<String, Conv>()
    /** 「先到」有序表：chat.start 到达即 append，顺序 = 时间戳顺序。 */
    private val convOrder = ArrayList<String>()
    /** 当前占主岛（Priority.HIGH）的会话 key；空 = 主岛没有我们的内容。 */
    @Volatile private var primaryCid = ""
    private var convSeq = 0L
    private var slotSeq = 0
    /** 最近一次真正上过岛的会话（App 内无 cid 的回复框用它）。 */
    @Volatile private var lastActiveKey = ""
    /** 最近一次见到的 botId（会话自己还没带 botId 时兜底）。 */
    @Volatile private var lastBotId = ""

    private var planTitle = "计划任务"
    private val convNames = HashMap<String, String>()

    // ids already started on island. 主线程（pushXxx）与投送线程（flushPending）
    // 都会读写，所以用同步包装，避免并发 HashMap 破坏结构。
    private val liveIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private var uidLogged = false
    private var lastDropLog = 0L
    private var lastMdLog = 0L
    /** 第 45 条：面板开着期间「暂不投岛」这条日志的节流（5s 一条，别刷屏）。 */
    private var lastPanelSkipLog = 0L
    /** 第 48 条：「卡片已被收掉→不再投岛」这条日志的节流（3s 一条）。 */
    private var lastSuppressSkipLog = 0L
    private var lastCardKindLog = ""      // 卡片形态变化才打一次日志
                                          // （也是「去 markdown 记号」日志节流）
    /** 上一组被宿主接受的按钮（第 45 条：同一档只打一次，防推流刷屏）。 */
    private var lastBtnSet = ""

    // ---- 歌词/正文的**全局**可调参数（与具体会话无关）----
    private var lyricCfg: Int? = null // ib_lyric.txt 第 1 行：胶囊分组长（可实时调）
    private var bodyCfg: Int? = null  // ib_lyric.txt 第 2 行：正文一行字数（可实时调）
    private var lyricCfgAt = 0L

    // ---------------- adb/root 模拟通道（filesDir/ib_sim.txt）----------------
    //
    // 只能注入**合成会话**：一行一个 JSON 事件，App 每秒读一次，走**和真实
    // 推流完全相同**的分发路径（BridgeApp.handleEvent → 本类的 handle() +
    // ChatStore.handle）：
    //
    //   {"n":1,"t":"chat.start","cid":"sim-a","mid":"ma","cname":"模拟A"}
    //   {"n":2,"t":"chat.delta","cid":"sim-a","mid":"ma","kind":"text","text":"你好"}
    //   {"n":3,"t":"chat.end","cid":"sim-a","mid":"ma"}
    //   {"n":4,"t":"sim.action","cid":"sim-a","a":"ack"}
    //                     ↑ 等价于岛上点「我知道了」（走同一个 onAction(id, act)）
    //   {"n":5,"t":"sim.expand","cid":"sim-a"}
    //                     ↑ 等价于宿主回调 onExpanded(reply:sim-a)
    //   {"n":6,"t":"sim.reply","cid":"sim-a","text":"好的"}
    //                     ↑ 等价于宿主回调 onReply(reply:sim-a, "好的")
    //   {"n":7,"t":"sim.collapse","cid":"sim-a"}
    //   {"n":8,"t":"sim.overlay","cid":"sim-a"}
    //                     ↑ 等价于**用户点岛上「回复」按钮**（onAction(id,"reply")）
    //                       → 弹本应用悬浮窗（第 45 条）
    //
    // 为什么需要它：多会话/副岛这件事只有造出**两个不同 cid 的会话**才验证得
    // 了，而真实豆包同一时刻只可能有一个会话在答；第 40 条的岛回复链路同理
    // （真手指点岛没法自动化）。`n` 是单调序号，App 用它去重。
    // 文件在 filesDir，只有本应用自己和 root/adb 能写 —— 所以 release 包
    // 也可以带这个通道，不需要 debug 包。它只影响岛上的显示与本次会话的收卡。
    //
    // 旧版的 `pc on` / `pc off` 行（模拟「电脑在线且有响应」）已随电脑端一起
    // 删除：不再有「电脑」这个来源，非 JSON 行一律忽略（CHANGELOG 第 34 条）。
    //
    // **第 41 条修掉重放坑（CHANGELOG 第 39 条）**：以前每次启动整读这个文件、
    // 去重水位只在内存，App 一重启就把旧的合成会话重新灌进来（幽灵会话占主岛）。
    // 现在**处理完即清空**：只保留最后一行的残片，投递过的行立刻从文件里删掉。
    private val simExec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "island-sim").apply { isDaemon = true }
    }
    private var simWatermark = 0L      // 已处理过的最大 n（防同一份文件被读两次）
    private var simLastLen = -1        // 上一次读到的文件长度（给不带 n 的老式行用）
    private var simPollerOn = false
    private var simClearFailed = false // 清空失败只记一次（见 clearSim）

    private fun startSimPoller() {
        synchronized(this) {
            if (simPollerOn) return
            simPollerOn = true
        }
        // 一次性自愈（第 41 条）：这个文件是 root/adb 用 su 写的，属主 root、
        // 默认 644 —— App 能读不能写，于是「处理完即清空」会失败。启动时借
        // root 把它改成 666（属主不变），之后就能自己清了。没有 root 就跳过，
        // 只靠内存水位 + 脚本侧的 chmod。
        simExec.execute { repairSimPerm() }
        simExec.scheduleWithFixedDelay({
            try { pollSim() } catch (t: Throwable) {
                log("模拟通道读取失败: ${t.javaClass.simpleName}")
            }
        }, SIM_TICK_MS, SIM_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** 见 [startSimPoller] 的说明。失败了什么都不做（不是产品功能）。 */
    private fun repairSimPerm() {
        runCatching {
            val f = java.io.File(ctx.filesDir, "ib_sim.txt")
            if (!f.isFile || f.canWrite()) return
            val out = EnvCheck.sh(
                "chmod 666 ${f.absolutePath} 2>&1; ls -l ${f.absolutePath}", 8_000L)
            log("模拟通道文件权限自愈: ${out?.trim()?.lines()?.lastOrNull() ?: "无输出"}")
        }
    }

    /** 每秒整读一次文件（很小），只处理**完整行**（以换行结尾），
     *  **处理完立刻把已投递的部分从文件里删掉**（第 41 条）。
     *
     *  为什么必须清空（CHANGELOG 第 39 条的真机复现）：以前去重水位 `n`
     *  只在内存里，App 一重启就整读文件重新投递，于是旧的合成会话变成
     *  「幽灵会话」先到、占住主岛，真实会话只能落副岛。
     *
     *  为什么清空而不是「落盘水位」：清空是**一个动作就成立**的语义
     *  （投递过 = 不再投递），不需要另存一份状态、也不会出现「水位文件
     *  与事件文件不同步」。截断只留最后一行残片，所以也不会把「正好在
     *  读盘时被 adb 追加进来的半行」吃掉。
     *
     *  去重仍靠 `n`：同一份内容被读两次（清空失败等异常路径）时不会重复投。
     *  不带 `n` 的老式行退化为「文件变长时才投一次」。 */
    private fun pollSim() {
        val f = java.io.File(ctx.filesDir, "ib_sim.txt")
        if (!f.isFile) { simLastLen = -1; return }
        val t = runCatching { f.readText() }.getOrNull() ?: return
        val grew = t.length > simLastLen
        simLastLen = t.length
        val lastNl = t.lastIndexOf('\n')
        if (lastNl < 0) return                 // 还没有一整行
        val consumed = t.substring(0, lastNl)
        var delivered = 0
        for (raw in consumed.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || !line.startsWith("{")) continue
            val o = runCatching { JSONObject(line) }.getOrNull()
            if (o == null) { log("模拟通道忽略非法 JSON（${line.length} 字符）"); continue }
            val n = o.optLong("n", 0L)
            if (n > 0L) {
                if (n <= simWatermark) continue
                simWatermark = n
            } else if (!grew) {
                continue                       // 老式行：文件没变长就不再投
            }
            delivered++
            // 投到主线程再分发：和 provider 那条路一致，也避开与歌词 tick
            // （主线程）抢同一份会话状态。
            main.post { dispatchSim(o) }
        }
        // 处理完即清空：只留最后那一段没有换行的残片（半个 JSON 行）
        clearSim(t.length, lastNl + 1)
        if (delivered > 0) log("模拟通道已投递 $delivered 条并清空文件（防重启重放，第 39 条）")
    }

    /** 把已投递的内容从模拟文件里删掉，只留 [offset] 起的残片（半行）。
     *
     *  保守写法：只有「现在读到的长度 == 刚才读的长度」时才整写（说明这一轮
     *  没有别人往里追加）；长度变了就**跳过**，下一拍再清 —— `n` 水位保证
     *  已投递的行不会因为「没清掉」而被重复投递。
     *
     *  **真机踩坑（第 41 条）**：`tools/sim_multi.py` 用 `su` 写这个文件，属主
     *  是 root、权限 644 —— App（u0_a45）读得到、**写不了**，于是每秒一条
     *  `FileNotFoundException`（Android 对「存在但没权限」也是抛这个）。
     *  现在脚本改成写完 `chmod 666`（属主仍是 root，App 能写）；这里再加一道：
     *  写不了就**只记一次**日志，不再刷屏。 */
    private fun clearSim(readLen: Int, offset: Int) {
        runCatching {
            val f = java.io.File(ctx.filesDir, "ib_sim.txt")
            if (!f.isFile) return
            val cur = f.readText()
            if (cur.length != readLen) return          // 期间被追加过，下一拍再清
            if (!f.canWrite()) {
                if (!simClearFailed) {
                    simClearFailed = true
                    log("模拟通道文件不可写（属主/权限问题），本轮不清空：" +
                        "去重水位仍在内存，重启前不会重放；" +
                        "用 tools/sim_multi.py 重写一次（会 chmod 666）即可")
                }
                return
            }
            f.writeText(cur.substring(offset.coerceIn(0, cur.length)))
        }.onFailure { t ->
            if (!simClearFailed) {
                simClearFailed = true
                log("模拟通道清空失败（只记这一次）: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    private fun dispatchSim(o: JSONObject) {
        val app = ctx.applicationContext as? BridgeApp ?: return
        val t = o.optString("t")
        val extra = when (t) {
            "sim.action" -> " act=${o.optString("a", "ack")}"
            "sim.reply" -> " 字数=${o.optString("text").length}"
            else -> " len=${o.optString("text").length}"
        }
        log("模拟通道: $t cid=${tail6(o.optString("cid"))}$extra")
        // 同一条分发路径：模块推来的帧也是直接进这里
        app.handleEvent(o)
    }

    /** 内容编号 -> 已构建好但还没投出去的内容（岛未就绪时暂存）。 */
    private val pending = LinkedHashMap<String, IslandActivity>()

    /** ids whose end() has NOT yet reached the island. */
    private val pendingEnds = HashSet<String>()

    /** 构造完成后再起模拟通道的轮询线程（此时所有字段都已就绪）。 */
    init { startSimPoller() }

    // ---------------- 按会话查表 / 主岛归属 ----------------

    /** cid → key。cid 为空（老测试事件、预览）用 "-" 当一个会话。 */
    private fun keyOf(cid: String): String = if (cid.isBlank()) "-" else cid

    private fun convOfKey(key: String, create: Boolean = false): Conv? =
        synchronized(convs) {
            var c = convs[key]
            if (c == null && create) {
                c = Conv(key)
                c.seq = convSeq++
                convs[key] = c
                // 先到顺序表：chat.start 到达即入表，顺序即时间戳顺序
                convOrder.add(key)
                pruneConvs()
            }
            c
        }

    /** cid → Conv（[create] 时新建）。 */
    private fun convOf(cid: String, create: Boolean = false): Conv? {
        val c = convOfKey(keyOf(cid), create)
        if (create && c != null && c.cid.isEmpty() && cid.isNotBlank()) c.cid = cid
        return c
    }

    /** 岛内容编号（`reply:<cid>`）→ Conv；不认的 id（如计划卡）返回 null。
     *  这就是「从 activity id 反解会话」那一步：SDK 的操作回调只给我们 id。 */
    private fun convOfId(id: String): Conv? =
        if (id.startsWith(ID_REPLY_PREFIX)) convOf(id.removePrefix(ID_REPLY_PREFIX))
        else null

    /** 会话表上限：只清「已经收掉且已结束」的最老条目，避免长期运行内存增长。 */
    private fun pruneConvs() {
        if (convs.size <= MAX_CONVS) return
        for (k in ArrayList(convOrder)) {
            if (convs.size <= MAX_CONVS) break
            val c = convs[k] ?: continue
            if (!c.shown && c.ended) {
                convs.remove(k)
                convOrder.remove(k)
                if (primaryCid == k) primaryCid = ""
            }
        }
    }

    /** 先到顺序里最靠前的**在线**会话（主岛空出时的接替者）。 */
    private fun firstOnlineConv(except: String? = null): Conv? = synchronized(convs) {
        for (k in convOrder) {
            if (k == except) continue
            val c = convs[k] ?: continue
            if (c.shown) return c
        }
        null
    }

    /** 当前占主岛的会话 key。先到者还在就一直归它（用户确认的语义）。 */
    private fun effectivePrimary(): String {
        val cur = convs[primaryCid]
        if (cur != null && cur.shown) return cur.key
        val k = firstOnlineConv()?.key ?: ""
        if (k != primaryCid) primaryCid = k
        return k
    }

    /** 卡片优先级：先到者 HIGH（主岛），后来者 DEFAULT（副岛）。 */
    private fun priorityOf(c: Conv): Priority =
        if (c.key == effectivePrimary()) Priority.HIGH else Priority.DEFAULT

    private fun prioWord(c: Conv): String =
        if (priorityOf(c) == Priority.HIGH) "high" else "default"

    /** 日志里只暴露会话号的后 6 位 —— **不打印任何回复正文**（隐私）。 */
    private fun tail6(s: String): String =
        if (s.isBlank()) "-" else if (s.length <= 6) s else s.takeLast(6)

    /** 一张卡被收掉之后的善后：若它是先到者，把下一条在线会话升到主岛。
     *
     *  语义来源（用户确认）：「主岛归最先收到内容的那条会话，直到它被收掉」。
     *  所以**只有**先到者没了才会发生接管，而且接管者只重新 post 一次自己
     *  那张卡（同一个 id → 宿主按 update 处理），优先级从 DEFAULT 变 HIGH。 */
    private fun afterConvGone(key: String) {
        if (primaryCid != key) return
        primaryCid = ""
        val next = firstOnlineConv() ?: run {
            log("主岛空出（当前没有在线的会话）")
            return
        }
        primaryCid = next.key
        log("先到者已被收掉 → 副岛升级为主岛 id=${next.liveId} " +
            "cid=${tail6(next.cid)} prio=high（重新 post 一次）")
        pushReplyItem(next, next.disp.ifBlank { next.buf },
            ended = next.ended,
            status = if (next.ended) ST_DONE else ST_REPLYING)
    }

    fun resync() {
        // 一次性记下双方 uid：命令通道走广播，而广播的「发送方 uid 归因」是这套
        // 里最容易出真机差异的一环（见 sendCmdToDoubao 的注释）。真出问题时，
        // 模块日志里的 `rejected: sender uid=` 和这一行对一下就知道是谁。
        if (!uidLogged) {
            uidLogged = true
            val doubao = try {
                ctx.packageManager.getPackageUid(PKG_DOUBAO, 0)
            } catch (_: Throwable) { -1 }
            log("uid: app=${android.os.Process.myUid()} doubao=$doubao")
        }
        // island (re)connected.
        //
        // Do NOT clear our bookkeeping here. On a cold Binder wake this runs
        // AFTER the first chat.start already arrived (the provider call starts
        // the process, the island handshake finishes a moment later), so
        // wiping replyShown/replyBuf made the eventual chat.end update a silent
        // no-op — the guard saw replyShown=false and the pill stayed on
        // screen forever with the mid-stream text.
        worker.execute {
            flushPending()
            flushPendingEnds()
        }
    }

    /** Events that arrived before the island session finished binding. */
    private fun flushPending() {
        if (!island.isReady || pending.isEmpty()) return
        val replay = ArrayList(pending.entries)
        pending.clear()
        for ((id, activity) in replay) {
            val r = startSafe(id, activity)
            if (r != null && r.accepted) {
                liveIds.add(id)
                synchronized(pendingEnds) { pendingEnds.remove(id) }
            } else log("岛补发($id) ${r ?: "异常"}")
        }
        if (replay.isNotEmpty()) log("岛就绪，补发 ${replay.size} 条")
    }

    /** Replay ends that could not be delivered earlier (see pendingEnds). */
    private fun flushPendingEnds() {
        if (!island.isReady) return
        val ids = synchronized(pendingEnds) {
            if (pendingEnds.isEmpty()) return
            val c = ArrayList(pendingEnds); pendingEnds.clear(); c
        }
        for (id in ids) {
            val r = try { island.end(id) } catch (t: Throwable) { null }
            if (r != null && r.accepted) log("岛 end($id) 补发成功")
            else {
                synchronized(pendingEnds) { pendingEnds.add(id) }
                log("岛 end($id) 重试仍失败 $r")
            }
        }
    }

    // ---------------- 事件入口 ----------------

    fun handle(o: JSONObject) {
        val evt = o.optString("t")
        // 保活 ping 的静默事件：模块和 App 只是互摸一下确认链路活着，
        // 不上岛、不打日志（每 3s 一条，否则日志面板会被它刷满）
        if (evt == "silent.ping") return
        // 保活前台服务**只在轮次边界**摸一次。
        // 真机教训：旧实现每来一帧都调 `BridgeService.activity(ctx)`，而
        // activity() 没有节流 → **每帧一次 startForegroundService（AMS 往返）**。
        // 一轮长回答几百帧就是几百次 IPC，既拖慢投递（事件因此积压后一次性冲出，
        // 表现为"突然给一大串然后卡住"）又费电。activity 窗口本身有 15s 有效期，
        // 用 chat.start / chat.end / keepalive 三个边界续它就够了。
        if (evt == "chat.start" || evt == "chat.end" || evt == "keepalive") {
            BridgeService.activity(ctx)
        }
        val cname = o.optString("cname")
        var cid = o.optString("cid")
        // 第 48 条（用户报的「我点击删除之后，我们岛还有内容在输出」在**进程被冻结/
        // 重启过**的场景里的那一半）：豆包新起一个进程时，模块最初那几帧**不带 cid**
        // （新进程还没把这条流和会话号对上，日志里能看到 `nova ctx captured` 紧跟
        // 着这些帧）。旧行为是拿空 cid 直接 `convOf("", create = true)` —— keyOf()
        // 把空 cid 映射成 "-"，于是岛上**凭空多出一张 `reply:-` 的卡**，和用户正在
        // 看的那张卡各算一条会话。真机日志（`--stop` 那一轮，豆包被 root 唤醒重启）：
        // ```
        //  删除回执：已删除 … → 主岛空出（当前没有在线的会话）
        //  +1.1s  主岛归先到者 id=reply:- cid=-   ← 一张 cid 为空的卡复活了
        //         随后 53 帧全从这张卡上出去（st=回答结束，还带着「回复」按钮）
        // ```
        // 收口：空 cid 的 chat.* 接到**最近一条还活着的会话**上（同一轮回答的尾巴，
        // 本来就属于它）；没有活会话就整帧丢掉 —— 刚删完/刚收掉的情况正是后者。
        if (cid.isBlank() && evt.startsWith("chat.")) {
            val last = lastActiveCid
            val lc = if (last.isBlank()) null else convOf(last)
            if (lc != null && lc.shown && !lc.suppressed &&
                !deadCids.contains(lc.cid)) {
                cid = last
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastCidMissLog > 3_000) {
                    lastCidMissLog = nowMs
                    log("无 cid 的 $evt → 接到最近一条会话 cid=${tail6(last)}" +
                        "（第 48 条：不再凭空建 reply:- 卡）")
                }
            } else if (last.isBlank()) {
                // 本进程还**没见过任何带 cid 的会话**（刚开机/刚注入就在推流）：
                // 这时按老行为落到 "-" 卡上，宁可多一张卡也别把第一轮回答丢了。
                // 只要见过一次真 cid，后面就不会再走这里。
                log("无 cid 的 $evt，且本进程还没见过任何会话 → 暂记在 - 号卡上" +
                    "（第 48 条，出现真 cid 后不再走这条）")
            } else {
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastCidMissLog > 3_000) {
                    lastCidMissLog = nowMs
                    log("忽略无 cid 的 $evt（最近一条会话已收掉/已删除，第 48 条）")
                }
                return
            }
        }
        if (cid.isNotBlank() && evt.startsWith("chat.")) lastActiveCid = cid
        // 学会话名（可能比 chat.start 先到，也可能后到）
        if (cname.isNotBlank() && cid.isNotBlank() && convNames[cid] != cname) {
            convNames[cid] = cname
            // 会话名不是隐私正文：整串打出来，专门给真机验收看（正文永不打印）
            log("会话名 cid=${tail6(cid)} '$cname'（${cname.length}字）来源=$evt")
        }
        // botId 按会话存（删除会话要用）；会话还不存在时先记一份全局兜底
        val botId = o.optString("botId")
        if (botId.isNotBlank()) {
            lastBotId = botId
            if (cid.isNotBlank()) convOf(cid)?.botId = botId
        }
        // 保活窗续期：**有回复就一直保**（每条 chat.* 推流都续期）；
        // chat.end 之后也走这里 —— 于是「响应结束后重新计 30s」自然成立
        if (kaArmed && (evt.startsWith("chat.") || evt.startsWith("plan.")))
            armDoubaoKeepAlive(evt, quiet = true)
        when (evt) {
            "keepalive" -> onKeepAliveEvent(o)
            // 豆包**自己**发出去的消息等不到回答（模块的看门狗，见 noteInAppSend）：
            // 最常见就是"发完立刻退出" —— 豆包在后台把推流断了。把豆包叫回来让它
            // 继续推这条回答，**不重发**（消息已经发出去了）。
            "reply.stalled" -> wakeDoubaoOnly("模块报告 8s 无任何推流")
            "chat.conv" -> {
                // 会话改名：只改对应那条会话（旧版只有一个全局 replyTitle）。
                // 名字常常**晚于** chat.start 到（手机侧的自动标题是回答完之后
                // 由服务端回填的，见模块 queryConvName）：已经上岛的卡片必须
                // **用同一个内容 id 重发一次**才会看到新标题 —— 重发 = 更新，
                // 不新增卡、不重新响铃、不影响主岛归属。
                val c = convOf(cid)
                if (c != null && cname.isNotBlank() && c.title != cname) {
                    val old = c.title
                    c.title = cname
                    log("会话标题更新 cid=${tail6(cid)} '$old' → '$cname'")
                    if (c.shown && !c.suppressed) {
                        // 第 48 条：同样走 statusWord，别把「正在删除…」/「已交给
                        // 豆包发送」这类更高优先级的状态改回「回答结束」。
                        pushReplyItem(c, c.buf, ended = c.ended,
                            status = statusWord(c, c.ended))
                    }
                }
            }
            "plan.start" -> {
                planTitle = o.optString("title").ifBlank { "计划任务" }
                pushPlan(0, 0, planTitle)
            }
            "plan.progress" -> {
                val done = o.optInt("done"); val total = o.optInt("total")
                pushPlan(done, total, o.optString("name").ifBlank { planTitle })
            }
            "plan.end" -> {
                liveIds.remove(ID_PLAN)
                endItem(ID_PLAN, o.optString("text").ifBlank { "任务完成" },
                    success = o.optBoolean("success", true))
            }
            "chat.start" -> {
                val c = convOf(cid, create = true) ?: return
                beginConv(c, cname)
                // 新一轮开始 = 接下来必须持续收到推流 → 立刻开冻结豁免窗口
                armHansExempt("chat.start")
                // 胶囊只显示状态，所以第一帧就给「正在回复」
                pushReplyItem(c, "", status = ST_REPLYING)
            }
            "chat.delta" -> {
                val c = convOf(cid, create = true) ?: return
                // 第 48 条（用户真机报的「点了删除，岛上还有内容在流式输出」）：
                // `suppressed` 必须**在**下面那个 beginConv 兜底**之前**判。
                //
                // 因果链：`dismissReply()`（删除 / 我知道了 / 点卡片都走它）的语义就是
                // 「这条会话的推流不再上岛」——把 `shown=false, suppressed=true`。
                // 旧顺序先跑 `if (!c.shown) beginConv(c, cname)`，而 beginConv 会把
                // `suppressed` 清成 false、`shown` 置真 —— 于是**紧接着的下一帧 delta
                // 就把刚收掉的卡片原地复活**并继续流式输出。真机日志（
                // `/data/adb/lspd/log/modules_*.log`）里 01:12:25.876 用户点删除 →
                // 01:12:26.384 模块 `delete ok (high) cid=716712230022402` →
                // 01:12:30~01:12:35 同一条 mid 还送来 ~40 条 `chat.delta` 并
                // `via provider` 投给 App，就是这个形状。
                //
                // 只有「回答还没结束就点删除」（会话仍在推流）才看得见：回答结束后
                // 没有 delta 了，所以这条一直没被抓到。
                if (c.suppressed) return
                // 兜底：没收到 chat.start 就直接来 delta（丢帧/手工注入）时，
                // 按一次隐式开始处理，别把内容丢掉。
                if (!c.shown) beginConv(c, cname)
                if (c.suppressed) return
                // 有推流 = 回答还在进行 → 续期冻结豁免（内部有 10s 节流）
                armHansExempt("chat.delta")
                val kind = o.optString("kind")
                val text = o.optString("text")
                if (kind == "text") {
                    // 正文累积进 buffer；上岛交给歌词播放器（分组流式，见 LYRIC_GROUP）
                    c.buf = c.buf + text
                    val oldDisp = c.disp
                    c.disp = markdownToPlain(c.buf)
                    val newText = if (c.disp.startsWith(oldDisp))
                        c.disp.substring(oldDisp.length) else c.disp
                    appendContainer(c, newText)
                    armStallWatchdog(c)     // 有数据 = 推流还活着，重置看门狗
                } else if (text.isNotBlank()) {
                    // think/tool 状态行 —— 胶囊显示思考中，正文给出提示
                    armStallWatchdog(c)
                    stageBody(c, text)
                }
            }
            "chat.async" -> convOf(cid)?.let {
                stageBody(it, "已转为后台任务，岛上继续跟进")
            }
            "chat.end" -> {
                // 不主动 end：卡片留下，等用户点「我知道了」
                // （见 buildReplyActivity 的 untilEnded 说明）
                val c = convOf(cid) ?: return
                c.ended = true
                // 收尾阶段（余量 flush + chat.reply）同样需要窗口，别在这里被冻
                armHansExempt("chat.end")
                flushContainer(c, force = true)
                cancelStallWatchdog(c)
                c.pendingBody = null
                c.flush?.let { main.removeCallbacks(it) }
                startLyric(c)           // 让播放器把剩余的分组唱完（唱完自动停）
                if (c.shown && !c.suppressed) {
                    // 结束时发完整正文，并把胶囊状态改成「回答完毕」。
                    // 第 48 条：这里必须走 `statusWord`，**不能写死 ST_DONE** ——
                    // 用户点了「删除」还在等回执时（`replyState=正在删除…`），
                    // 这一帧原来会把状态改回「回答结束」，按钮组跟着从两个换成
                    // 带「回复」的三个（用户报的「点了删除，岛上却出现了结束回复
                    // 才有的模板」）。statusWord 让 replyState 优先。
                    pushReplyItem(c, c.buf, ended = true,
                        status = statusWord(c, true))
                }
            }
            "send.result" -> onSendResult(o.optBoolean("ok"),
                o.optString("err"), o.optString("act"))
            // 仅模拟通道使用（见 simPoller）：等价于星河岛 SDK 的操作回调
            // IslandCallback.onAction("reply:<cid>", action) —— 用它就能在没有
            // 真机点击的情况下把「先到者被收掉」这条路径走完（tools/sim_multi.py）。
            "sim.action" -> {
                val a = o.optString("a", ACT_ACK)
                if (cid.isBlank()) { log("模拟通道动作缺 cid，忽略"); return }
                val id = ID_REPLY_PREFIX + keyOf(cid)
                log("模拟通道动作 $a id=$id（与岛上按钮走同一个 onAction）")
                onAction(id, a)
            }
            // ---- 第 45 条：悬浮窗回复链路的模拟入口 ----
            //   sim.overlay → onAction(reply:<cid>, ACT_REPLY)
            //   —— **与用户手指点岛上「回复」按钮调用的是同一个函数**，
            //   所以「无人值守验证」跑的就是真实那条路。
            "sim.overlay" -> {
                if (cid.isBlank()) { log("模拟通道 sim.overlay 缺 cid，忽略"); return }
                val id = ID_REPLY_PREFIX + keyOf(cid)
                log("模拟通道悬浮窗回复 id=$id（与岛上「回复」按钮同一个 onAction）")
                onAction(id, ACT_REPLY)
            }
            // ---- 第 40 条：岛回复链路的模拟（走**和宿主回调完全相同**的函数）----
            //
            //   sim.expand  → IslandCallback.onExpanded(reply:<cid>)
            //   sim.collapse→ IslandCallback.onCollapsed(reply:<cid>)
            //   sim.reply   → IslandCallback.onReply(reply:<cid>, text)
            //
            //   sim.type    → 第 48 条加的：把文本填进**当前开着的悬浮窗输入框**
            //                 （等价于用户把这段字打进去；真机上 `input text` 的
            //                 DOWN 被框架的 IME 输入阶段吃掉，自动化注入不了按键，
            //                 见 ReplyPanel.typeIntoLive 的注释）
            //
            // 这样「展开→同一 id 换 MessageCard→收到回复→已交给豆包发送」这条
            // 链路可以在没有真手指点岛的情况下完整跑一遍并留下日志。
            "sim.type" -> {
                // 第 48 条：填字进**当前开着的**悬浮窗输入框（等价于用户打字）
                val text = o.optString("text")
                val ok = ReplyOverlay.typeIntoLive(text)
                log("模拟通道填字 ${text.length}字 → 悬浮窗输入框 ok=$ok" +
                    "（第 48 条：真机 `input text` 的 DOWN 被框架吃掉，只能这样跑通打字链路）")
            }
            "sim.expand", "sim.collapse", "sim.reply" -> {
                val app = ctx.applicationContext as? BridgeApp
                if (app == null) { log("模拟通道：App 单例不可用，忽略 $evt"); return }
                if (cid.isBlank()) { log("模拟通道 $evt 缺 cid，忽略"); return }
                val id = ID_REPLY_PREFIX + keyOf(cid)
                when (evt) {
                    "sim.expand" -> {
                        log("模拟通道展开 id=$id（与宿主 onExpanded 同一个函数）")
                        app.noteIslandExpanded(id)
                    }
                    "sim.collapse" -> {
                        log("模拟通道收起 id=$id（与宿主 onCollapsed 同一个函数）")
                        app.noteIslandCollapsed(id)
                    }
                    else -> {
                        val text = o.optString("text")
                        // 隐私：正文永不打印，只有字数
                        log("模拟通道回复 id=$id ${text.length}字" +
                            "（与宿主 onReply 同一个函数）")
                        app.noteIslandReply(id, text)
                    }
                }
            }
            // 第 47 条：`chat.reply` 是这一轮回答的**全量文本**，用来给岛上兜底。
            //
            // 以前这里刻意不接入岛（「协议预留」），于是只要有一个增量丢在路上
            // （模块侧 mid 漂移把后续增量静默丢掉、传输去重、进程被冻结漏收），
            // 岛就**永远停在头部** —— 用户报的「第二次的内容只显示头部的一些输出
            // 然后不会流式显示下文」就是这个形状（现象见 CHANGELOG ### 47）。
            // 兜底是**单向**的：只在全量文本更长时补上，绝不缩短已经显示的内容，
            // 所以正常流式（两者等长）时这一支什么都不做。
            "chat.reply" -> {
                val c = convOf(cid) ?: return
                val t = o.optString("text")
                if (t.length > c.buf.length) {
                    log("回答全文 ${t.length}字 > 岛上已累积 ${c.buf.length}字 → 补全" +
                        "（第 47 条兜底） id=${c.liveId} cid=${tail6(c.cid)}")
                    c.buf = t
                    c.disp = markdownToPlain(t)
                    armStallWatchdog(c)
                    startLyric(c)
                    if (c.shown && !c.suppressed) {
                        pushReplyItem(c, c.disp, ended = c.ended,
                            status = if (c.ended) ST_DONE else null)
                    }
                }
            }
        }
    }

    /** 一条会话「开始回答」：重置本条会话的全部状态（旧版重置的是那一份全局
     *  状态 —— 多会话时会把别的会话正在推的流一起清掉，这就是主要病灶）。
     *  另外在这里决定它上主岛还是副岛。
     *
     *  来源固定是「手机」：事件只可能由豆包进程内的模块推来（电脑端已分离，
     *  见 CHANGELOG 第 34 条）。预览通道自己在 previewIsland 里改写 [Conv.src]。 */
    private fun beginConv(c: Conv, cname: String) {
        // 第 48 条：**删掉的会话不再复活**（墓碑，见 [deadCids]）。
        // 真机时序：用户点「删除」→ 回执 3.4s 后才到（豆包在后台，广播被推迟），
        // 期间卡片已被收掉，回执于是成了「忽略无主 send.result[act=DELETE ok=true]」，
        // 紧接着模块又推一帧 `chat.start` → 这里原来会把它**原样复活**，最后停在
        // 「回答结束」那套带「回复」的三按钮上 —— 用户报的「点了删除，岛上却出现
        // 了结束回复才有的模板」就是这个形状。
        if (deadCids.contains(c.cid)) {
            if (deadLogged.add(c.cid)) {
                log("会话已删除（cid=${tail6(c.cid)}）→ 残余推流不再上岛" +
                    "（第 48 条墓碑，用户报的「删了还回来」）")
            }
            c.shown = false
            c.suppressed = true
            return
        }
        val wasShown = c.shown
        c.src = "手机"
        val nm = cname.ifBlank { convNames[c.cid] ?: "" }
        if (nm.isNotBlank()) c.title = nm
        c.startedAt = System.currentTimeMillis()
        c.shown = true
        c.suppressed = false
        c.ended = false
        // 第 47 条：新的一轮回答开始时，把「上一条发出去了」那句反馈清掉。
        // 状态位优先显示 [Conv.replyState]（点完回复立刻看到反馈，这是用户要的），
        // 但它**跨轮不清**的话，第二轮从头到尾都显示「已交给豆包发送」——
        // 既看不到「回答进行」，答完也看不到「回答结束」。清掉之后顺序是
        // 已交给豆包发送（发送瞬间）→ 回答进行（本轮开始）→ 回答结束（本轮结束）。
        c.replyState = null
        c.buf = ""
        c.disp = ""
        if (c.botId.isBlank() && lastBotId.isNotBlank()) c.botId = lastBotId
        resetLyric(c)                 // 新回答：从第一组重新唱
        cancelIdleNotice(c)
        armStallWatchdog(c)
        lastActiveKey = c.key

        // ---- 主岛归属（用户确认的语义：先到者占主岛，直到它被收掉）----
        val holder = convs[effectivePrimary()]
        if (holder == null || holder.key == c.key) {
            primaryCid = c.key
            if (!wasShown || holder == null)
                log("主岛归先到者 id=${c.liveId} cid=${tail6(c.cid)} seq=${c.seq}")
        } else {
            log("新会话进副岛 id=${c.liveId} cid=${tail6(c.cid)} prio=default " +
                "（先到者 id=${holder.liveId} 仍占主岛）")
        }
    }

    /** Action-button taps on the island card arrive here via IslandCallback.
     *  `id` 就是岛内容编号 `reply:<cid>` —— 从这里反解出**是哪一个会话**，
     *  所以「删除会话 / 我知道了」只会作用在自己那张卡上（旧版全走单份
     *  replyCid，多会话时会把别人的卡收掉）。 */
    fun onAction(id: String, action: String) {
        val c = convOfId(id)
        when (action) {
            ACT_OPEN_APP -> openDoubao()
            ACT_DELETE -> {
                if (c == null) { log("岛动作:删除会话（未知内容 id=$id）"); return }
                log("岛动作:删除会话 id=${c.liveId} cid=${tail6(c.cid)} src=${c.src}")
                // 卡片只可能来自手机侧（电脑端已分离），所以删除一律交给豆包
                // 进程内的模块：真删除 + 回执 + 超时兜底。
                // 旧版这里只关卡片、IM 栈完全没动，会话其实还在。
                //
                // 第 48 条：**先把状态位改成「正在删除…」再发命令**。删除要等模块回执
                // （豆包在后台时可能几秒），期间状态位原来一直停在「回答进行/回答结束」，
                // 这一轮回答结束时还会照规则换成带「回复」的三按钮 —— 用户报的
                // 「点了删除，岛上却出现了结束回复才有的模板」就是这个。
                c.replyState = ST_DELETING
                if (c.shown) {
                    pushReplyItem(c, c.buf, ended = c.ended, status = ST_DELETING)
                }
                armPending(c, "删除失败，已取消")
                // 第 48 条：豆包在后台被冻结时，DELETE 这条广播要等它解冻才送达
                // （用户报的「离开豆包后点删除会卡一会，进入豆包才看见被删除」）。
                // 与 SEND 同源的处理：等不到回执就用 root 把豆包拉起来并重投。
                armDeleteWake(c)
                sendCmdToDoubao(Intent(BCAST_DELETE)
                    .setPackage(PKG_DOUBAO)
                    .putExtra("cid", c.cid)
                    .putExtra("botId", c.botId))
            }
            ACT_ACK -> {
                if (c == null) { log("岛动作:我知道了（未知内容 id=$id）"); return }
                log("岛动作:我知道了 id=${c.liveId} cid=${tail6(c.cid)}")
                dismissReply(c, null)
            }
            ACT_REPLY -> {
                if (c == null) { log("岛动作:回复（未知内容 id=$id）"); return }
                log("岛动作:回复 id=${c.liveId} cid=${tail6(c.cid)} " +
                    "→ 弹**本应用自己定制的悬浮窗**回复面板（第 45 条·最终形态；" +
                    "点卡片空白处才是打开豆包 App）")
                openReplyOverlay(c)
            }
        }
    }

    /** 第 45 条（用户最终决定）：「回复」按钮 = **我们自己定制的悬浮窗**。
     *
     *  用户原话：「放弃打开悬浮窗，使用我们自己定制的悬浮窗把；点击卡片的事件为
     *  打开豆包 app」。三轮试错后的结论（证据都在 CHANGELOG 第 45 条）：
     *  ① ColorOS 小窗（zoom window）是系统框架内部实现，普通 App 没有入口 ——
     *     它自己用的 `android:activity.mZoomLaunchFlags=8` 实测被整份忽略；
     *  ② 宿主那块盖在微信上的岛是 SystemUI 的 `STATUS_BAR_SUB_PANEL`（不可聚焦），
     *     弹不出键盘，所以「在岛上打字」物理上不成立；
     *  ③ 要在豆包自己的输入框里打字，就必须有一个**可聚焦**的窗口 —— 那就由本
     *     应用自己提供（本函数），文字经 [sendReply] 发回对应会话。
     *
     *  打开豆包 App 这件事挪到了**卡片空白处**（`IslandActivity.setOpenIntent` →
     *  `OpenDoubaoReceiver` → [cardTapped]），两条链路互不干扰。 */
    private fun openReplyOverlay(c: Conv) {
        if (!ReplyOverlay.canShow(ctx)) {
            log("悬浮窗回复：还没有「显示在其他应用上层」权限 → 请到本应用设置页" +
                "点「去授权」（第 45 条）")
            notifyOverlayNeeded(c)
            return
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE)
                as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            log("悬浮窗回复：锁屏状态下不弹面板（用户要求；cid=${tail6(c.cid)}）")
            return
        }
        ReplyOverlay.show(ctx, c.cid, c.title, logFn = { log(it) },
            send = { text -> sendReply(c.cid, text) },
            onShown = { retractIslandFor(c) },
            onClosed = { restoreIslandFor(c) })
        log("悬浮窗回复：面板已弹出 cid=${tail6(c.cid)}（TYPE_APPLICATION_OVERLAY，" +
            "可聚焦、不在锁屏弹、30s 无操作自动关）")
    }

    /**
     * 第 45 条（用户要求）：「**悬浮窗出现，岛缩回不展开**」。
     *
     * **官网没有「收起卡片」的接口**（`AstraIsland-Developers\sdk-0.1.0\api-doc.txt`
     * 逐条核对过）：卡片展开/收起由宿主和用户手势控制，应用侧只有
     *   · 两个**回调**：`onExpanded(activityId)` / `onCollapsed(activityId)`（只读通知）；
     *   · 两个**自动展开片刻**的开关：`setAlertOnStart` / `setAlertOnUpdate`（默认 false）；
     *   · 唯一能「把内容从岛上收掉」的调用：`IslandClient.end(id)`。
     * 所以这里走 `end()`：面板一出现就把这张卡从岛上收掉（岛缩回、绝不留着展开的
     * 大卡片压着悬浮窗），面板关掉再由 [restoreIslandFor] 用最新内容 `start()` 补回 ——
     * 全程只用官方公开接口，没有一条反射/私有调用。
     *
     * 同时把这条会话标成 `panelOpen`：期间所有推流都不投岛（[pushReplyItem] 开头
     * 那道闸），否则「更新且内容有变化」会让宿主把卡片重新展开片刻。
     */
    private fun retractIslandFor(c: Conv) {
        c.panelOpen = true
        c.expanded = false
        cancelIdleNotice(c)
        val wasLive = liveIds.remove(c.liveId) || c.shown
        c.shown = false
        log("悬浮窗出现 → 岛缩回：end(${c.liveId}) cid=${tail6(c.cid)}" +
            "（官方无收起接口，只能 end；面板关掉后再 start 补回）")
        if (!wasLive) return
        worker.execute {
            try {
                island.end(c.liveId)
            } catch (t: Throwable) {
                log("岛缩回 end(${c.liveId}) 异常 ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /** 面板关掉（发送/取消/点外面/返回键/超时）：把这张卡按**最新内容**放回岛上。 */
    private fun restoreIslandFor(c: Conv) {
        if (!c.panelOpen) return
        c.panelOpen = false
        // **账目也要一起回来**：retract 时把 c.shown 置了假，不还原的话
        // 「一分钟未确认」那条计时器会在 `if (!c.shown) return@Runnable` 处静默退出
        // （真机表现就是用户报的「一分钟后状态栏提示出问题了」），
        // 主岛归属判断（effectivePrimary）也会跟着错。
        c.shown = true
        log("悬浮窗关闭 → 岛补回：start(${c.liveId}) cid=${tail6(c.cid)} " +
            "（第 45 条：面板开着期间不投岛）")
        pushReplyItem(c, c.buf, ended = c.ended, status = statusWord(c, c.ended))
        armIdleNotice(c)   // first 标志没触发时也补一次，保证 60s 提醒还会来
    }

    /** 「要悬浮窗但没授权」时的状态栏提示：**不静默失败**。点它进本应用设置页，
     *  那里有一行「悬浮窗权限（去授权）」。 */
    private fun notifyOverlayNeeded(c: Conv) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? android.app.NotificationManager ?: return
            val chId = "islandbridge.window"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(
                    chId, "豆包小窗",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
            val pi = PendingIntent.getActivity(ctx, 3, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(ctx, chId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("悬浮窗回复还差一个权限")
                .setContentText("点这里 → 设置页「悬浮窗权限 · 去授权」，打开后" +
                    "点岛上的「回复」就会弹出输入框")
                .setStyle(android.app.Notification.BigTextStyle().bigText(
                    "本应用需要「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）才能" +
                        "弹出回复输入框。这是特殊权限，只能你自己在系统页里点开：" +
                        "点这条通知 → 设置页 → 「悬浮窗权限」那一行的「去授权」→" +
                        "允许「显示在其他应用上层」。授权后点岛上「回复」即可。" +
                        "（本次这条消息 cid=${tail6(c.cid)} 没能弹出面板。）"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(0x1B28, n)
        }.onFailure { log("发悬浮窗权限提示失败: ${it.message}") }
    }


    /** 按**会话号**回复（第 36 条新界面的聊天页用）。
     *
     *  为什么需要它：新界面右侧是「某一条会话」的聊天页，用户在那一页输入框里
     *  发的话必须回到**这条 cid 自己**；旧界面只有一个全局输入框，靠「最近一条」猜。
     *
     *  这里**不新建 Conv**（`create = false`）：找不到就说明这条会话还没被岛上
     *  认领，此时发送会被模块拒（没有对应模板），所以直接如实记一行日志并返回，
     *  不要凭空造一条会话出来。 */
    fun sendReply(cid: String, text: String) {
        if (text.isBlank()) return
        if (cid.isBlank()) { sendReply(text); return }
        val c = convOf(cid)
        if (c == null) {
            log("回复失败：会话 ${tail6(cid)} 还没有上过岛（先等它推一条内容）")
            return
        }
        sendReplyToConv(c, text)
    }

    /** App 回复页输入框（没有 cid）：发给先到者/最近一条有内容的会话。 */
    fun sendReply(text: String) {
        if (text.isBlank()) return
        val c = convs[effectivePrimary()] ?: convs[lastActiveKey]
        if (c == null) {
            log("回复失败：还没有任何会话（先收到一条 chat.start 再回复）")
            return
        }
        sendReplyToConv(c, text)
    }

    /** 悬浮窗回复面板（第 48 条起**唯一**的上行入口）/ v6 回复输入条 /
     *  App 回复页输入框 —— 把文字真正发回豆包。
     *  路由：交给豆包进程内的模块走它自己的 sendMessageV2（真发送）。
     *  模块明确报错时才回退到剪贴板+拉起豆包的老路子。 */
    private fun sendReplyToConv(c: Conv, text: String) {
        if (text.isBlank()) return
        // 隐私：只记长度，不打印回复正文
        log("回复[${c.src}-${c.title}] id=${c.liveId} cid=${tail6(c.cid)}: ${text.length}字")
        // 第 46 条：**所有**上行路径都在这个唯一漏斗里记一笔 —— 悬浮窗回复面板、
        // 宿主自带回复框、调试入口。旧版只有前两条各自记了一次，悬浮窗
        // 那条**一条都没记**（磁盘上查不到自己发出去的那句，和第 41 条「磁盘与
        // 界面一致」不符）。集中在这里既补齐悬浮窗，也不会重复记；预览卡没有
        // 真实会话，`cid="-"` 由 mirrorUser 自己跳过。
        com.tg.dbisland.ui.BridgeHub.noteUserSend(c.cid, text)
        // 第 48 条：**「把这句话交出去」这一瞬间起，状态位就是「已交给豆包发送」**，
        // 而按本轮用户规则，这一态属于「正在回答」→ 岛上按钮组只有两个
        // `[知道了, 删除]`、**没有「回复」**。
        //
        // 为什么必须放在这个唯一漏斗里：悬浮窗是第 48 条起唯一的回复入口，而
        // 它的发送顺序是 `面板 close() → onClosed → restoreIslandFor() → 再 send()`；
        // 只由宿主回调 [onReplyText] 置位的话，面板关掉后补回岛上的那一帧仍是
        // 「回答结束」、按钮组会**多出一个「回复」**（正是用户不要的状态）。
        // 放在这里三条上行路径（悬浮窗 / 宿主 / 调试）行为一致，且幂等。
        c.replyState = ST_REPLY_SENT
        if (c.shown) {
            pushReplyItem(c, c.buf, ended = c.ended, status = statusWord(c, c.ended))
        }
        pendingSendText = text
        pendingSendKey = c.key
        pendingSendCid = c.cid
        if (c.src == "预览") log("预览卡回复：没有真实会话，走最近一条会话")
        sendViaMobile(text)
    }

    /** 把命令广播（SEND / DELETE）交给豆包进程 —— **必须显式开身份共享**。
     *
     *  真机教训（v1.2 实测）：App targetSdk ≥ 34 时，Android 14+ 默认**不把发送方
     *  身份**带给接收器（`getSentFromUid()` 返回 INVALID_UID）。模块那边于是退到
     *  `Binder.getCallingUid()`，而在主线程 Handler 回调里它返回的是**接收方自己**
     *  的 uid（实测模块在豆包进程里拿到 10375 = 豆包自己），「只认本模块 App
     *  (10430)」的校验就把**合法命令全部拒掉** —— 表面现象正是「回复失败、
     *  等 8s 没回执」。开身份共享后模块能拿到真实的 10430，
     *  「签名级权限 + uid 白名单」两道闸才都真正生效。 */
    private fun sendCmdToDoubao(i: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            ctx.sendBroadcast(i, null,
                android.app.BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true).toBundle())
        } else {
            ctx.sendBroadcast(i)
        }
    }

    private fun sendViaMobile(text: String) {
        lastSendErr = ""
        // 「发送」事件激活保活窗（用户要求）：刚把消息交给豆包，回答一定会来，
        // 所以立刻开窗并开始每 3s ping 豆包进程 —— 否则用户点完回复就切走，
        // 豆包被冻住，命令和回答一起丢（就是「手动回复之后马上退出会接收不到
        // 内容」那个现象）。模块侧收到 SEND 也会自己激活一次，两边都开窗。
        armDoubaoKeepAlive("本机发送→等回答")
        // 本次动作的 id：首发 / App 重发 / root 重投共用，模块按它去重
        pendingSendId = java.util.UUID.randomUUID().toString()
        sendCmdToDoubao(Intent(BCAST_SEND)
            .setPackage(PKG_DOUBAO)
            .putExtra("text", text)
            .putExtra("cid", pendingSendCid)   // 这条会话自己的 cid
            .putExtra("id", pendingSendId))
        log("已递交豆包进程发送（cid=${tail6(pendingSendCid)}），等待回执…")
        // 等不到回执 = 豆包多半被冻结/回收（广播被推迟），交给 root 唤醒升级
        armSendWakeWatchdog(text)
        val rt = Runnable {
            pendingTimeout = null
            if (lastSendErr.isNotEmpty()) {
                // 模块**明确**报了错 —— 这时才回退剪贴板。
                log("进程内发送失败: $lastSendErr — 回退剪贴板")
                fallbackSend(pendingSendKey, pendingSendText)
            } else {
                // 只是没等到回执：不做任何破坏性动作。
                // 旧版这里无条件 fallbackSend → 剪贴板 + 拉起豆包 + dismissReply
                // （关卡片且把 replySuppressed 置真），于是后续推流全被丢 ——
                // 用户报的「点完回复后岛不再显示 / 收不到内容」正是这个。
                // 豆包的回答往往会随后到达（新 chat.start 会重置抑制位），
                // 所以这里只记一行，卡片留在岛上继续等。
                log("回复回执超时(${SEND_TIMEOUT_MS}ms)：未确认，" +
                    "卡片保留（不回退剪贴板、不关卡片）")
            }
        }
        pendingTimeout = rt
        main.postDelayed(rt, SEND_TIMEOUT_MS)
    }

    /** send.result 回执（LSPosed 钩子在豆包进程内发的）。
     *
     *  豆包有多个进程，每个都可能回一条 —— 失败**不是**终局：保留超时、
     *  记住错误，只有超时到了还没有成功才回退。成功立即结束。
     *
     *  守卫：我们只在**确实有在等回执**的时候才认这条消息。否则一条迟到的
     *  （或别的来源的）回执会把当前正在显示的卡片误关掉。
     *
     *  回执只作用在 **pendingSendKey 那条会话**上（多会话时不能收错卡）。 */
    fun onSendResult(ok: Boolean, err: String, act: String) {
        // 第 48 条：删除成功的回执**先落墓碑**，再看有没有主 —— 上面那个真机时序里
        // 回执到得比「收卡」晚（3.4s），落进「无主」分支，于是墓碑也没留下、
        // 卡片随后被 chat.start 复活。`pendingSendCid` 会被 dismissReply 清掉，
        // 所以无主分支用 [pendingDeleteCid]（删除动作自己记的，只被下一次删除覆盖）。
        val isDelete = act == BCAST_DELETE || act == ACT_DELETE
        if (ok && isDelete) {
            markDead(pendingSendCid.ifBlank { pendingDeleteCid })
        }
        if (pendingTimeout == null) {
            log("忽略无主 send.result[act=$act ok=$ok]" +
                (if (ok && isDelete) "（已记墓碑 cid=${tail6(pendingDeleteCid)}，" +
                    "第 48 条：死会话不再上岛）" else ""))
            return
        }
        val key = pendingSendKey
        cancelSendWakeWatchdog()   // 有回执了（成功或明确失败），不用再唤醒升级
        cancelDeleteWake()         // 第 48 条：删除的唤醒链同理，收到回执就收手
        if (!ok) {
            lastSendErr = err
            log("send.result[$act] 失败: $err")
            // 豆包那边**明确**拒了（本地校验 message list empty / 冷启动无模板）：
            // 模块手搓或克隆重放的请求服务器不收。这时改走**豆包自己的发送通道**
            // （导出活动 OuterShareDeliverActivity）补投 —— 已实测这条通道在
            // 进程被杀 / 模板中毒时都能发出并拿到回答。代价：豆包界面会短暂
            // 切到前台（这一投是"真发送"，还会顺手让模块抓到好模板，
            // 之后同一进程里的岛上回复就恢复走模板层、不再打扰）。
            if (err.contains("无模板") || err.contains("冷启动") ||
                err.contains("被服务器拒绝") || err.contains("没有有效消息体")) {
                Thread {
                    val ok2 = coldSendAsRoot()
                    main.post {
                        if (ok2) {
                            lastSendErr = ""
                            pendingTimeout?.let { main.removeCallbacks(it) }
                            pendingTimeout = null
                            armDoubaoKeepAlive("豆包通道补发")
                            log("模块发不出去 → 已用豆包自己的通道补发" +
                                "（会短暂显示豆包；此后同一进程恢复正常）")
                            convOfKey(key)?.let { dismissReply(it, "已发送") }
                        } else {
                            log("豆包通道补发也失败，卡片保留；打开一次豆包即可")
                        }
                    }
                }.start()
            }
            return
        }
        pendingTimeout?.let { main.removeCallbacks(it) }
        pendingTimeout = null
        val c = convOfKey(key)
        when (act) {
            BCAST_DELETE, ACT_DELETE -> {
                // 第 48 条：删除回执原来**一条日志都没有**（真机上没法判断
                // 「点了删除到底成没成」）。这里补一行，既是真机验收的锚点，
                // 也说明随后「这条会话的推流一律不再上岛」。
                if (c != null) {
                    log("删除回执：已删除 id=${c.liveId} cid=${tail6(c.cid)}" +
                        " → 收卡，本条会话的推流不再上岛（第 48 条）")
                    dismissReply(c, "已删除")
                } else log("删除回执：会话已不在（$key）")
                return
            }
        }
        log("已发送到手机豆包")
        if (c != null) dismissReply(c, "已发送")
    }

    /** 老路径兜底：复制进剪贴板并拉起豆包（粘贴即得）。 */
    private fun fallbackSend(key: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "豆包回复", text))
        } catch (t: Throwable) { log("剪贴板失败: $t") }
        openDoubao()
        convOfKey(key)?.let { dismissReply(it, "已交给应用发送") }
    }

    private var pendingTimeout: Runnable? = null
    private var pendingSendText = ""
    /** 这次上行动作属于哪条会话（key / 它的 wire cid）。 */
    private var pendingSendKey = ""
    private var pendingSendCid = ""
    private var lastSendErr = ""

    /** 第 48 条：**已经删掉的会话**（cid）墓碑。
     *
     *  单条链路的因果（用户 01:14:43 手动删除那次，App 日志原文）：
     *  ```
     *  83046 岛动作:删除会话
     *  86373 岛收起 → 86437 主岛空出（卡片被别的动作收掉了）
     *  86462 忽略无主 send.result[act=DELETE ok=true]   ← 回执 3.4s 后才到，已无主
     *  86934 主岛归先到者 id=reply:716712230022402      ← 卡片**复活**
     *  86995 st=回答结束 + 岛按钮组合被接受: [回复,知道了,删除]  ← 用户看到的那套模板
     *  ```
     *  会话在豆包那边**已经删掉**了，之后到达的任何推流都只是死会话的残余；
     *  一旦回执 ok=true 就把 cid 记进来，[beginConv] 直接拒绝复活。
     *  上限 30 条（LRU 式），不会无限增长。 */
    private val deadCids = LinkedHashSet<String>()
    private val deadLogged = HashSet<String>()

    private fun markDead(cid: String) {
        if (cid.isBlank()) return
        deadCids.add(cid)
        while (deadCids.size > 30) deadCids.remove(deadCids.first())
    }

    /** 最近一条**带 cid 的 chat.* 事件**属于哪条会话（第 48 条：空 cid 帧的归属）。 */
    private var lastActiveCid = ""
    private var lastCidMissLog = 0L

    /** Shared scaffolding for delete: if no receipt arrives within
     *  [DELETE_TIMEOUT_MS], surface `fallbackText` instead of silently pretending
     *  the operation succeeded (that is how delete "worked" before while the
     *  conversation quietly survived).
     *
     *  第 48 条把 8s 放宽到 14s：删除多了一条 root 唤醒路径（[armDeleteWake]），
     *  唤醒脚本自己就要 5~6s 才把三条重投发完。 */
    private fun armPending(c: Conv, fallbackText: String) {
        pendingTimeout?.let { main.removeCallbacks(it) }
        pendingSendKey = c.key
        pendingSendCid = c.cid
        val rt = Runnable {
            pendingTimeout = null
            val cur = convOfKey(c.key)
            if (cur != null) {
                log("删除回执超时(${DELETE_TIMEOUT_MS}ms)：未确认，" +
                    "按「$fallbackText」收卡（第 48 条：卡片不会留在岛上继续流式）")
                dismissReply(cur, fallbackText)
            } else log("删除超时：会话已不在（$fallbackText）")
        }
        pendingTimeout = rt
        main.postDelayed(rt, DELETE_TIMEOUT_MS)
    }

    /** 卡片空白处点击（经 OpenDoubaoReceiver）：打开豆包，**这一张**卡消失。
     *  `key` 由 PendingIntent 的 extra 带过来（每个会话一个 PendingIntent，
     *  见 openIntentFor）—— 旧版只有一张卡，不带也没关系，现在必须带。 */
    fun cardTapped(key: String?) {
        openDoubao()
        val c = if (key.isNullOrBlank()) null else convOfKey(key)
        if (c != null) dismissReply(c, null)
        else log("卡片点击：没带上会话号（只打开豆包，不收卡）")
    }

    /** The user swiped the card away on the island side (or it expired) —
     *  resync our bookkeeping so the next chat.start re-creates it cleanly. */
    fun userDismissed(id: String) {
        val c = convOfId(id)
        if (c != null) {
            c.pendingBody = null
            c.flush?.let { main.removeCallbacks(it) }
            c.flush = null
            cancelStallWatchdog(c)
            resetLyric(c)
            c.buf = ""
            c.disp = ""
            c.shown = false
            c.suppressed = true
            cancelIdleNotice(c)
            log("岛收起/结束 $id cid=${tail6(c.cid)}（账目已复位）")
            afterConvGone(c.key)
        }
        liveIds.remove(id)
    }

    /** Remove the reply card after an operation (tap/reply/delete).
     *  outro == null → 立即消失；否则先显示一句话再收。
     *  **只收传进来那一条会话**（多会话时不能把别人的卡一起收掉）。 */
    private fun dismissReply(c: Conv, outro: String?) {
        cancelIdleNotice(c)
        // 卡片已经收了，就不要再让 SEND/删除的超时回调把「失败」提示补上来
        // （只清属于这条会话的那个待处理动作）
        if (pendingSendKey == c.key) {
            pendingTimeout?.let { main.removeCallbacks(it) }
            pendingTimeout = null
            pendingSendKey = ""
            pendingSendCid = ""
        }
        c.pendingBody = null
        c.flush?.let { main.removeCallbacks(it) }
        c.flush = null
        cancelStallWatchdog(c)
        resetLyric(c)
        c.buf = ""
        c.disp = ""
        liveIds.remove(c.liveId)
        c.shown = false
        c.suppressed = true
        endItem(c.liveId, outro)
        afterConvGone(c.key)
    }

    // ---- 一分钟无操作：状态栏提示 + 岛折叠（事件不再常驻，但内容留在通知里）----

    /** 卡片上岛后开始计时。一分钟内用户没有任何操作（点卡片/按按钮/回复都算操作，
     *  那些路径会走 dismissReply 把计时器取消）→ 发通知 + 折叠。
     *  正在回答时先不动（等到“回答结束”再判），避免读到一半被收走。
     *  每会话一份计时器 + 各用一个通知号（多条会话各自超时互不覆盖）。 */
    private fun armIdleNotice(c: Conv) {
        cancelIdleNotice(c)
        // 第 45 条：面板开着的时候岛已经缩回去了，这时候再弹「您的豆包消息未确认」
        // 是自相矛盾的（用户正在打字）。面板关掉后 restoreIslandFor 会重新计时。
        if (c.panelOpen) return
        val rt = Runnable {
            c.idleTimer = null
            if (!c.shown) return@Runnable
            if (!c.ended) { armIdleNotice(c); return@Runnable }
            log("一分钟无操作 → 发状态栏提示「您的豆包消息未确认」+ 岛折叠 " +
                "id=${c.liveId} cid=${tail6(c.cid)}")
            notifyUnconfirmed(c)
            val cur = convOfKey(c.key)
            if (cur != null) dismissReply(cur, null)
            log("岛已收起（事件结束，内容在通知里）")          // 岛上折叠；内容在通知里，可展开
        }
        c.idleTimer = rt
        main.postDelayed(rt, IDLE_NOTICE_MS)
    }

    private fun cancelIdleNotice(c: Conv) {
        c.idleTimer?.let { main.removeCallbacks(it) }
        c.idleTimer = null
    }

    /** 状态栏通知：标题就是结论，正文放回复内容（BigText，可展开看全文）。
     *  点它回本 App（再点卡片回豆包）。 */
    private fun notifyUnconfirmed(c: Conv) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? android.app.NotificationManager ?: return
            val chId = "islandbridge.unconfirmed"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(
                    chId, "未确认的豆包消息",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = android.app.PendingIntent.getActivity(ctx, 0, open,
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val body = c.disp.ifBlank { c.buf }.ifBlank { "豆包回复已到达" }
            val n = android.app.Notification.Builder(ctx, chId)
                // 小图标必须是能做单色化的 drawable：
                // 用 App 的自适应启动图标会被系统拒（真机实测
                // Invalid notification (no valid small icon)）。
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("您的豆包消息未确认")
                .setContentText(body.take(60))
                .setStyle(android.app.Notification.BigTextStyle().bigText(body.take(BODY_MAX)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            // 每条会话一个通知号（槽位 0~15），后超时的那条不会顶掉前一条
            nm.notify(0x1B17 + c.slot, n)
        }.onFailure { log("发未确认通知失败: ${it.message}") }
    }

    /** End an island item, deferring the call when there is no live session.
     *
     *  BUG THIS FIXES (live-proven on the old SDK): end() failed while the
     *  session was still WAITING, the old code cleared its bookkeeping anyway,
     *  and the island kept rendering the stale card until it expired. Park a
     *  failed end and replay it on the next ready edge. */
    private fun endItem(id: String, outro: String?, success: Boolean = true) {
        worker.execute {
            if (!island.isReady) {
                synchronized(pending) { pending.remove(id) }
                synchronized(pendingEnds) { pendingEnds.add(id) }
                log("岛未就绪(${island.state})，暂存 end($id)")
                return@execute
            }
            val r = try {
                if (outro == null) island.end(id)
                else island.end(id,
                    com.astraisland.sdk.Outro(success, outro.take(OUTRO_MAX)))
            } catch (t: Throwable) {
                log("岛 end($id) 异常: ${t.javaClass.simpleName} ${t.message}")
                null
            }
            if (r != null && r.accepted) {
                synchronized(pendingEnds) { pendingEnds.remove(id) }
            } else {
                // 受理失败（含 NOT_CONNECTED）都排队重试：一张残留卡片
                // 比一次迟到的 end 更糟。
                synchronized(pendingEnds) { pendingEnds.add(id) }
                log("岛 end($id) = $r，已排队重试")
            }
        }
    }

    /** Card tap / 回复 button: open the Doubao app. */
    fun openDoubao() {
        try {
            val i = ctx.packageManager.getLaunchIntentForPackage(PKG_DOUBAO)
                ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            log("打开豆包失败: $t")
        }
    }

    /** 实时调参（真机宽度随机型/系统字号变，这样试最快，**不用重装**）：
     *  `filesDir/ib_lyric.txt` 第 1 行 = 胶囊分组长（4~40），
     *  第 2 行 = 正文一行字数（6~60）。3s 缓存。 */
    private fun tunables(): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        if (now - lyricCfgAt > 3_000) {
            lyricCfgAt = now
            var g: Int? = null
            var b: Int? = null
            try {
                val f = java.io.File(ctx.filesDir, "ib_lyric.txt")
                if (f.exists()) {
                    val ln = f.readText().lineSequence().toList()
                    g = ln.getOrNull(0)?.trim()?.toIntOrNull()
                        ?.coerceIn(4, LYRIC_GROUP_MAX)
                    b = ln.getOrNull(1)?.trim()?.toIntOrNull()
                        ?.coerceIn(6, BODY_ONE_LINE_MAX)
                }
            } catch (_: Throwable) {}
            lyricCfg = g
            bodyCfg = b
        }
        return (lyricCfg ?: LYRIC_GROUP) to (bodyCfg ?: EXPAND_MAX)
    }

    private fun lyricGroup(): Int = tunables().first

    /** 正文一行放得下的字数（用户要求只占一行）。 */
    private fun bodyMax(): Int = tunables().second

    /** 固定字符容器：正文增量先暂存，满容量才更新岛卡；结束时强制清空余量。 */
    private fun appendContainer(c: Conv, delta: String) {
        if (delta.isEmpty()) return
        c.containerPending += delta.replace('\n', ' ')
        flushContainer(c, force = false)
    }

    private fun flushContainer(c: Conv, force: Boolean) {
        val cap = lyricGroup()
        while (c.containerPending.length >= cap || (force && c.containerPending.isNotEmpty())) {
            val n = if (c.containerPending.length >= cap) cap else c.containerPending.length
            c.containerBody = c.containerPending.substring(0, n).trim()
            c.containerPending = c.containerPending.substring(n)
            if (c.containerBody.isNotEmpty() && c.shown && !c.suppressed)
                // 传**累积正文**（`c.disp`）而不是这 10 字分组：卡片正文要的是
                // 「最新一段的滑动窗口」（见 pushReplyItem 里 tailOf 那段注释）。
                // 真机事故：原来传 containerBody，而 pushReplyItem 又优先取
                // `c.containerBody`，于是注释里设计的滑动窗口成了死代码 ——
                // 岛上正文恒等于当前分组（10 字），`chat.end` 那帧推的完整正文
                // 也被直接丢弃，用户表现为「平时读不到、只有结束时冒一下」。
                pushReplyItem(c, c.disp.ifBlank { c.containerBody },
                    ended = c.ended, status = null)
            if (!force && c.containerPending.length < cap) break
        }
    }

    private fun lyricFrame(c: Conv): String? {
        val s = c.disp
        if (s.isEmpty()) return null
        if (c.lyricPos > s.length) c.lyricPos = maxOf(0, s.length - lyricGroup())
        val end = minOf(c.lyricPos + lyricGroup(), s.length)
        if (end <= c.lyricPos) return null
        return s.substring(c.lyricPos, end).replace('\n', ' ').trim().ifEmpty { null }
    }

    /** 当前该唱的固定长度容器内容。 */
    private fun lyricSeg(c: Conv): String? = lyricFrame(c)

    /** 起播。每条推流都调它，但节拍由 [Conv.lyricTick] 控制 —— 不再是"推流来
     *  一条就直接塞进胶囊"，所以胶囊不会疯狂刷新，也不会被整段截断。
     *  **每会话一个播放器**（旧版全局一个，多会话会互相把位置清零）。 */
    private fun startLyric(c: Conv) {
        if (c.lyricRunning) return
        c.lyricRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (!c.lyricRunning) return
                val seg = lyricSeg(c)
                if (seg != null && seg != c.lyricLast) {
                    c.lyricLast = seg
                    c.pendingBody = null        // 胶囊走歌词，别让 pending 再插一帧
                    pushReplyItem(c, c.disp, ended = c.ended, status = null)
                }
                val s = c.disp
                val group = lyricGroup()
                val backlog = s.length - c.lyricPos
                // 落后太多（6 组以上没唱完）就一次跨两组追上去
                val step = if (backlog > 6 * group) 2 else 1
                if (backlog > group) {
                    c.lyricPos += step * group
                    main.postDelayed(this, LYRIC_TICK_MS)
                } else if (!c.ended) {
                    // 还在推流：留着节拍，等新字把这一组填满
                    main.postDelayed(this, LYRIC_TICK_MS)
                } else {
                    // 推流结束且这一组已唱完 → 收工（卡片本身按 untilEnded 常驻）
                    c.lyricRunning = false
                }
            }
        }
        c.lyricTick = tick
        main.post(tick)
    }

    private fun resetLyric(c: Conv) {
        c.lyricRunning = false
        c.lyricPos = 0
        c.lyricLast = ""
        c.lyricTick?.let { main.removeCallbacks(it) }
        c.lyricTick = null
    }

    /** 豆包推流带 markdown 记号（`**加粗**`、`__`、行内 `` `code` ``）。
     *  只做**最小清理**：去掉强调记号与行内反引号，文字一律保留
     *  （不渲染成粗体是能力边界：岛的正文是纯文本，SDK 没有富文本 API）。 */
    private fun markdownToPlain(s: String): String {
        if (s.indexOf('*') < 0 && s.indexOf('_') < 0 && s.indexOf('`') < 0) return s
        return s.replace("**", "").replace("__", "").replace("`", "")
    }

    /** 调参预览（App 里那个按钮调的）：往岛上推一段样例文字，走**和真实回复完全
     *  相同**的路径（歌词分组 + 一行正文），这样用户在 App 里 −/＋ 调数字时能在
     *  岛上立刻看出正文是一行还是两行、胶囊分组放不放得下。
     *
     *  为什么需要它：卡片是**岛自己（SystemUI）渲染的**，我们的进程拿不到它的
     *  字体/内边距 —— 真机 `uiautomator dump` 也看不到岛（实测 dump 里只有豆包
     *  自己的节点），所以"一行到底放得下几个字"只能在真机上看着数行数。 */
    fun previewIsland() {
        main.post {
            val c = convOfKey("preview", create = true) ?: return@post
            c.src = "预览"
            c.title = "预览"
            c.startedAt = System.currentTimeMillis()
            c.shown = true
            c.suppressed = false
            c.ended = false
            c.buf = ""
            c.disp = ""
            resetLyric(c)
            cancelIdleNotice(c)
            if (convs[effectivePrimary()] == null) primaryCid = c.key
            pushReplyItem(c, "", status = ST_REPLYING)
            val sample = "这是一段用来量宽度的样例文字：如果正文只占一行，就说明这个字数" +
                "刚好放得下；如果折到了第二行，就把数字再调小一点。胶囊里的分组也照这个办法试。"
            var i = 0
            val feed = object : Runnable {
                override fun run() {
                    if (i >= sample.length) {
                        c.ended = true
                        startLyric(c)
                        return
                    }
                    c.buf = c.buf + sample.substring(i, minOf(i + 3, sample.length))
                    c.disp = markdownToPlain(c.buf)
                    i += 3
                    startLyric(c)
                    main.postDelayed(this, 200)
                }
            }
            main.post(feed)
        }
    }

    /** Last [max] chars, preferring to start right after a sentence/word
     *  boundary so the tail reads as whole sentences instead of mid-word.
     *
     *  Why the tail and not the head: a start/update with an already-known id
     *  is a FULL replacement, and an over-long body is truncated from the head.
     *  Streaming re-sends the item every few hundred ms, so the card would look
     *  stuck on the opening words. Keeping the payload short enough to FIT means
     *  the newest text is what is on screen. */
    /** 取"最新一段"正文。
     *
     *  [boundary] = true 时会把切点挪到标点之后（读起来是完整句子），代价是长度
     *  在 max 附近**摆动** —— 推流中每帧都换长度会让岛不停重新测量窗口
     *  （`handleResized abandoned!`），那正是"看着卡住"的来源。所以推流中用
     *  [boundary] = false：**长度恒定**、窗口高度稳定，只有收尾那一帧才断句。 */
    private fun tailOf(s0: String, max: Int, boundary: Boolean = true): String {
        val s = s0.trim()
        if (s.length <= max) return s
        var cut = s.length - max
        if (boundary) {
            val stops = charArrayOf('\n', '。', '！', '？', '；', '，',
                                    '.', '!', '?', ';', ',', ' ')
            // only nudge forward a little; never eat more than a third of the tail
            val lim = minOf(s.length - 1, cut + max / 3)
            var i = cut
            while (i < lim) {
                if (s[i] in stops) { cut = i + 1; break }
                i++
            }
        }
        // 不再补 "…"：用户明确嫌岛上到处是省略号。这里给的是"最新一段"的滑动
        // 窗口，内容随推流往后走，本身就能看出是被截过的，不需要再加省略号；
        // 而且自己补的省略号会和岛对超长正文的截断叠在一起，看起来更乱。
        return s.substring(cut)
    }

    private fun stageBody(c: Conv, line: String) {
        c.pendingBody = line.take(BODY_MAX)
        if (c.flush == null) {
            val r = Runnable {
                val body = c.pendingBody
                c.flush = null
                if (body != null) pushReplyItem(c, body)
            }
            c.flush = r
            main.postDelayed(r, 300)
        }
    }

    /** 最近一次递交 HANS 豁免窗口的时间（节流用）。 */
    private var hansArmedAt = 0L

    /** 把「这一轮回答还没结束」递交给 system_server 里的模块，请它在窗口内
     *  **不要冻结豆包与本 App**。
     *
     *  为什么必须存在：ColorOS HANS 会在豆包退到后台时冻结它（真机日志
     *  `OplusHansManager : freeze uid: 10375 com.larus.nova pids: [...]`），
     *  冻结期间豆包进程内的 Hook **一次都不会回调** —— 用户报的「发送后立刻
     *  退出就没有输出、只有在前台才拿得到内容」就是这个。而 FREEZE_FIX.md 已
     *  实测白名单/待机分桶/appops/oom_score_adj 那一套**全部无效**，所以只剩
     *  两条路：拦它的冻结决策点（这条），或不依赖它的存活。
     *
     *  为什么由 App 续期而不是豆包自己续期：豆包被冻时它的定时器也一起停了，
     *  没法自救；而 App 每收到一帧都被 Binder 事务解冻，是"确定在跑"的一方。
     *  节流 HANS_RENEW_MS（一帧一条广播会把省电又吃回去）。 */
    private fun armHansExempt(why: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - hansArmedAt < HANS_RENEW_MS) return
        hansArmedAt = now
        try {
            ctx.sendBroadcast(Intent(ACT_HANS).putExtra("ms", HANS_WINDOW_MS)
                .putExtra("why", why), null)
        } catch (t: Throwable) {
            log("HANS 豁免续期失败（$why）: ${t.javaClass.simpleName}")
        }
    }

    /** 推流看门狗：STALL_MS 内没有任何 chat.delta 就把胶囊状态改成
     *  「内容已暂停」，告诉用户为什么卡片不动了。
     *
     *  为什么会有这种情况：捕获豆包推流的 LSPosed 钩子**住在豆包进程里**
     *  （见 xposed/DoubaoHookEntry.kt），豆包进程一旦被 ColorOS 冻结/回收，
     *  或者它自己在后台把 SSE 连接断掉，就再也收不到事件 —— 用户只能把豆包
     *  拉回前台才会继续（真机现象：「手动回复之后马上退出就收不到内容，
     *  需要手动打开豆包」）。这是宿主进程的边界，不是本 App 能代收的，
     *  所以这里如实把状态显示出来，而不是让卡片静静停住。
     *  **每会话一份**：一条会话停住不该把另一条会话的卡也标成暂停。 */
    private fun armStallWatchdog(c: Conv) {
        cancelStallWatchdog(c)
        val rt = Runnable {
            c.stallWatchdog = null
            if (c.shown && !c.suppressed && !c.ended) {
                log("推流已 ${STALL_MS / 1000}s 无更新：豆包进程可能被冻结/回收，" +
                    "把豆包拉回前台才会继续 id=${c.liveId} cid=${tail6(c.cid)}")
                pushReplyItem(c, c.buf, ended = false, status = ST_STALLED)
            }
        }
        c.stallWatchdog = rt
        main.postDelayed(rt, STALL_MS)
    }

    private fun cancelStallWatchdog(c: Conv) {
        c.stallWatchdog?.let { main.removeCallbacks(it) }
        c.stallWatchdog = null
    }

    // ---------- 豆包后台保活窗（与模块镜像同一套规则）----------

    /** 空闲窗到期时刻（elapsedRealtime）。为 0 = 没有在保活。 */
    @Volatile private var kaDeadline = 0L
    private var kaPinger: Runnable? = null
    @Volatile private var kaArmed = false
    private var kaPings = 0

    /** 模块报告豆包前后台/保活状态。 */
    private fun onKeepAliveEvent(o: JSONObject) {
        when (o.optString("state")) {
            "armed", "reply" -> armDoubaoKeepAlive("模块：${o.optString("reason")}")
            "stopped" -> {
                if (kaArmed) {
                    kaArmed = false
                    stopDoubaoPing()
                    log("豆包保活结束（${o.optString("reason")}）→ 交给系统处理")
                }
            }
        }
    }

    /** 开窗/续期：KA_IDLE_MS 内没有新的推流就停手。 */
    private fun armDoubaoKeepAlive(cause: String, quiet: Boolean = false) {
        val first = !kaArmed
        kaArmed = true
        kaDeadline = android.os.SystemClock.elapsedRealtime() + KA_IDLE_MS
        if (first) {
            log("豆包退到后台 → 保活 ${KA_IDLE_MS / 1000}s（有回复会持续续期）")
            startDoubaoPing()
        } else if (!quiet) {
            log("豆包保活续期（$cause）")
        }
    }

    /** 窗内每 KA_PING_MS 摸一次豆包进程；到期就停手，交回系统。 */
    private fun startDoubaoPing() {
        kaPinger?.let { main.removeCallbacks(it) }
        kaPings = 0
        val r = object : Runnable {
            override fun run() {
                kaPinger = null
                if (!kaArmed) return
                if (android.os.SystemClock.elapsedRealtime() >= kaDeadline) {
                    kaArmed = false
                    log("豆包保活：${KA_IDLE_MS / 1000}s 没有新内容 → 放弃保活，" +
                        "交回系统处理（ping ${kaPings} 次）")
                    return
                }
                kaPings++
                // 一次广播 = 一次 binder 事务进豆包进程（RECEIVER 带 CONTROL
                // 签名权限，第三方发不进来）；模块那边只回一个"活着"。
                sendCmdToDoubao(Intent(BCAST_PING).setPackage(PKG_DOUBAO))
                kaPinger = this
                main.postDelayed(this, KA_PING_MS)
            }
        }
        kaPinger = r
        main.postDelayed(r, KA_PING_MS)
    }

    private fun stopDoubaoPing() {
        kaPinger?.let { main.removeCallbacks(it) }
        kaPinger = null
        kaDeadline = 0L
    }

    // ---------- 发送后没人接 → root 唤醒豆包并重投 ----------

    /** 本次用户动作的 id：App 首发、root 重投共用它，模块按 id 去重。 */
    private var pendingSendId = ""
    private var wakeWatchdog: Runnable? = null
    private var wakeTries = 0

    // ---- 第 48 条：删除的 root 唤醒（SEND 那套的镜像，见 armSendWakeWatchdog）----
    private var deleteWake: Runnable? = null
    private var deleteWakeTries = 0
    private var pendingDeleteCid = ""
    private var pendingDeleteBot = ""

    /** 用户报的原话：「在豆包内的时候删除会很顺畅，但是离开豆包后在岛上选择删除会卡
     *  一会，进入豆包之后才看见被删除了，岛上还出现了结束回复才有的模板」。
     *
     *  两个原因，分别对应两处修法：
     *   ① DELETE 也是一条**广播**，必须由豆包进程里的模块收到才执行。豆包退到后台
     *      被 ColorOS 冻结/回收后，广播要等它解冻才送达 —— App 只能干等（原来连
     *      「在等」都看不出来）。这里照 SEND 的做法：2.6s 没回执 → root 把豆包拉起
     *      并**重投 DELETE**（三次、1.5s 一次）。删除本身幂等：会话已经删掉时再投
     *      只会收到一条失败回执，而那条会被 onSendResult 的「无主回执」闸门丢掉。
     *   ② 期间状态位/按钮组没变 → 这一轮回答结束时按规则换成了带「回复」的三按钮。
     *      见 [ST_DELETING]。 */
    private fun armDeleteWake(c: Conv) {
        cancelDeleteWake()
        deleteWakeTries = 0
        pendingDeleteCid = c.cid
        pendingDeleteBot = c.botId
        scheduleDeleteWake()
    }

    private fun scheduleDeleteWake() {
        val rt = Runnable {
            deleteWake = null
            if (pendingTimeout == null) return@Runnable      // 已经有回执了
            if (deleteWakeTries >= SEND_WAKE_TRIES) {
                log("删除：豆包始终没接，已升级 ${deleteWakeTries} 次 root 唤醒，" +
                    "不再重试（${DELETE_TIMEOUT_MS}ms 后按失败收卡）")
                return@Runnable
            }
            deleteWakeTries++
            if (writeDeleteRequest() == null) {
                log("无法准备删除唤醒请求（内部文件写失败）")
                return@Runnable
            }
            Thread {
                val out = EnvCheck.sh(deleteWakeScript(), 15_000L)
                val ok = out != null && out.contains("IB_DEL_OK")
                main.post {
                    if (ok) {
                        log("豆包在后台没接删除（${SEND_WAKE_MS}ms 无回执）→ " +
                            "root 已拉起豆包并重投删除（第 ${deleteWakeTries} 次）")
                    } else {
                        log("删除的 root 唤醒不可用（${out?.trim()?.take(60) ?: "无输出"}）" +
                            "→ 等 ${DELETE_TIMEOUT_MS}ms 超时收卡")
                    }
                    if (pendingTimeout != null) scheduleDeleteWake()
                }
            }.start()
        }
        deleteWake = rt
        main.postDelayed(rt, SEND_WAKE_MS)
    }

    private fun cancelDeleteWake() {
        deleteWake?.let { main.removeCallbacks(it) }
        deleteWake = null
    }

    /** cid / botId 一行一个，避免在 shell 里拼。 */
    private fun writeDeleteRequest(): java.io.File? = try {
        java.io.File(ctx.filesDir, "ib_del.req")
            .also { it.writeText(pendingDeleteCid + "\n" + pendingDeleteBot) }
    } catch (t: Throwable) {
        log("写删除唤醒请求失败: ${t.javaClass.simpleName}")
        null
    }

    /** root 侧：没进程就先拉起来，然后由 root 直接投 DELETE
     *  （root 发送方不受后台启动限制；模块的 allowCommandSender 明确放行 uid 0）。 */
    private fun deleteWakeScript(): String {
        val svcs = DOUBAO_WAKE_SVCS.joinToString(" ") { "com.larus.nova/$it" }
        return """
            F=/data/data/com.tg.dbisland/files/ib_del.req
            [ -f "${'$'}F" ] || { echo IB_DEL_NOFILE; exit 2; }
            CID=${'$'}(sed -n 1p "${'$'}F")
            BOT=${'$'}(sed -n 2p "${'$'}F")
            [ -n "${'$'}CID" ] || { echo IB_DEL_NOCID; exit 2; }
            if ! pidof com.larus.nova >/dev/null 2>&1; then
              for S in $svcs; do
                am start-service -n ${'$'}S >/dev/null 2>&1
                sleep 0.4
                pidof com.larus.nova >/dev/null 2>&1 && break
              done
            fi
            sleep 0.6
            for N in 1 2 3; do
              am broadcast -a com.tg.dbisland.DELETE --es cid "${'$'}CID" \
                --es botId "${'$'}BOT" >/dev/null 2>&1
              sleep 1.5
            done
            echo IB_DEL_OK
        """.trimIndent()
    }

    private fun armSendWakeWatchdog(text: String) {
        cancelSendWakeWatchdog()
        wakeTries = 0
        scheduleSendWake(text)
    }

    private fun scheduleSendWake(text: String) {
        val rt = Runnable {
            wakeWatchdog = null
            // 已经有回执（成功或明确失败）就不用管了
            if (pendingTimeout == null) return@Runnable
            if (wakeTries >= SEND_WAKE_TRIES) {
                log("豆包在后台始终没接：已升级 ${wakeTries} 次 root 唤醒，" +
                    "不再重试（卡片留着，回执超时也不会误关）")
                return@Runnable
            }
            wakeTries++
            val req = writeWakeRequest(text)
            if (req == null) {
                log("无法准备 root 唤醒请求（内部文件写失败）")
                return@Runnable
            }
            Thread {
                val out = EnvCheck.sh(wakeScript(), 15_000L)
                val ok = out != null && out.contains("IB_WAKE_OK")
                main.post {
                    if (!ok) {
                        // 没有 root / 被拒 → 退化成 App 自己再发一次（尽力而为）
                        log("root 唤醒不可用（${out?.trim()?.take(60) ?: "无输出"}）→ " +
                            "改由 App 重发一次")
                        resendSend(text)
                    } else {
                        log("豆包在后台没接（${SEND_WAKE_MS}ms 无回执）→ root 已拉起豆包" +
                            "并重投（第 ${wakeTries} 次）")
                        armDoubaoKeepAlive("root 唤醒后重投")
                    }
                    if (pendingTimeout != null) scheduleSendWake(text)
                }
            }.start()
        }
        wakeWatchdog = rt
        main.postDelayed(rt, SEND_WAKE_MS)
    }

    private fun cancelSendWakeWatchdog() {
        wakeWatchdog?.let { main.removeCallbacks(it) }
        wakeWatchdog = null
    }

    /** 把本次发送写进请求文件，交给 root 脚本读（避免在 shell 里拼用户文本）。
     *  cid 用**这次动作那条会话**的（多会话时不能写错别人的会话号）。 */
    private fun writeWakeRequest(text: String): java.io.File? = try {
        val f = java.io.File(ctx.filesDir, "ib_send.req")
        // 一行一个字段：id / cid / text（换行压成空格，回复通常是单行）
        f.writeText(pendingSendId + "\n" + pendingSendCid + "\n" +
            text.replace('\n', ' '))
        f
    } catch (t: Throwable) {
        log("写唤醒请求失败: ${t.javaClass.simpleName}")
        null
    }

    /** root 侧：没有进程就先拉起来，然后由 root 直接投这条 SEND。
     *  root 发送方不受「后台启动限制 / Do not want to launch」约束
     *  （模块的 relay.sh 用的是同一依据）。
     *
     *  **为什么要连投三次**（真机实测教训）：豆包是**冷启动**被拉起来时，
     *  LSPosed 注入 + 模块跑完 `cmd receiver registered` 要 1~2s；那之前投的
     *  广播**没有接收器**，直接丢了（实测日志里只有 `cmd receiver registered`，
     *  没有 `SEND recv`）。所以每隔 1.5s 投一次，共三次；三条带**同一个 id**，
     *  模块按 id 去重 → 只有第一条真正落地的那次会发送，不会重复问豆包。 */
    private fun wakeScript(): String {
        val svcs = DOUBAO_WAKE_SVCS.joinToString(" ") { "com.larus.nova/$it" }
        return """
            F=/data/data/com.tg.dbisland/files/ib_send.req
            [ -f "${'$'}F" ] || { echo IB_WAKE_NOFILE; exit 2; }
            ID=${'$'}(sed -n 1p "${'$'}F")
            CID=${'$'}(sed -n 2p "${'$'}F")
            TXT=${'$'}(sed -n 3p "${'$'}F")
            if ! pidof com.larus.nova >/dev/null 2>&1; then
              for S in $svcs; do
                am start-service -n ${'$'}S >/dev/null 2>&1
                sleep 0.4
                pidof com.larus.nova >/dev/null 2>&1 && break
              done
            fi
            sleep 0.6
            for N in 1 2 3; do
              am broadcast -a com.tg.dbisland.SEND --es text "${'$'}TXT" \
                --es cid "${'$'}CID" --es id "${'$'}ID" >/dev/null 2>&1
              sleep 1.5
            done
            echo IB_WAKE_OK
        """.trimIndent()
    }

    /** 没有 root 时的退化路径：App 自己再发一次（带同一个 id，模块会去重）。 */
    private fun resendSend(text: String) {
        sendCmdToDoubao(Intent(BCAST_SEND)
            .setPackage(PKG_DOUBAO)
            .putExtra("text", text)
            .putExtra("cid", pendingSendCid)
            .putExtra("id", pendingSendId))
    }

    /** 冷启动兜底：借豆包自己的「分享直投」活动把消息发出去。
     *  已实测：豆包进程被杀后这条通道仍能发出并拿到回答（会把豆包界面切前台，
     *  所以调用方必须先确认**息屏**）。 */
    private fun coldSendAsRoot(): Boolean {
        if (writeWakeRequest(pendingSendText) == null) return false
        val out = EnvCheck.sh("""
            F=/data/data/com.tg.dbisland/files/ib_send.req
            TXT=${'$'}(sed -n 3p "${'$'}F")
            [ -n "${'$'}TXT" ] || { echo IB_SHARE_NOTEXT; exit 2; }
            am start -a android.intent.action.SEND -t text/plain \
              --es android.intent.extra.TEXT "${'$'}TXT" \
              -n com.larus.nova/com.larus.home.impl.OuterShareDeliverActivity \
              >/dev/null 2>&1
            echo IB_SHARE_OK
        """.trimIndent(), 10_000L)
        return out != null && out.contains("IB_SHARE_OK")
    }

    /** 只把豆包**叫回来**，绝不重发。用在"豆包自己发出去的消息没等到回答"：
     *  用户发完立刻退出，豆包在后台把 SSE 断了，模块一条事件都收不到。
     *  真机验证过的解法就是用户自己做的那个动作 —— 手动打开豆包，回答立刻继续
     *  推出来。所以这里等价地把它唤回前台（息屏时只 start-service，不亮屏、不抢
     *  前台；亮屏时用 monkey 起启动页，等于帮你点了一下豆包图标）。 */
    private fun wakeDoubaoOnly(reason: String) {
        Thread {
            val out = EnvCheck.sh("""
                AWAKE=${'$'}(dumpsys power 2>/dev/null | grep -m1 mWakefulness=)
                case "${'$'}AWAKE" in
                  *Awake*) monkey -p com.larus.nova -c android.intent.category.LAUNCHER 1 \
                    >/dev/null 2>&1 ;;
                  *) for S in com.larus.nova/com.ss.android.message.NotifyService \
                       com.larus.nova/com.bytedance.mira.stub.p0.StubService1; do
                       am start-service -n ${'$'}S >/dev/null 2>&1; sleep 0.3
                     done ;;
                esac
                echo IB_WAKEONLY_OK
            """.trimIndent(), 15_000L)
            main.post {
                log("豆包自己发的消息没等到回答（$reason）→ 唤醒豆包：" +
                    "${out?.trim() ?: "失败"}（只叫回来继续推流，不重发）")
            }
        }.start()
    }

    // ---------- Doubao app icon / launch intent ----------
    /** 豆包桌面图标（Bitmap）。0.1.0 里卡片顶部的来源栏由星河岛自己写本应用的
     *  图标与名称，**卡片左侧/头像位只放内容自己的图片**，所以这里用豆包的图标
     *  当会话头像 —— 语义上正是「谁在说话」。 */
    private val doubaoIcon: Bitmap by lazy {
        try {
            drawableToBitmap(ctx.packageManager.getApplicationIcon(PKG_DOUBAO))
        } catch (t: Throwable) {
            log("取豆包图标失败: $t")
            Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && !d.bitmap.isRecycled)
            return d.bitmap
        val sz = 192
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, sz, sz)
        d.draw(c)
        return b
    }

    /** Whole-card tap -> OpenDoubaoReceiver：打开豆包并按「操作过即消失」
     *  收起卡片（直接起 Activity 拿不到回调，只能走广播）。
     *
     *  **按会话各一个 PendingIntent**：requestCode 用会话 key 的哈希，extra 里
     *  带上 key —— 接收器开完豆包后才知道该收**哪一张**卡。旧版只有一张卡，
     *  一个 requestCode=0 的全局 PendingIntent 就够了；多会话时那样做会把
     *  点的那张之外的信息丢掉（收错/收不掉）。 */
    private fun openIntentFor(c: Conv): PendingIntent {
        val i = Intent(ACT_OPEN_DOUBAO).setPackage(ctx.packageName)
            .putExtra("cid", c.key)
        return PendingIntent.getBroadcast(ctx, c.key.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * 投送一条内容。岛未就绪时按 id 暂存最新一份，就绪后补发。
     *
     * 0.1.0 的 start() 返回 [IslandResult] 而不是数字 rc，需要按枚举分支：
     *   · accepted（OK / IMAGE_REJECTED）—— 受理，但受理不等于已显示
     *     （本应用在前台时内容不显示、排位靠后时暂不显示）；
     *   · NOT_CONNECTED / INVALID —— 暂存，等下一次就绪边补发；
     *   · QUOTA_EXCEEDED（同时 3 条）/ RATE_LIMITED（>10 次/秒）—— 记录，
     *     靠 pending 的 3 条上限与 300ms 节流自然回落；
     *   · SOURCE_DISABLED —— 用户在星流里关掉了本应用的内容，记录下来避免刷屏。
     */
    private fun send(id: String, activity: IslandActivity) {
        worker.execute {
            if (!island.isReady) {
                synchronized(pending) {
                    // keep at most 3 live ids, mirroring the island's own quota
                    if (pending.size >= 3 && !pending.containsKey(id)) {
                        pending.remove(pending.keys.first())
                    }
                    pending[id] = activity
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastDropLog > 5000) {
                    lastDropLog = now
                    log("岛未就绪(${island.state})，暂存 $id")
                }
                return@execute
            }
            val r = startSafe(id, activity)
            when {
                r == null -> Unit                       // 已在 startSafe 里记日志
                r.accepted -> synchronized(pendingEnds) { pendingEnds.remove(id) }
                r == IslandResult.NOT_CONNECTED || r == IslandResult.INVALID -> {
                    synchronized(pending) { pending[id] = activity }
                    synchronized(pendingEnds) { pendingEnds.remove(id) }
                    log("岛 start($id) = $r，暂存待补发")
                }
                // 第 40 条：配额/限流**不能静默** —— 这两种结果直接决定
                // 「卡片为什么没出现」，必须留下明确的一行（含当前有多少条在岛
                // 上、上限多少），否则用户报「岛没反应」时无从查起。
                r == IslandResult.QUOTA_EXCEEDED -> log(
                    "岛 start($id) = QUOTA_EXCEEDED：本应用同时在岛上的内容已达上限" +
                        "（官方：最多 3 条）；liveIds=${liveIds.size} " +
                        "pending=${pending.size} —— 先收掉一张卡再发")
                r == IslandResult.RATE_LIMITED -> log(
                    "岛 start($id) = RATE_LIMITED：投送超过官方上限（每秒 >10 次）；" +
                        "本帧被丢弃，等下一帧（歌词节拍 1s/次，正常不会触发）")
                r == IslandResult.SOURCE_DISABLED -> log(
                    "岛 start($id) = SOURCE_DISABLED：用户在星流里关掉了本应用的内容，" +
                        "卡片不会显示（不是本应用的故障）")
                else -> log("岛 start($id) = $r")
            }
        }
    }

    /** start() 的异常兜底：0.1.0 的 Builder 在参数不合规时**立即抛
     *  IllegalArgumentException**，不接住就会打死投送线程。 */
    private fun startSafe(id: String, activity: IslandActivity): IslandResult? = try {
        island.start(activity)
    } catch (t: Throwable) {
        log("岛 start($id) 异常: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    /** 截断到 [max] 个 UTF-16 字符（SDK 的上限口径），并保证非空白。 */
    private fun cut(s: String, max: Int, fallback: String = ""): String {
        val t = s.trim()
        if (t.isEmpty()) return fallback
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }

    private fun pushPlan(done: Int, total: Int, step: String) {
        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val first = liveIds.add(ID_PLAN)
        val builder = try {
            val capsule = Capsule.Builder(IslandImage.symbol(BuiltinSymbol.DOWNLOAD))
                .setLabel(cut(planTitle, SHORT_MAX, "计划"))
                .setTrailing(CapsuleTrailing.progress(fraction))
                .build()
            val card = ProgressCard.Builder(cut(planTitle, TITLE_MAX, "计划任务"))
                .setSubtitle(cut(step, SUBTITLE_MAX))
                .setProgress(fraction)
                .addButton(TextButton(ACT_OPEN_APP, "打开豆包岛桥"))
                .build()
            IslandActivity.Builder(ID_PLAN, capsule, card)
                .setPriority(Priority.HIGH)      // 外部来源最高档
                .setAlertOnStart(first)          // 只有第一帧自动展开
                // 计划卡不设消失策略（默认 untilEnded）：与旧实现一致 ——
                // 计划可能长时间没有新的 progress 帧，按 60s 自动消失会把
                // 正在进行的任务卡弄没。收尾统一由 plan.end → endItem 负责。
                .setContentDescription(cut("豆包计划进度 ${done}/${total}", SUBTITLE_MAX,
                    "豆包计划进度"))
                .build()
        } catch (t: Throwable) {
            log("构建计划卡片失败: ${t.javaClass.simpleName} ${t.message}")
            return
        }
        send(ID_PLAN, builder)
    }

    /** 消息式卡片。
     *
     *  三个区域分别对应三种需求：
     *  - 岛外(胶囊)不展开时：信息区只写状态「安卓包·回答进行」/「安卓包·回答结束」，
     *    让用户一眼看出是谁在答、答完没有，而不必读正文。
     *  - 展开卡片后：正文走推流。MessageCard 的正文固定两行、不是滚动区，超长
     *    从开头截断。所以推流期间发的是**尾部一小段**（只占一行，见第 27 条），
     *    最后一帧才断句。
     *  - 来源字样：胶囊小标题与发送者都带来源前缀（电脑端分离后只剩「安卓包」，
     *    调参预览是「预览」）。
     *
     *  **第 40/44/45 条**：卡片形态由 [msgMode] 决定 —— 第 45 条起它**只认设置页
     *  那个对比开关**（默认关）→ 平时恒为 GenericCard（常驻、不让位），
     *  宿主回调 `onExpanded` / `onCollapsed` 只记日志、**不再换模板**。
     *  「在岛上回复」改由卡上的「回复」按钮**弹本应用自己的悬浮窗**（第 45 条
     *  最终形态，见 ReplyPanel.kt）；点卡片空白处才是打开豆包 App。
     *
     *  隐私：回复正文是聊天内容，按星河岛官方建议把锁屏可见性设为 TITLE_ONLY
     *  （锁屏只留标题与头像），不用 PUBLIC。
     */
    private fun pushReplyItem(c: Conv, body: String, ended: Boolean = false,
                              status: String? = null) {
        // 第 48 条（用户真机报的「点了删除，岛上还有内容在流式输出」）：**唯一漏斗**。
        // 卡片被 dismissReply() 收掉之后是 `suppressed=true`，此后这条会话**任何
        // 路径都不许再往岛上 post 这张卡**。原来只有部分调用点各自判 suppressed，
        // 漏了两个口子，两个都能让刚收掉的卡复活：
        //   ① `chat.delta` 的 beginConv 兜底（先复活、后判抑制位，见 handle）；
        //   ② 歌词播放器 tick 里那次 push —— `chat.end` / `chat.reply` 都会无条件
        //      重启播放器（`startLyric(c)`），播放器内部**没有**抑制位判断。
        // 放在这里，等于给「这条会话还该不该在岛上」只留一个判据。
        // 第 48 条追加：**删掉的会话（墓碑）** 同样一律不许再投 —— 会话在豆包那边
        // 已经没了，之后到达的每一条都只是死会话的残余推流（真机：删除回执晚到
        // 3.4s → 卡片被收 → 又收到 chat.start 复活 → 最后停在「回答结束」模板）。
        if (c.suppressed || deadCids.contains(c.cid)) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastSuppressSkipLog > 3_000) {
                lastSuppressSkipLog = nowMs
                log((if (deadCids.contains(c.cid)) "会话已删除（墓碑）"
                     else "卡片已被收掉（suppressed）") + "→ 本条会话不再投岛 " +
                    "id=${c.liveId} cid=${tail6(c.cid)}")
            }
            return
        }
        // 第 45 条（用户要求）：「悬浮窗出现，岛缩回不展开」—— 面板开着期间
        // 这条会话**不投岛**（卡已经被 end() 收掉；就算补投，宿主也会按新内容把
        // 卡片自动展开片刻，正好是用户不要的观感）。本地状态照常更新，
        // 面板一关 restoreIslandFor() 就会把最新内容投回去。
        if (c.panelOpen) {
            // **自愈**（真机教训）：面板可能不是我们关的 —— ColorOS 会把本 App
            // 冻结/回收，或者窗口被系统移除，onClosed 回调就不来了。
            // 这时 panelOpen 会一直是 true，这条会话**再也投不上岛**（用户看到
            // 的就是「偶发：豆包里有内容，岛上没内容」）。所以每次投递都对着
            // 真实窗口状态核一遍：面板其实不在了 → 就地恢复投岛。
            if (!ReplyOverlay.isShowing()) {
                c.panelOpen = false
                c.shown = true
                log("悬浮窗其实已不在（被系统收掉/进程被冻结过）→ 自动恢复投岛 " +
                    "id=${c.liveId} cid=${tail6(c.cid)}")
            } else {
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastPanelSkipLog > 5_000) {
                    lastPanelSkipLog = nowMs
                    log("悬浮窗面板开着 → 暂不投岛（岛保持缩回，第 45 条）" +
                        " id=${c.liveId} cid=${tail6(c.cid)}")
                }
                return
            }
        }
        val first = liveIds.add(c.liveId)
        if (first) armIdleNotice(c)
        // 「岛上回复交出去了」这个状态优先显示（第 40 条：只动 subtitle/状态位）。
        // 注意**不能**在调用方传 status=ST_DONE 时把它盖掉，否则真机上会出现
        // 「同一张卡一会儿 回答结束、一会儿 已交给豆包发送」的抖动。
        val st = status ?: statusWord(c, ended)
        // 豆包推流里的 markdown 记号（`**加粗**` 等）在岛上会**原样显示** ——
        // 豆包自己的界面会渲染成粗体、把记号藏起来，所以用户看到的是
        // 「实际回复没有 **，岛里却有」。这里统一走 markdownToPlain。
        val clean = markdownToPlain(body)
        val stripped = body.length - clean.length
        if (stripped > 0) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastMdLog > 3_000) {
                lastMdLog = nowMs
                log("去 markdown 记号 ${stripped}字（原文带 ** 等，岛上只显示纯文本）")
            }
        }
        // 胶囊信息区：来源 + 状态（状态挪到左侧，右侧让给滚动文字）
        // 收起状态（胶囊）只显示 来源 + 状态，**不再滚动正文**（用户要求）：
        // 「回答进行 / 回答结束」期间胶囊保持不动。c.src 是语义判断（手机/预览），
        // 显示名另算一份。
        val compact = "${srcWord(c)}·$st"
        // 正文：**卡片正文固定两行、不会滚动**（api-doc：消息内容最多 4096 字但
        // 卡片上最多两行；GenericCard 同样两行）。所以推流中与结束都取**尾部**
        // 最新一段 —— 给开头的话卡片永远停在开头，用户报的
        // 「只显示前面一部分、不会继续滚动」就是这个原因。
        // 推流中 body 用**等长**的尾巴（boundary=false）：窗口高度稳定，
        // 岛不会每帧重新测量；收尾那一帧才断句，读起来完整。
        // 长度取 [bodyMax]（用户要求只占一行，真机上可实时调）。
        // 真机事故：原来这里优先取 `c.containerBody`（那个 10 字分组），
        // 而 `containerBody` 只在 flushContainer 里被赋成当前分组、永远是"非空"，
        // 于是下面那条滑动窗口分支**从来没执行过** —— 卡片正文恒为一小段、
        // 且 `chat.end` 推的完整正文被丢弃，用户报的「内容只有结束时才出现一下、
        // 平时读不到」就是这里。现在统一走滑动窗口：推流中取等长的最新一段
        // （高度稳定、不折行、无省略号），收尾那帧按句子边界断（给结论）。
        val text = tailOf(clean, bodyMax(), boundary = ended).ifBlank { st }
        // 胶囊右侧文字是**唯一会滚动**的区域（api-doc:126），把更长的一段最新
        // 文字放这儿，就像歌词一样持续滚动刷新。
        // 现在这里放的是**当前这一组**（≤ LYRIC_GROUP 字，见歌词播放器）：
        // 组内流式长出来、组满换组，所以永远不需要截断，也就没有「…」。
        // 胶囊右侧（卡片里唯一会滚动的区域）**故意不再放正文**：用户要求
        // 「回答进行/回答结束不要滚动显示内容」，所以它就是静态的来源·状态。
        val roll = compact
        val msg = msgMode(c)
        // 这条日志是「多会话/主岛归属」的主要证据（cid 只留后 6 位，不打印正文）。
        // `title=` 是「会话标题」这条真机问题的证据：它必须等于 `${srcWord}-${会话名}`，
        // 而不是写死的兜底 `安卓包-豆包`（见 CHANGELOG ### 33）。
        // `card=` 是第 40 条的证据：收起 generic / 展开 msg，同一个 id。
        log("岛card id=${c.liveId} prio=${prioWord(c)} cid=${tail6(c.cid)} " +
            "src=${c.src} st=$st body=${text.length}字 " +
            "left=${srcWord(c).length}字 right=${st.length}字 srcs=" +
            sources().joinToString(",") { it.first } +
            " card=${if (msg) "msg" else "generic"}" +
            (if (c.replyState.isNullOrBlank()) "" else " reply='${c.replyState}'") +
            " title='${cut("${srcWord(c)}-${c.title}", TITLE_MAX, "豆包")}'")
        // 胶囊图标：**呼吸动画只接受 单色图标 / 内置符号 / 应用图标**，用照片会抛
        //   IllegalArgumentException: 照片类图片不支持呼吸变化，请改用单色图标、
        //   内置符号或应用图标。
        // 真机实测就是这么失败的：卡片一句话都没渲染出来，日志只有这一行异常。
        // 这里先试单色图标（保留豆包品牌形状），不行再退内置的「消息」符号。
        val icon = try {
            IslandImage.glyph(doubaoIcon)
        } catch (t: Throwable) {
            log("单色图标不可用(${t.javaClass.simpleName})，用内置符号")
            IslandImage.symbol(BuiltinSymbol.MESSAGE)
        }
        val activity = try {
            buildReplyActivity(c, icon, first, text, ended, compact, roll)
        } catch (t: Throwable) {
            // 图标形式不被接受时（SDK 校验发生在 build() 里），换内置符号重试一次，
            // 保证「至少能出卡片」而不是整条回复静默消失。
            log("回复卡片图标被拒(${t.javaClass.simpleName}: ${t.message})，改用内置符号重试")
            try {
                // 来源 logo = 豆包 App 的图标（原来这里只用内置符号 MESSAGE）
                buildReplyActivity(c, IslandImage.picture(doubaoIcon),
                    first, text, ended, compact, roll)
            } catch (t2: Throwable) {
                log("构建回复卡片失败: ${t2.javaClass.simpleName} ${t2.message}")
                return
            }
        }
        send(c.liveId, activity)
    }

    /** 显示用的来源名：**安卓包 / 预览**（c.src 是语义判断，保持 手机/预览，
     *  两套名字别混）。收起状态（胶囊）与标题都用它。
     *  旧的「电脑包」随电脑端一起删除（CHANGELOG 第 34 条）。 */
    private fun srcWord(c: Conv): String = when (c.src) {
        "手机" -> "安卓包"
        "预览" -> "预览"
        else -> c.src
    }

    // ============================================================
    //  第 40 条：岛上的回复框（展开才给 MessageCard）
    // ============================================================

    /** 这张卡现在该用消息卡（有回复输入条）吗？
     *
     *  · 设置页开关打开 → **永远**消息卡（旧的对比行为，用户可自行体验让位）；
     *  · 否则只有**宿主说它展开了**（[Conv.expanded]）才用消息卡。
     *
     *  为什么不是「一直消息卡」：消息卡按新消息排位、停留一段时间后让位副岛
     *  （api-doc:248），拿不到「常驻主岛」这个已验收行为（第 9/32 条）。
     *  宿主每展开一次就回调一次 `onExpanded`，所以「用户想看回复框」这件事
     *  宿主会主动告诉我们 —— 这就是两全的依据。 */
    //  第 45 条（用户最终决定）：**放弃「岛上原生回复框」这条路线**
    //  （第 43/44 条已证明：MessageCard 的回复栏与「常驻主岛」不可兼得）。
    //  岛卡**固定用 GenericCard**（受 Priority 管辖 → 常驻主岛、不消失），
    //  卡上按钮**按状态换组（第 48 条）**：回答中 `[知道了, 删除]`、
    //  回答结束 `[回复, 知道了, 删除]`；「回复」= 弹**本应用自己
    //  定制的悬浮窗**（ReplyPanel.kt），文字经 sendReply 发回这条会话。
    //  展开/收起不再换模板（第 44 条那套已回退）。
    //  [PREF_REPLY_BAR]：打开 = 永远 MessageCard（**纯对比开关**，默认关）。
    /** 回答结束后使用官方即时消息模板；回答中保持 GenericCard，避免消息卡提前让位。 */
    private fun msgMode(c: Conv): Boolean = c.ended

    /** 卡片正文（**只占一行**的那份尾巴，与第 27 条验收一致）。 */
    private fun cardBodyText(c: Conv, ended: Boolean, status: String): String =
        tailOf(markdownToPlain(c.buf), bodyMax(), boundary = ended).ifBlank { status }

    /** 消息卡的正文：给**完整**正文（消息卡的正文区比通用卡大），
     *  markdown 记号仍要清掉（第 28 条）。 */
    private fun msgBodyText(c: Conv, status: String): String =
        cut(markdownToPlain(c.buf).trim().ifEmpty { status }, BODY_MAX, status)

    /** 消息卡的**副标题位**：先把「岛上回复框交出去的结果」说出来，
     *  让点完回复的用户立刻看到反馈（用户要求：用 subtitle/status，别改标题）。 */
    private fun msgSubtitle(c: Conv): String {
        val rs = c.replyState
        return if (rs.isNullOrBlank()) "回复输入条" else cut(rs, SUBTITLE_MAX, rs)
    }

    /** 胶囊右侧/副标题的状态：**岛上回复交出去的结果优先**（用户要求看得见反馈），
     *  没有交过就按 ended 给「回答结束 / 回答进行」。 */
    private fun statusWord(c: Conv, ended: Boolean): String =
        c.replyState?.takeIf { it.isNotBlank() } ?: if (ended) ST_DONE else ST_REPLYING

    /** 岛内容编号 → 会话（没有就记一行并返回 null）。 */
    private fun convForId(tag: String, id: String): Conv? {
        val c = convOfId(id)
        if (c == null) log("$tag：未知内容 id=$id")
        return c
    }

    /**
     * 宿主回调 `onExpanded(activityId)`：用户把卡片展开了。
     *
     * **第 45 条起这里只记日志，不再换模板**（第 40/44 条的「同 id 换 MessageCard」
     * 已随路线变更一并取消）：卡片的形态由 [msgMode] 决定，而它只认设置页那个
     * 对比开关 —— 于是「回答进行 / 回答结束 / 展开 / 收起」任何一个时点，
     * 卡片的模板与按钮都**完全稳定**，不会再有形态切换带来的闪烁或让位。
     */
    fun onExpanded(id: String) {
        val c = convForId("岛展开", id) ?: return
        c.expanded = true
        log("岛展开 id=${c.liveId} cid=${tail6(c.cid)}（第 45 条：只记日志，" +
            "不再换 MessageCard —— 形态固定 => ${if (msgMode(c)) "msg" else "generic"}）")
    }

    /** 宿主回调 `onCollapsed(activityId)`：卡片收起。**同样只记日志**（见 [onExpanded]）。 */
    fun onCollapsed(id: String) {
        val c = convForId("岛收起", id) ?: return
        c.expanded = false
        log("岛收起 id=${c.liveId} cid=${tail6(c.cid)}（第 45 条：只记日志，" +
            "形态固定 => ${if (msgMode(c)) "msg" else "generic"}）")
    }

    /** 宿主回调 `onReply(activityId, text)`（旧名 [onReplyText]，保留给
     *  App 内的调用点）：用户在**岛上的回复框**里发了文字。
     *
     *  官方语义：宿主只负责把文字交出来，**实际发送由应用负责** ——
     *  所以这里反解 cid → 走现有的 [sendReply] 真正发回豆包，并把这张卡
     *  更新成「已交给豆包发送」（用 subtitle/status，不动标题）。
     *
     *  隐私：日志**只打 cid 后 6 位与字数**，绝不打正文。 */
    fun onReplyText(id: String, text: String) {
        val c = convOfId(id)
        if (c == null) { log("岛上回复：未知内容 id=$id，忽略"); return }
        if (text.isBlank()) {
            log("岛上回复：空内容，忽略（id=${c.liveId}）")
            return
        }
        c.expanded = true          // 回复框就在展开态里，保持一致
        c.replyState = ST_REPLY_SENT
        log("岛上回复 id=${c.liveId} cid=${tail6(c.cid)} ${text.length}字" +
            " → 交本应用发送（官方语义：宿主只交出文字）")
        // 用户刚操作过：取消「一分钟无操作」的未确认提醒 + 立刻给卡片反馈
        cancelIdleNotice(c)
        pushReplyItem(c, c.buf, ended = c.ended, status = statusWord(c, c.ended))
        // 上行记录统一在 sendReplyToConv 里落盘（第 46 条），这里不再重复记
        sendReplyToConv(c, text)
    }

    /** 岛上并列显示的来源（用户要求的“存在逻辑”）。电脑端已分离，所以这里
     *  **只剩手机一项**：能走到这里就说明豆包进程内的模块在线。
     *  旧版还会按 SSE 连接/`pc on` 追加一个「电脑 · 已连接 / 模拟在线」，
     *  现在没有第二个来源，相关判据（PC_LIVE_MS / simPcLive / `pc on`）全部删除。 */
    private fun sources(): List<Pair<String, String>> =
        listOf("手机" to "本机")

    /** 组装回复卡片。胶囊图标必须由调用方给（呼吸只接受非照片图标），其余按
     *  这里的状态统一生成；失败抛给调用方决定退路。
     *  卡片编号 `reply:<cid>`、优先级（主岛/副岛）、打开意图、点按回调都**按
     *  这条会话**生成（旧版全是全局单份）。 */
    private fun buildReplyActivity(c: Conv, icon: IslandImage, first: Boolean,
                                   text: String, ended: Boolean, compact: String,
                                   roll: String): IslandActivity {
        val st = statusWord(c, ended)
        val capsule = Capsule.Builder(icon)
            // **左槽**：来源（安卓包 / 预览；电脑包已随电脑端删除）
            .setLabel(cut(srcWord(c), SHORT_MAX, "豆包"))
            // 右侧（api-doc:126 —— 整张卡片里**唯一会滚动**的区域）**故意留空**：
            // 用户要求「收起的时候不要循环」。来源·状态改放
            // **左侧 label**（静态、不滚动，只在状态真变的时候才变）。
            // **右槽**：状态（回答进行 / 回答结束 / 已交给豆包发送）
            // —— 一左一右内容不同。
            // 这块是宿主唯一的滚动区（api-doc:126），但**只有文字超宽才会滚**：
            // 之前循环是因为我塞了 8 个字的整串，4 个字放得下、是静止的。
            .setTrailing(CapsuleTrailing.text(cut(st, SHORT_MAX, st)))
            .setBreathing(false)
            .build()
        // 第 48 条：按钮组只按**状态**换（回答中两按钮 / 回答结束三按钮），
        // 判据就是下面这个 st —— 与状态位、与第 47 条的 replyState 清零同一份真值。
        val card = buildReplyCard(c, icon, text, ended, st, buttons(st, msgMode(c)))
        return IslandActivity.Builder(c.liveId, capsule, card)
            // 先到者 HIGH（主岛），后来者 DEFAULT（副岛）；先到者被收掉时
            // 由 afterConvGone 重新 post 一次完成「升级」。
            .setPriority(priorityOf(c))
            .setAlertOnStart(first)
            .setOpenIntent(openIntentFor(c))           // 点卡片空白处打开豆包
            .setPostedAt(c.startedAt.coerceAtLeast(1L))
            .setLockScreenVisibility(LockScreenVisibility.TITLE_ONLY)
            // 常驻：卡片**不会**自己到点消失，只有
            //   · 用户点「我知道了」（ACT_ACK → dismissReply → island.end），或
            //   · 岛自己的上限（文档记的 8 小时 / 用户在星流里关掉本应用内容）
            // 才会离开。旧值 afterMillis(60s) 是「最后一次更新起 1 分钟自消」，
            // 用户明确要求「一直常驻，除非我自己点我知道了」，所以换成 untilEnded()。
            .setDismissPolicy(DismissPolicy.untilEnded())
            .setContentDescription(cut("豆包回复 ${c.title}", SUBTITLE_MAX,
                "豆包回复"))
            .build()
    }

    /** 卡片按钮组合（**按状态换组**，再按信息量从多到少降级）。
     *
     *  **第 48 条（用户本轮规则，取代第 45 条的「永远三按钮」）**：
     *  **同一个 id、同一张通用卡**，只按「现在是不是还在回答」换按钮组 ——
     *  不再有任何「msg ↔ generic 模板切换」。
     *
     *  · **正在回答**（推流中，或刚把话交给豆包、状态位是
     *    [ST_REPLY_SENT]「已交给豆包发送」）→ **两个按钮**
     *    `[知道了, 删除]`，**没有「回复」** —— 用户原话：「回答结束才可以显示回复
     *    按钮」；
     *  · **回答结束**（`ended=true` / 状态位是 [ST_DONE]「回答结束」）→ **三个按钮**
     *    `[回复, 知道了, 删除]`，「回复」= 打开既有悬浮窗（`ReplyPanel.kt`）。
     *
     *  判据取的是 [statusWord] 的结果（`st == ST_DONE`）而不是裸 `ended`：
     *  刚把话交给豆包那一瞬间 `ended` 还是 true（上一轮答完了），但状态位是
     *  「已交给豆包发送」、新一轮正在开始的路上 —— 那正是「正在回答」，
     *  必须给两个按钮。于是「回答结束 →（悬浮窗发出）→ 已交给豆包发送（两按钮）
     *  → 回答进行（两按钮）→ 回答结束（三按钮）」自洽，且第 47 条的
     *  `replyState` 跨轮清零逻辑一行都没改。
     *
     *  为什么还要一条**每状态各自**的降级链：SDK 的校验发生在 `build()` 里，
     *  **一抛就是整张卡不显示**（真机上表现为「岛一句话都没有」）。
     *  `GenericCard.build()` 的实际约束（反编译 `IslandButtonKt.checkButtons` 得到）：
     *  · 按钮 id 必须互不相同；
     *  · 文字按钮 ≤ 4 个、`IconButton` ≤ 3（通用卡允许 icon-only）、
     *    `BarIconButton` ≤ 4、**全部加起来 ≤ 4**、`ToggleButton` ≤ 3。
     *  数量上合法不代表宿主一定接受（模板/排版也可能整份拒掉），所以逐档试，
     *  并把**实际被接受的那一组**打进日志（`第 k/N 档`，N 随状态变化）：
     *  · 回答中：`[知道了,删除]` → `[知道了]` → `[]`（3 档）；
     *  · 回答结束：`[回复,知道了,删除]` → `[回复,知道了]` → `[回复]` → `[]`（4 档）。
     *
     *  标签为什么是「回复」而不是用户说的「悬浮窗回复」：官方对多个文字按钮的
     *  排布是「置于卡片底部」，五个汉字很可能放不下（真机正文一行只放得下
     *  ~14 个汉字，三个按钮平分一行则每个只有 4 字左右），所以取两个字；
     *  「悬浮窗」这件事写在按钮触发的日志与文档里。
     *
     *  第 69 条（用户报「结束事件的模板缺少了按钮」）：结束态走 MessageCard，
     *  官方回复栏由 `setReplyEnabled(true)` 提供，所以**不加**旧的「回复」文字
     *  按钮；但必须给「知道了 / 删除」，否则结束模板一个按钮都没有
     *  （第 42 条当时是「消息卡一个按钮都不给」，那个取舍已经被这条推翻）。
     *  同样是降级链：`[知道了,删除]` → `[知道了]` → `[]`。 */
    private fun buttons(st: String, msg: Boolean): List<List<IslandButton>> {
        fun ack() = TextButton(ACT_ACK, "知道了")
        fun del() = TextButton(ACT_DELETE, "删除", ButtonStyle.DESTRUCTIVE)
        fun rep() = TextButton(ACT_REPLY, "回复")
        // 结束态 MessageCard：官方回复栏已在，不再加「回复」；补上卡片操作按钮。
        if (msg) return listOf(listOf(ack(), del()), listOf(ack()), emptyList())
        // GenericCard: answering has no reply entry; ended has the legacy
        // overlay entry for the non-message comparison mode only.
        if (st == ST_DONE) return listOf(
            listOf(rep(), ack(), del()), listOf(rep(), ack()), listOf(rep()), emptyList())
        return listOf(listOf(ack(), del()), listOf(ack()), emptyList())
    }

    /** 一组按钮的可读名字（给「实际被接受的是哪一档」那条日志用）。 */
    private fun btnNames(bs: List<IslandButton>): String =
        bs.joinToString(",", "[", "]") { (it as? TextButton)?.label ?: it.id }

    /** 组装卡片本体。SDK 对按钮个数/正文长度有校验且**在 build() 里抛异常**，
     *  一抛就是整张卡片不显示（真机上表现为「岛一句话都没有」），所以这里
     *  按候选组合逐个降级尝试，绝不把异常留给调用方去猜。
     *
     *  **形态判定（第 71 条起只有两种）**：消息卡（`msg` = 该会话已回答结束）>
     *  通用卡。第 71 条把原来的「明细卡（调试用，默认关）」整支删掉了。
     *  通用的 [text] 是「只占一行」的那份尾巴；消息卡的正文与副标题**自己算**
     *  （用户要求：回复状态放 subtitle，标题一个字都不动）。 */
    private fun buildReplyCard(c: Conv, icon: IslandImage, text: String, ended: Boolean,
                               st: String, sets: List<List<IslandButton>>): IslandCard {
        val msg = msgMode(c)
        val kind = if (msg) "msg" else "generic"
        if (kind != lastCardKindLog) { lastCardKindLog = kind; log("卡片形态 = $kind") }
        var last: Throwable? = null
        for (bs in sets) {
            try {
                val built: IslandCard = if (msg) {
                    // 消息卡（回答结束）
                    //   title    = 会话名（官方 MessageCard.Builder(sender, text)：
                    //              sender 显示在标题位；没有会话名就用来源名）
                    //   正文     = 当前回复正文（markdown 已清）
                    //   副标题位 = 「已交给豆包发送」这类**回复结果**（不动标题）
                    val sender = c.title.ifBlank { srcWord(c) }
                    val b = MessageCard.Builder(cut(sender, TITLE_MAX, "豆包"),
                            cut(text, BODY_MAX))
                        .setAvatar(IslandImage.picture(doubaoIcon))
                        .setReplyEnabled(true)      // 官方结束态回复模板；onReply 仍交给悬浮窗发送链路
                    bs.forEach { b.addButton(it) }
                    b.build()
                } else {
                    val b = GenericCard.Builder(
                            cut("${srcWord(c)}-${c.title}", TITLE_MAX, "豆包"))
                        .setSubtitle(cut(st, SUBTITLE_MAX))
                        .setBody(cut(text, BODY_MAX))
                        .setImage(IslandImage.picture(doubaoIcon))
                    bs.forEach { b.addButton(it) }
                    b.build()
                }
                // 第 45 条：把**实际被接受**的按钮组合打出来（同一档只打一次，
                // 否则推流期间每秒一行会把日志刷满）。这是「三个按钮到底能不能
                // 一起上岛」的唯一直接证据。
                val names = btnNames(bs)
                // 第 69 条：去重键必须**带卡片形态**。原来只比按钮名，于是通用卡
                // 接受 `[知道了,删除]` 之后，消息卡也接受同一组时这行被吞掉 ——
                // 而它恰恰是「结束态到底有没有按钮」的唯一直接证据。
                val key = "$kind|$names"
                if (key != lastBtnSet) {
                    lastBtnSet = key
                    log("岛按钮组合被接受: $names（第 ${sets.indexOf(bs) + 1}/${sets.size} 档、" +
                        "${if (kind == "msg") "消息卡" else "通用卡"}）")
                }
                return built
            } catch (t: Throwable) {
                last = t
                log("卡片按钮组合(${btnNames(bs)})被拒: " +
                    "${t.javaClass.simpleName} ${t.message}")
            }
        }
        throw (last ?: IllegalStateException("回复卡片构建失败"))
    }
}
