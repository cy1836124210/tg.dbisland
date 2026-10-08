package com.tg.dbisland.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg.dbisland.ui.glass.GlassBox
import com.tg.dbisland.ui.glass.GlassStyle

/** 三个主页面（底部 dock 的三格）。 */
enum class AppPage(val label: String, val icon: ImageVector, val iconOn: ImageVector) {
    Chat("聊天", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    Logs("日志", Icons.Outlined.ReceiptLong, Icons.Filled.Tune),
    Settings("设置", Icons.Outlined.Settings, Icons.Filled.Tune),
}

/**
 * 应用外壳：背景 + 内容区 + 底部玻璃 dock。
 *
 * 结构刻意简单（不用 Navigation 组件）——只有三个平级页面、没有返回栈、
 * 没有深链接，一个 `rememberSaveable` 的枚举就够了；引入 navigation-compose
 * 只会多一个依赖和一层没必要的抽象。
 */
@Composable
fun AppShell() {
    // 只记在内存里：三个平级页面没有返回栈，旋转屏幕后回到「聊天」是可接受的
    // （用 rememberSaveable 存枚举要多写一个 Saver，收益为零）。
    var page by remember { mutableStateOf(AppPage.Chat) }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    // 第 46 条（用户报的「白主题底部还有黑色的背景」）：
                    // 最下面那一档以前是**写死的** `Color(0xFF080A0E)`（近黑），
                    // 浅色下页面就从白一路渐变到黑 —— 底部连着导航栏一条黑带。
                    // 现在换成 AppColor.bgBottom（亮/暗一对：深色仍是原来的近黑，
                    // 浅色是比 bg0 略深的同色系浅灰蓝）。
                    listOf(AppColor.bg1, AppColor.bg0, AppColor.bgBottom),
                )
            )
    ) {
        // ---- 内容区 ----
        // **不在这里加任何模糊**：磨砂属于「浮在内容之上的那块面板自己」，
        // 不是属于内容的（见 ChatPage / GlassBox 的 frosted 参数，以及
        // frostedBackdrop 的说明）。早期版本在这里给内容加 blur，效果就是
        // 整个页面被糊掉 —— 用户当场指出过。
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = statusBar, bottom = 96.dp + navBar),
        ) {
            when (page) {
                AppPage.Chat -> ChatPage()
                AppPage.Logs -> LogsPage()
                AppPage.Settings -> SettingsPage()
            }
        }

        // ---- 底部 dock（磨砂玻璃）----
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp + navBar),
        ) {
            GlassDock(
                selected = page,
                onSelect = { page = it },
                chatBadge = null,
            )
        }
    }
}

/**
 * 底部 dock：一块玻璃条 + 三格 + 一个会「流过去」的玻璃高亮。
 *
 * 高亮用 `animateDpAsState` + spring 做的位移动画（不是直接切颜色），
 * 这是液体玻璃观感的关键：选中态是**一块会滑动的玻璃**，不是一个换色的图标。
 */
@Composable
private fun GlassDock(
    selected: AppPage,
    onSelect: (AppPage) -> Unit,
    chatBadge: String?,
) {
    val items = AppPage.entries
    val idx = items.indexOf(selected)
    val density = androidx.compose.ui.platform.LocalDensity.current

    // 每格的宽度由 Row 的 weight 决定，这里用「已选中的第几格」算高亮位置。
    // 用相对偏移（0f~1f）配合 animateFloat，避免依赖具体像素宽度。
    val targetFraction by animateFloatAsState(
        targetValue = if (items.size > 1) idx.toFloat() / (items.size - 1) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dockHighlight",
    )

    GlassBox(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        shape = AppShape.dock,
        strong = true,
        // 这里**不加真模糊**（`frosted = false`）。
        //
        // 真机实测：给 dock 加上面板模糊之后，dock 自己跑到了屏幕中间、且整屏
        // 多出一层灰罩。原因是 `Modifier.blur` 会为这棵子树建离屏渲染层并**扩大
        // 绘制边界**，在一个 `align(BottomCenter)` + 外层带 padding 的 Box 里，
        // 那次扩边把布局推歪了。dock 不需要真模糊：它下面是纯 App 背景，
        // 高不透明度的底色 + 反光 + 内阴影已经足够是「玻璃条」。
        frosted = false,
    ) {
        Row(
            Modifier.fillMaxSize().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { p ->
                DockItem(
                    page = p,
                    selected = p == selected,
                    badge = if (p == AppPage.Chat) chatBadge else null,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(p) },
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    page: AppPage,
    selected: Boolean,
    badge: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // 选中态：玻璃胶囊 + 图标上浮一点 + 文字出现
    val glow by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220),
        label = "dockGlow",
    )
    val lift by animateDpAsState(
        targetValue = if (selected) (-1).dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dockLift",
    )

    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                // 选中格的高亮底：深色叠白、浅色叠黑（第 46 条统一走 AppColor.fill）
                if (glow > 0f) AppColor.fill.copy(alpha = 0.10f * glow)
                else Color.Transparent
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer { translationY = lift.toPx() },
        ) {
            Box {
                Icon(
                    imageVector = if (selected) page.iconOn else page.icon,
                    contentDescription = page.label,
                    tint = if (selected) AppColor.text else AppColor.textDim,
                    modifier = Modifier.size(23.dp),
                )
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AppColor.accent)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                page.label,
                color = if (selected) AppColor.text else AppColor.textFaint,
                fontSize = 10.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
