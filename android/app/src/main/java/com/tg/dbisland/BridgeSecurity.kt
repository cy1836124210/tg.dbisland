package com.tg.dbisland

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 跨进程通信的统一授权入口。
 *
 * 本模块的快照里有三类「别人能主动打进来」的入口，安全模型必须写死在一处，
 * 否则任何一处漏校验就等于全部失效：
 *
 *   1. 豆包进程内动态注册的 `com.tg.dbisland.SEND` / `.DELETE` 命令接收器
 *      —— **签名级权限** `com.tg.dbisland.permission.CONTROL` 强制发送方
 *      （见 DoubaoHookEntry.registerCommandReceiver）。这两个动作能替用户发消息、
 *      永久删除会话，属于「以用户名义执行写操作」，只靠包名过滤不够。
 *   2. 本 App 清单里 exported 的 `BridgeEventReceiver` / `KeepAliveReceiver`
 *      / `OpenDoubaoReceiver` —— 这些必须让豆包进程、system_server 打进来，
 *      没法用签名权限（它们签的不是我们的证书），因此按 **uid 白名单**
 *      校验发送方。
 *   3. `EventProvider` —— 同样按 uid 白名单（见 EventProvider.isAllowed）。
 *
 * uid 白名单的取值原则是「最小可信集」：只放我们自己、root(0)、system_server(1000)、
 * shell(2000)、豆包 `com.larus.nova`，以及星河岛宿主（PendingIntent 回调的来源）。
 * 任何其它 uid 一律拒绝并记日志。
 */
object BridgeSecurity {

    private const val TAG = "IslandBridge"

    /** 命令通道签名级权限：只有与本模块同证书签名的应用才能持有。
     *  清单里由本应用 `&lt;permission&gt;` 定义、`&lt;uses-permission&gt;` 申请；
     *  豆包进程内的动态接收器以它为 `broadcastPermission` 注册，于是任意第三方
     *  应用（包括被签名不同、或未申请的）发送 `com.tg.dbisland.SEND` 都会被系统丢弃。 */
    const val PERM_CONTROL = "com.tg.dbisland.permission.CONTROL"

    const val PKG_DOUBAO = "com.larus.nova"

    /** 可能通过 PendingIntent 点我们卡片的星河岛宿主（正式/调试/独立岛）。 */
    private val HOST_PKGS = arrayOf(
        "com.astraflow.tool",
        "com.astraflow.tool.debug",
        "com.astraisland")

    @Volatile private var doubaoUid = -2
    @Volatile private var hostUids: MutableSet<Int>? = null

    /** 取广播发送方 uid。
     *
     *  · API 34+ 用官方公开的 [BroadcastReceiver.getSentFromUid]；
     *  · API 34 以下用反射取 @hide 的 `BroadcastReceiver.getSendingUid()`。
     *
     *  返回 -1 表示**无法归因**，分两种情况，都由调用方按各自策略处理
     *  （命令通道由签名级权限兜底；事件通道放行并告警）：
     *    · 发送方 targetSdk ≥ 34 且没开身份共享 —— Android 14 起系统不再附带
     *      发送方身份，公开 API 返回 `Process.INVALID_UID`；
     *    · 取到的 uid **等于接收方自己** —— 这是真机踩出来的坑（v1.2）：
     *      `onReceive` 大多是在主线程 Handler 里回调的、**不在 Binder 事务中**，
     *      此时 `Binder.getCallingUid()` 返回的是**本进程**的 uid。实测模块在豆包
     *      进程里拿到 10375（= 豆包自己），而真正的发送方是本 App 的 10430，
     *      于是「只认本模块 App」的命令校验把**合法命令全部拒掉** —— 真机现象
     *      就是「回复失败、等 8s 没回执」。发送方不可能是接收方自己，所以这种
     *      取值一律当**无效**丢弃，绝不拿它去做「像不像自己人」的判断。
     */
    fun senderUid(r: BroadcastReceiver?): Int = try {
        val u = if (Build.VERSION.SDK_INT >= 34 && r != null) {
            r.sentFromUid
        } else reflectedSendingUid(r)
        if (u < 0 || u == Process.myUid()) -1 else u
    } catch (_: Throwable) { -1 }

    /** API 34 以下的兜底：`getSendingUid()` 是 @hide 方法，各 ROM 实现不一，
     *  用反射取；取不到返回 -1（由调用方按「无法归因」处理）。 */
    private fun reflectedSendingUid(r: BroadcastReceiver?): Int {
        if (r == null) return -1
        return try {
            val m = BroadcastReceiver::class.java
                .getDeclaredMethod("getSendingUid")
            m.isAccessible = true
            (m.invoke(r) as? Int) ?: -1
        } catch (_: Throwable) { -1 }
    }

    /**
     * 清单接收器的守卫：**只拒绝能明确归因、且不在白名单里的发送方**。
     *
     * 这样做的原因很实际：`getSendingUid()` 在个别 ROM／投递路径上可能拿不到
     * 发送方 uid（返回 -1）。若这时一律 fail-closed，
     * 会把豆包、system_server、root 中继的正常事件一起挡掉，功能直接不可用。
     * 因此策略是：
     *   · uid 可信 → 放行；
     *   · uid 可归因但不可信 → 拒绝（这就是要挡的第三方伪造）；
     *   · uid 拿不到（-1）→ 放行但记警告，并把这个局限写进 README「已知限制」。
     *
     * 真正危险的两个写操作（SEND/DELETE）不走这条宽松路径，它们由系统强制的
     * 签名级权限兜底，不存在「拿不到 uid 就放行」的空档。
     */
    fun allowBroadcast(ctx: Context?, r: BroadcastReceiver?): Boolean {
        val uid = senderUid(r)
        if (uid < 0) {
            // 只记第一次：事件广播是高频路径，逐条打印会把 logcat 刷满
            // （真机实测一轮回答能到十几条），需要时看第一条即可。
            if (!warnedUnknownSender) {
                warnedUnknownSender = true
                Log.w(TAG, "bcast sender uid unknown — allowed (see README 已知限制)")
            }
            return true
        }
        if (isTrustedUid(ctx, uid)) return true
        Log.w(TAG, "bcast rejected: untrusted sender uid=$uid")
        return false
    }

    /** 事件通道「发送方 uid 拿不到」的告警只打一次（见 [allowBroadcast]）。 */
    @Volatile private var warnedUnknownSender = false

    /** 可信 uid 最小集合。 */
    fun isTrustedUid(ctx: Context?, uid: Int): Boolean {
        if (uid < 0) return false
        if (uid == Process.myUid()) return true          // 自己（含 PendingIntent 回跳）
        if (uid == 0) return true                        // root（adb / root 调试与唤醒脚本）
        if (uid == Process.SYSTEM_UID) return true        // system_server 保活/中继
        if (uid == Process.SHELL_UID) return true         // adb / shell 调试
        val c = ctx ?: return false
        if (uid == doubaoUidOf(c)) return true            // 豆包进程内的 LSPosed 模块
        return hostUidsOf(c).contains(uid)                // 星河岛宿主
    }

    /**
     * 命令通道（`com.tg.dbisland.SEND` / `.DELETE`）的发送方校验。
     *
     * 这个函数是**在豆包进程内**调用的，不能复用 [isTrustedUid]：那里
     * `Process.myUid()` 等于豆包自己的 uid，会把豆包本身也当成可信发送方。
     * 命令通道只信任三件事：本模块 App（同签名，且已由签名级权限强制）、
     * root(0)、shell(2000)。其它应用即使拿到 uid 也无法通过签名权限这一关。
     *
     * @param uid 发送方 uid；-1 表示无法归因 —— 此时**放行**，因为系统强制的
     *            签名级权限已经在投递前把关，重复 fail-closed 只会造成偶发丢命令。
     */
    fun allowCommandSender(ctx: Context?, uid: Int): Boolean {
        if (uid < 0) return true
        if (uid == 0) return true
        if (uid == Process.SHELL_UID) return true
        val c = ctx ?: return false
        return uid == bridgeUidOf(c)
    }

    /**
     * system_server 内「事件中继」接收器（`com.tg.dbisland.EVENT_RELAY`）的
     * 发送方校验。
     *
     * 为什么单独一个函数：这个接收器住在 system_server 里，收到后会**以 uid 1000
     * 的身份**把事件转发给本 App。如果不校验，任意应用发一条同 action 的隐式广播
     * 就能借 uid 1000 绕开 App 侧的白名单，等于给岛注入任意内容。而 system_server
     * 里 `Process.myUid()` 就是 1000，所以同样不能复用 [isTrustedUid]。
     * 只认豆包进程、root、shell。
     */
    fun allowEventRelaySender(ctx: Context?, uid: Int): Boolean {
        if (uid < 0) return true
        if (uid == 0) return true
        if (uid == Process.SHELL_UID) return true
        val c = ctx ?: return false
        return uid == doubaoUidOf(c)
    }

    @Volatile private var selfUid = -2

    /** 本模块 App 的 uid（可能与被注入的进程不同，必须查包名而不是用 myUid）。 */
    private fun bridgeUidOf(ctx: Context): Int {
        if (selfUid != -2) return selfUid
        synchronized(this) {
            if (selfUid == -2) {
                selfUid = try {
                    ctx.packageManager.getPackageUid("com.tg.dbisland", 0)
                } catch (_: Throwable) { -1 }
            }
        }
        return selfUid
    }

    private fun doubaoUidOf(ctx: Context): Int {
        if (doubaoUid != -2) return doubaoUid
        synchronized(this) {
            if (doubaoUid == -2) {
                doubaoUid = try {
                    ctx.packageManager.getPackageUid(PKG_DOUBAO, 0)
                } catch (_: Throwable) { -1 }
            }
        }
        return doubaoUid
    }

    private fun hostUidsOf(ctx: Context): Set<Int> {
        hostUids?.let { return it }
        synchronized(this) {
            hostUids?.let { return it }
            val s = HashSet<Int>(4)
            for (p in HOST_PKGS) {
                try { s.add(ctx.packageManager.getPackageUid(p, 0)) }
                catch (_: Throwable) { /* 没装就跳过 */ }
            }
            hostUids = s
            return s
        }
    }
}
