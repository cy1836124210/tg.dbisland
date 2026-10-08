package com.tg.dbisland.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed

/** 一小块玻璃卡片（设置项分组、状态分组都用它）。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    com.tg.dbisland.ui.glass.GlassBox(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.card,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = AppColor.text, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        if (subtitle != null) {
                            Text(subtitle, color = AppColor.textDim, fontSize = 12.sp)
                        }
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** 圆点 + 标题 + 明细 的一行状态（root / LSPosed / 模块 三项自检）。 */
@Composable
fun StatusRow(
    color: Color,
    title: String,
    detail: String,
    icon: ImageVector? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        } else {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColor.text, fontSize = 14.sp)
            if (detail.isNotBlank()) {
                Text(
                    detail, color = AppColor.textDim, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 小标签（pill）。 */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier
            .clip(AppShape.chip)
            .background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 一行「键 — 值」，设置页的信息展示用。 */
@Composable
fun KeyValueRow(
    k: String,
    v: String,
    valueColor: Color = AppColor.textDim,
    mono: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(k, color = AppColor.textDim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Text(
            v, color = valueColor, fontSize = 13.sp, modifier = Modifier.weight(1f),
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace
            else androidx.compose.ui.text.font.FontFamily.Default,
        )
    }
}

/** 水平分隔线（玻璃卡片内部用，很淡）。 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppColor.glassLine),
    )
}

/** 页面大标题区块。 */
@Composable
fun PageHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColor.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, color = AppColor.textDim, fontSize = 12.5.sp)
            }
        }
        trailing?.invoke()
    }
}

/**
 * 底部 dock 的磨砂半径（dp）—— **现在只是语义标记**。
 *
 * 第 38 条最终没有对任何面板使用 `Modifier.blur`（原因见
 * [com.tg.dbisland.ui.glass.frostedBackdrop] 的注释）。dock 的「磨砂」由
 * `GlassBox(frosted = true)` 的高不透明度底 + 渐变反光 + 内阴影提供，
 * 与这个值无关。
 *
 * 保留它是为了给**将来做真实 backdrop 折射**留一个统一入口：那时
 * 「dock 该糊多少」和「页面在滚时要不要停」这两个决定仍然需要一个共同的位置。
 */
val LocalDockFrost = compositionLocalOf<androidx.compose.ui.unit.Dp> {
    androidx.compose.ui.unit.Dp(8f)
}

/** 页面用这个把「我在滚动」翻译成 dock 的磨砂半径（滚动时暂停，见 [LocalDockFrost]）。 */
fun dockFrostFor(isScrolling: Boolean): androidx.compose.ui.unit.Dp =
    if (isScrolling) androidx.compose.ui.unit.Dp(0f) else androidx.compose.ui.unit.Dp(8f)

/**
 * 订阅 [kotlinx.coroutines.flow.StateFlow] 的便捷包装。

 *
 * 为什么不用 `collectAsStateWithLifecycle`：那需要
 * `androidx.lifecycle:lifecycle-runtime-compose`（我们其实已经依赖了），
 * 但它多一层「STOPPED 时停止收集」的语义，而 [BridgeHub.state] 是**内存里的
 * StateFlow**（没有冷流开销、没有 IO），停止收集没有任何收益，反而让
 * 「切回前台要等一帧才有数据」这种小毛病出现。所以统一用最直接的 collect。
 */
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe(): State<T> =
    collectAsState()

/**
 * 无涟漪点击。
 *
 * 玻璃界面里默认的 Material 涟漪会在玻璃层上糊出一块不透明的水波，
 * 把「反光 + 内阴影」全盖掉，所以统一换成「无 indication」的点击。
 * 用 `composed` 而不是 `@Composable fun Modifier.xxx()`：这样它能像普通
 * Modifier 一样在任意位置链式调用（包括非 @Composable 的 lambda 里）。
 */
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    clickable(
        enabled = enabled,
        interactionSource = src,
        indication = null,
        onClick = onClick,
    )
}

/** 圆形玻璃图标按钮（清空日志、刷新等）。 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = AppColor.textDim,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            // 第 46 条：深色叠白、浅色叠黑（写死白色在浅色主题里等于没有层次）
            .background(AppColor.fill.copy(alpha = 0.06f))
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}
