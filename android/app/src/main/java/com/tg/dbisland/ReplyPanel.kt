package com.tg.dbisland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 第 45 条（用户最终决定）—— **我们自己定制的悬浮窗**回复面板。
 *
 * 用户原话：「放弃打开悬浮窗，使用我们自己定制的悬浮窗」「点击卡片的事件为
 * 打开豆包 app」；两条链路彻底分开：
 *  · 岛上「回复」按钮 → **本文件这块面板**（可打字，文字经 `onSend` →
 *    `IslandBridge.sendReply` 发回这条会话）；
 *  · 点击卡片空白处 → 宿主执行 `IslandActivity.setOpenIntent(...)` 打开豆包
 *    App（`OpenDoubaoReceiver` → `IslandBridge.cardTapped`），与本面板无关。
 *
 * 为什么又回到自建面板：ColorOS 的小窗（zoom window）在框架内部，普通 App 拿不到
 * 入口 —— 真机实测 `android:activity.mZoomLaunchFlags=8` 会被整份忽略（见
 * CHANGELOG 第 45 条）；而「在豆包自己的输入框里打字」要求窗口**可聚焦**，宿主
 * 那块岛是 SystemUI 的 `STATUS_BAR_SUB_PANEL`（`NOT_FOCUSABLE`），弹不出键盘 ——
 * 打字只能由本应用自己的悬浮窗承担。
 *
 * ## 行为契约（`tools/overlay_test.py` 抓的就是这几行日志）
 *  · 窗口类型 `TYPE_APPLICATION_OVERLAY`（要 `SYSTEM_ALERT_WINDOW`，设置页有入口）；
 *  · **可聚焦**：能弹软键盘、能收返回键；
 *  · **贴在输入法上方**：窗口 `gravity=BOTTOM` + `ADJUST_RESIZE`，再叠加
 *    「IME insets 回调 + 200ms 轮询 `getWindowVisibleDisplayFrame`」两条保险 ——
 *    键盘弹出/收起、键盘高度变化（九宫格、手写、悬浮键盘）时卡片都跟着动，始终
 *    落在键盘**上沿之上**：不会被键盘遮住，也不会压到键盘（用户要求
 *    「适配悬浮窗在输入法上面不要超过了」）；
 *  · **触摸退出逻辑**（用户要求「适配触摸屏的退出逻辑」）：
 *    ① 点面板以外的任何地方 → `ACTION_OUTSIDE`（`FLAG_WATCH_OUTSIDE_TOUCH`）→ 收；
 *    ② 返回键 → 收（`PanelRoot.dispatchKeyEvent`；键盘开着时系统先收键盘）；
 *    ③ ✕ / 取消 → 收；
 *    ④ 30s 无操作（任何输入/触摸都续期）→ 收。
 *  · **「发送」带振动**（用户要求「给发送添加振动事件」）：30ms 单次，见 [buzz]。
 *  · 锁屏不弹（调用方先判 `KeyguardManager`，见 `IslandBridge.openReplyOverlay`）。
 */
object ReplyOverlay {
    private const val IDLE_MS = 30_000L
    private const val IME_TICK_MS = 200L

    /**
     * 面板配色（用户要求：「悬浮窗适配黑白色调自动跟随系统」）。
     *
     * 面板不是 Compose 画的，所以走 [com.tg.dbisland.ui.AppColor.resolveFor] ——
     * 第 46 条之后那是**唯一**的明暗判断入口：设置页里固定「浅色 / 深色」时，
     * 悬浮窗和 App 一起变（而不是只认系统）。两套都保证「任意背景上都看得清」：
     * 深色底近黑 + 白字，亮色底近白 + 黑字，发送按钮两套都是蓝底白字。
     */
    private class Palette(
        val bg: String, val stroke: String, val field: String,
        val accent: String, val text: String, val sub: String, val onAccent: String,
    )

    private fun palette(ctx: Context): Palette {
        val night = com.tg.dbisland.ui.AppColor.resolveFor(ctx)
        return if (night) Palette(
            bg = "#F21C1C1E", stroke = "#33FFFFFF", field = "#26FFFFFF",
            accent = "#4C8DFF", text = "#FFFFFFFF", sub = "#99FFFFFF",
            onAccent = "#FFFFFFFF",
        ) else Palette(
            bg = "#F7FFFFFF", stroke = "#22000000", field = "#0F000000",
            accent = "#3B6FE0", text = "#14171F", sub = "#7A8296",
            onAccent = "#FFFFFFFF",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var panel: PanelRoot? = null
    private var input: EditText? = null
    private var log: ((String) -> Unit)? = null
    private var onSend: ((String) -> Unit)? = null
    private var onClosedCb: (() -> Unit)? = null
    private var appCtx: Context? = null
    private var shownAtMs = 0L
    private var lastImePx = -1

    private val idle = Runnable { close("30s 无操作") }

    /** 键盘高度轮询：`ADJUST_RESIZE` 与 insets 回调在部分 ROM 上不灵，这条用最老的
     *  `getWindowVisibleDisplayFrame` 兜底（200ms 一次，很便宜）。 */
    private val imeTick = object : Runnable {
        override fun run() {
            val v = panel ?: return
            syncImePadding(v)
            main.postDelayed(this, IME_TICK_MS)
        }
    }

    /** 面板现在有没有在屏幕上（`BridgeService.onDestroy` 会顺手收掉）。 */
    fun isShowing(): Boolean = panel != null

    /** 第 48 条：**模拟通道专用的填字口** —— 把文本写进当前这块面板的输入框
     *  （等价于用户/输入法把这段字打进去），供自动化在没有真手指、也注入不了
     *  按键时把「打字 → 发送」这条链路跑完。
     *
     *  为什么需要它（真机实测，App 自己的日志）：面板输入框拿到焦点后
     *  （`requestFocus=true hasFocus=true`），`adb shell input text` /
     *  `input keyevent` 送进来的按键**只有 `action=1`（UP）**，DOWN 在框架的
     *  IME 输入阶段就被吃掉了；而 Android 的 TextView **只在 ACTION_DOWN 上
     *  插入字符** —— 关掉全部输入法也一样（`ime list -s` 为空时仍然只有 UP）。
     *  所以自动化里「面板弹出来了、键盘也在，就是打不进字」的真正原因在设备/
     *  框架这一侧，不是面板的毛病（真人用输入法打字走 composing→commit，不受影响）。
     *  @return 面板没开着时返回 false。 */
    fun typeIntoLive(text: String): Boolean {
        val et = input ?: return false
        if (panel == null) return false
        main.post {
            et.setText(text)
            et.setSelection(et.text?.length ?: 0)
            log?.invoke("悬浮窗回复：模拟通道填字 ${text.length}字（等价于用户打字）")
        }
        return true
    }

    /** `SYSTEM_ALERT_WINDOW` 是**特殊权限**，只能用户在系统页里点开，所以设置页把
     *  它做成「状态 + 去授权」的行，而不是运行时申请。 */
    fun canShow(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    /**
     * 弹面板。[convCid] 是这条会话的线上会话号；[send] 由 `IslandBridge` 传入 ——
     * 面板不碰发送逻辑，只负责拿文字，于是「面板 → 发送」和 App 内回复页走的是
     * 同一个函数。
     */
    @SuppressLint("SetTextI18n")
    fun show(ctx: Context, convCid: String, title: String,
             logFn: (String) -> Unit, send: (String) -> Unit,
             onShown: (() -> Unit)? = null,
             onClosed: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        main.post {
            if (panel != null) close("换了一条会话")
            appCtx = app
            log = logFn
            onSend = send
            onClosedCb = onClosed
            val wmgr = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = app.resources.displayMetrics
            // 亮/暗跟随系统（用户要求，见 [palette]）
            val pal = palette(app)
            fun dp(v: Int) = (v * dm.density).toInt()

            val card = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.parseColor(pal.bg))
                    setStroke(dp(1), Color.parseColor(pal.stroke))
                }
            }

            val head = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(app).apply {
                text = "悬浮窗回复 · " + cut(title, 12)
                setTextColor(Color.parseColor(pal.text))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(TextView(app).apply {
                text = "✕"
                setTextColor(Color.parseColor(pal.sub))
                textSize = 18f
                setPadding(dp(10), dp(2), dp(2), dp(2))
                setOnClickListener { close("用户点了 ✕") }
            })
            card.addView(head)

            val et = EditText(app).apply {
                hint = "直接在这里打字，发回这条豆包消息…"
                setHintTextColor(Color.parseColor(pal.sub))
                setTextColor(Color.parseColor(pal.text))
                textSize = 16f
                isSingleLine = false
                maxLines = 4
                minLines = 1
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor(pal.field))
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            input = et
            card.addView(et)

            val row = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            row.addView(TextView(app).apply {
                text = "取消"
                setTextColor(Color.parseColor(pal.sub))
                textSize = 15f
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { close("用户点了取消") }
            })
            row.addView(TextView(app).apply {
                text = "发送"
                setTextColor(Color.parseColor(pal.onAccent))
                textSize = 15f
                setPadding(dp(22), dp(10), dp(22), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor(pal.accent))
                }
                setOnClickListener { doSend() }
            })
            card.addView(row)

            // 根容器：**底部对齐**（卡片贴在键盘上沿），左右留 10dp。
            // 触摸退出逻辑在 PanelRoot 里（ACTION_OUTSIDE / 返回键）。
            card.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            val wrap = PanelRoot(app).apply {
                setPadding(dp(10), 0, dp(10), dp(10))
                addView(card)
                onTouchResetter = { resetIdle() }
                onDismiss = {
                    // 宽限期：打开面板的那一次触摸（用户在岛上点「回复」）抬手时可能
                    // 正好落在刚出现的面板之外，会被当成 ACTION_OUTSIDE 把面板立刻
                    // 收掉。400ms 内不认「点外面」，之外照常收。
                    if (android.os.SystemClock.elapsedRealtime() - shownAtMs >= 400L) {
                        close("点了面板外")
                    } else {
                        logFn("悬浮窗回复：忽略打开瞬间的点外面（400ms 宽限期）")
                    }
                }
                onBack = { close("返回键") }
                onKeySeen = { logFn(it) }
                card.setOnTouchListener { _, _ -> resetIdle(); false }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // WATCH_OUTSIDE_TOUCH：点面板以外能收到 ACTION_OUTSIDE（触摸退出）
                // NOT_TOUCH_MODAL：面板以外的手势照常交给下面的 App/桌面
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT)
            // BOTTOM + ADJUST_RESIZE：键盘弹出时窗口整体上移，卡片始终在键盘上方
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE

            val ok = try {
                wmgr.addView(wrap, lp)
                true
            } catch (t: Throwable) {
                logFn("悬浮窗回复：addView 失败 " +
                    "${t.javaClass.simpleName} ${t.message}")
                false
            }
            if (!ok) return@post
            wm = wmgr
            panel = wrap
            lastImePx = -1
            shownAtMs = android.os.SystemClock.elapsedRealtime()

            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) =
                    Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) =
                    resetIdle()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            et.setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    doSend(); true
                } else false
            }
            // 返回键的三条保险（真机上按键走哪条路取决于 ROM/输入法）：
            //  ① PanelRoot.dispatchKeyEvent（窗口拿到按键时最先到这里）
            //  ② OnUnhandledKeyEventListener（视图层没人处理时兜底，API 28+）
            //  ③ EditText 的 OnKeyListener（输入法把 BACK 交给编辑器时）
            et.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK) {
                    close("返回键(编辑器)")
                    true
                } else false
            }
            if (Build.VERSION.SDK_INT >= 28) {
                wrap.addOnUnhandledKeyEventListener { _, ev ->
                    if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (ev.action == KeyEvent.ACTION_UP) close("返回键(兜底)")
                        true
                    } else false
                }
            }
            // 两条保险：平台 insets 回调 + 200ms 轮询，谁先拿到键盘高度算谁的
            wrap.setOnApplyWindowInsetsListener { v, ins ->
                val ime = if (Build.VERSION.SDK_INT >= 30) {
                    ins.getInsets(WindowInsets.Type.ime()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    ins.systemWindowInsetBottom
                }
                applyImePad(v, ime)
                ins
            }
            // 第 48 条（真机实测）：窗口**能**收到按键（`PanelRoot.dispatchKeyEvent`
            // 那条「按键 code=…」日志有输出，说明按键送到了这个窗口），但
            // **EditText 不是窗口里 focus 的那个 view** —— uiautomator dump 里它是
            // `focused="false"`，于是这些按键在视图层被丢掉、一个字都进不去，
            // 只有输入法「上屏」（composing → commit，走 InputConnection）能进字。
            // 真实用户用输入法打字不受影响，但**硬件键盘 / ADB `input text` /
            // 无障碍服务**这类直接送按键的路径打不进字。
            // 修法：不让外层 FrameLayout 抢焦点（子 view 优先），并在窗口真正挂上
            // 之后再补两次 requestFocus（幂等；顺带把结果写进日志，便于排查）。
            wrap.apply {
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                requestFocus()
            }
            et.requestFocus()
            val refocus = Runnable {
                if (panel !== wrap) return@Runnable      // 面板已经关了，别再动
                val ok = et.requestFocus()
                logFn("悬浮窗回复：输入框 requestFocus=$ok hasFocus=${et.hasFocus()}" +
                    "（false 时直接送进来的按键会打不进字）")
            }
            et.postDelayed(refocus, 200)
            et.postDelayed(refocus, 700)
            et.post {
                val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(et,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            main.post(imeTick)
            resetIdle()
            // 第 45 条（用户要求）：「**悬浮窗出现，岛缩回不展开**」——
            // 面板已经挂到屏幕上了，再通知 IslandBridge 把那张卡重投一次
            // （宿主收到同 id 的更新会把展开态收回去，真机上是 `岛收起` 回调）。
            onShown?.let {
                try { it() } catch (t: Throwable) {
                    logFn("悬浮窗回复：岛缩回回调异常 ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /** 用 `getWindowVisibleDisplayFrame` 反推键盘高度（最老的通用办法）。 */
    private fun syncImePadding(v: View) {
        val r = Rect()
        v.getWindowVisibleDisplayFrame(r)
        val screenH = v.resources.displayMetrics.heightPixels
        val ime = (screenH - r.bottom).coerceAtLeast(0)
        applyImePad(v, ime)
    }

    /** 把键盘高度做成根容器的底部内边距 → 卡片被顶到键盘上沿之上，**永不重叠、
     *  也不越出屏幕**（用户要求「在输入法上面不要超过了」）。 */
    private fun applyImePad(v: View, imePx: Int) {
        if (imePx == lastImePx) return
        lastImePx = imePx
        val dm = v.resources.displayMetrics
        val side = (10 * dm.density).toInt()
        v.setPadding(side, 0, side, imePx + (10 * dm.density).toInt())
        log?.invoke("悬浮窗回复：键盘高度 ${imePx}px → 面板贴在输入法上沿")
    }

    /** 发送：文字交给 [onSend]（= `IslandBridge.sendReply`），然后收面板。
     *  先给一次**振动反馈**（用户要求「给发送添加振动事件」），再收面板再发送 ——
     *  手感上「按下去就震」比「等网络回来才震」更跟手。 */
    private fun doSend() {
        val t = input?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) { close("空内容，不发"); return }
        buzz()
        val send = onSend
        close("已发送 ${t.length}字（已振动）")
        send?.invoke(t)
    }

    /** 「发送」的振动事件（`VIBRATE` 普通权限，安装即授予）。
     *  30ms 单次、默认强度：够确认，不打扰；失败（无马达/被省电策略禁掉）只记日志，
     *  绝不因为振动失败而影响发送本身。 */
    private fun buzz() {
        try {
            val v = appCtx?.getSystemService(Context.VIBRATOR_SERVICE)
                    as? android.os.Vibrator
            if (v == null || !v.hasVibrator()) {
                log?.invoke("悬浮窗回复：这台设备没有振动器，跳过振动")
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                    30L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(30L)
            }
            log?.invoke("悬浮窗回复：发送振动 30ms（VibrationEffect.createOneShot）")
        } catch (t: Throwable) {
            log?.invoke("悬浮窗回复：振动失败 ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 收起面板。[reason] 会写进日志（真机排查就看这一行）。 */
    fun close(reason: String) {
        val act = {
            val p = panel
            if (p != null) {
                panel = null
                input = null
                onSend = null
                lastImePx = -1
                main.removeCallbacks(idle)
                main.removeCallbacks(imeTick)
                try { wm?.removeViewImmediate(p) } catch (_: Throwable) {}
                wm = null
                log?.invoke("悬浮窗回复：面板已关闭（$reason）")
                // 第 45 条：面板关了 → 让 IslandBridge 把这张卡补回岛上
                val cb = onClosedCb
                onClosedCb = null
                cb?.let {
                    try { it() } catch (t: Throwable) {
                        log?.invoke("悬浮窗回复：岛补回回调异常 ${t.javaClass.simpleName}")
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) act() else main.post(act)
    }

    /** 服务退出/被回收时顺手收掉（否则面板会留在屏幕上没人管）。 */
    fun closeIfShowing() {
        if (isShowing()) close("服务退出")
    }

    private fun resetIdle() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    private fun cut(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n) + "…"

    /** 面板里打字打了一半的内容（给「一分钟未确认」之类判断留个口子）。 */
    fun draft(): String = input?.text?.toString().orEmpty()

    /**
     * 面板的根容器：**触摸退出逻辑**都在这里。
     *  · `onTouchEvent` 收到 `ACTION_OUTSIDE`（要 `FLAG_WATCH_OUTSIDE_TOUCH`）
     *    → 点面板以外就收；
     *  · `dispatchKeyEvent` 拦返回键 → 收（键盘开着时系统先收键盘，再轮到我们）。
     */
    private class PanelRoot(ctx: Context) : FrameLayout(ctx) {
        var onDismiss: (() -> Unit)? = null
        var onBack: (() -> Unit)? = null
        var onTouchResetter: (() -> Unit)? = null
        var onKeySeen: ((String) -> Unit)? = null

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            onTouchResetter?.invoke()
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                onDismiss?.invoke()
                return true
            }
            return super.onTouchEvent(ev)
        }

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            // 真机排查用：按键事件到底有没有送进这个悬浮窗窗口
            onKeySeen?.invoke("按键 code=${ev.keyCode} action=${ev.action}")
            if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
                if (ev.action == KeyEvent.ACTION_UP) onBack?.invoke()
                return true
            }
            return super.dispatchKeyEvent(ev)
        }
    }
}
