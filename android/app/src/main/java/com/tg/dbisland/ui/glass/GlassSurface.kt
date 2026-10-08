package com.tg.dbisland.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tg.dbisland.ui.AppColor

/**
 * 液体玻璃表面 —— 全应用统一的「玻璃卡片 / 玻璃 dock」外观。
 *
 * ## 视觉构成（从下往上）
 *   1. **背景提亮**：一层很淡的白色渐变，模拟玻璃把背后内容「抬亮」；
 *   2. **内阴影**：用 AGSL（API 33+）沿圆角内侧算一圈距离场暗边，让玻璃有厚度；
 *      低版本退化成两层由粗到细的暗描边；
 *   3. **镜面反光**：一条斜向高光带（玻璃的「湿」感主要来自它）；
 *   4. **描边**：1dp 亮边（上亮下暗）。
 *
 * ## 关于「真实背景折射」的如实说明
 * SukiSU 管理器那套玻璃（`component/liquid/Lens.kt`，改编自 Kyant0/AndroidLiquidGlass，
 * Apache-2.0）能把**背后已经画好的内容**当 shader 输入真正折射 —— 它靠的是
 * miuix-kmp 的 `BackdropEffectScope.runtimeShaderEffect`，属于一整套 Compose
 * Multiplatform backdrop 管线。本机的 Gradle 缓存里没有 miuix / kyant0:backdrop，
 * 而 Maven Central 在这台机器上不可达，**装不进来**；所以本文件是**等价的自实现**：
 * 同样的圆角矩形 SDF + 边缘折射算法（见 [GlassShader]，未启用）、同样的
 * 「提亮 + 内阴影 + 反光 + 描边」分层，但**不做逐像素的背景位移**。
 * 这一点已如实写进 CHANGELOG 的「未做 / 做不到」。
 */
object GlassStyle {
    /**
     * 玻璃自身的**底色**（近实的深色）。
     *
     * 真机实测教训：最初只用白色加亮（alpha 0.08 左右）当玻璃，放在正文上方时
     * 下面的字读得一清二楚、和玻璃上的字糊成一片 —— 完全不可读。真实玻璃是
     * **先挡光再反光**，所以这里必须有一层高不透明度的深色底（[surfaceAlpha]），
     * 反光/内阴影/描边都建在它之上。
     */
    val surface: Color
        get() = if (AppColor.isDark) Color(0xFF141922) else Color(0xFFF7F9FE)
    /**
     * 普通卡片（静止铺在背景上）的底色不透明度。
     *
     * 卡片下面就是 App 背景（纯色渐变），没有内容会被「透出来」，所以只要
     * 保证卡片上的字可读即可。0.90 足够。
     */
    const val surfaceAlpha = 0.90f

    /**
     * 面板（浮在内容之上）的底色不透明度。
     *
     * 0.92 是**真机调出来的**，卡在两条边界中间：
     *   · 太低（试过 0.80）：面板内容（会话名/摘要）和背后的聊天文字糊在一起，
     *     真机采样显示面板内文字与底面只差 10~20 级灰度，等于看不见内容；
     *   · 太高（1.0）：变成不透明板子，背后完全没有透光，就没有玻璃感。
     * 0.92 时面板上的白字与底面差 ≈ 200 级，清晰可读；背后内容的明暗还能
     * 透出一点点 —— 这就是这块面板的「磨砂」观感来源。
     *
     * 一句话：**可读性优先，磨砂感靠底色 + 上下渐变反光 + 内阴影 + 亮边。**
     */
    const val panelAlpha = 0.92f

    /** 玻璃底色（很淡的白，叠在深色底之上，做质感）。亮色下改成**黑**的极淡叠加，
     *  否则白上加白什么都看不出来。 */
    val tintTop: Color get() = if (AppColor.isDark) Color(0x14FFFFFF) else Color(0x0A000000)
    val tintBottom: Color
        get() = if (AppColor.isDark) Color(0x06FFFFFF) else Color(0x03000000)

    /** 描边亮边。 */
    val strokeTop: Color get() = if (AppColor.isDark) Color(0x3AFFFFFF) else Color(0x2E000000)
    val strokeBottom: Color
        get() = if (AppColor.isDark) Color(0x0FFFFFFF) else Color(0x0A000000)

    /**
     * 内阴影强度。
     *
     * 第 46 条起**浅色下减半**（0.45 → 0.22）：内阴影画的是「贴着圆角内侧的一圈
     * 黑边」，在近黑底上 0.45 才有厚度感，但浅色玻璃上同一圈黑边会显得脏、像
     * 描歪了的轮廓。亮色下给一半，观感是「薄玻璃的柔和边」，不再是黑框。
     */
    val innerShadowAlpha: Float get() = if (AppColor.isDark) 0.45f else 0.22f
}

/** 把 Compose [Shape] 的圆角换算成像素（玻璃形状都是圆角矩形，取四角最大值）。 */
private fun Shape.cornerRadiusPx(size: Size, density: Density): Float {
    val o = createOutline(size, androidx.compose.ui.unit.LayoutDirection.Ltr, density)
    return if (o is Outline.Rounded) {
        maxOf(
            o.roundRect.topLeftCornerRadius.x,
            o.roundRect.topRightCornerRadius.x,
            o.roundRect.bottomRightCornerRadius.x,
            o.roundRect.bottomLeftCornerRadius.x,
        ).coerceAtMost(minOf(size.width, size.height) / 2f)
    } else 0f
}

/**
 * 画一个玻璃表面。
 *
 * [shape] 决定圆角与裁剪形状；[strong] 用于 dock 这类需要更明显反光的部件；
 * [fillColor]/[fillAlpha] 是玻璃自身的**底色** —— 真机实测：只靠白色加亮
 * （没有底色）时，玻璃面板下面的文字会读得很清楚，叠在正文上就糊成一片。
 * 所以玻璃必须有一层**近实的深色底**（默认 [GlassStyle.surface]），
 * 折射/反光/内阴影都建在它之上 —— 这也正是真实玻璃的物理：先挡光，再反光。
 */
fun Modifier.glassSurface(
    shape: Shape,
    density: Density,
    strong: Boolean = false,
    fillColor: Color = GlassStyle.surface,
    fillAlpha: Float = GlassStyle.surfaceAlpha,
): Modifier = this
    .drawWithCache {
        val w = size.width
        val h = size.height
        val radius = shape.cornerRadiusPx(size, density)
        val radii = androidx.compose.ui.geometry.CornerRadius(radius)
        // 深色底（挡住背后内容，保证文字可读）
        val body = fillColor.copy(alpha = fillAlpha)
        // 上亮下暗的一点点渐变，让底面不是死板的纯色
        val tint = Brush.verticalGradient(
            colors = listOf(GlassStyle.tintTop, GlassStyle.tintBottom),
            startY = 0f, endY = h,
        )
        val sheen = Brush.linearGradient(
            colors = listOf(
                Color(if (AppColor.isDark) 0x00FFFFFF else 0x00000000),
                // 反光是「白上更白 / 亮上更亮」：深色用白、亮色用黑（都是极淡）
                if (AppColor.isDark) Color(if (strong) 0x22FFFFFF else 0x14FFFFFF)
                else Color(if (strong) 0x14000000 else 0x0A000000),
                Color(if (AppColor.isDark) 0x00FFFFFF else 0x00000000),
            ),
            start = Offset(w * 0.02f, 0f),
            end = Offset(w * 0.98f, h),
        )
        val strokeBrush = Brush.verticalGradient(
            colors = listOf(GlassStyle.strokeTop, GlassStyle.strokeBottom),
            startY = 0f, endY = h,
        )
        onDrawBehind {
            drawRoundRect(color = body, size = size, cornerRadius = radii)
            drawRoundRect(brush = tint, size = size, cornerRadius = radii)
            drawRoundRect(brush = sheen, size = size, cornerRadius = radii)
            drawInnerShadow(size, radius, density)
            drawRoundRect(
                brush = strokeBrush,
                topLeft = Offset(0.5f, 0.5f),
                size = Size((w - 1f).coerceAtLeast(0f), (h - 1f).coerceAtLeast(0f)),
                cornerRadius = radii,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }

/** 内阴影：用 AGSL 在圆角内侧画一圈柔和暗边；AGSL 不可用时退化成两层描边。
 *
 * 这是「玻璃有厚度」的关键一层 —— 只有渐变没有它，卡片会显得像半透明色块。
 */
private fun DrawScope.drawInnerShadow(size: Size, radiusPx: Float, density: Density) {
    val shader = buildInnerShadowShader(size, radiusPx, density)
    if (shader == null) {
        // 退化：两层由粗到细的暗描边，近似一圈羽化内阴影
        val r = radiusPx.coerceAtMost(minOf(size.width, size.height) / 2f)
        val cr = androidx.compose.ui.geometry.CornerRadius(r)
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.10f * GlassStyle.innerShadowAlpha),
            topLeft = Offset(0.5f, 0.5f),
            size = Size(size.width - 1f, size.height - 1f),
            cornerRadius = cr,
            style = Stroke(width = 6.dp.toPx()),
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.32f * GlassStyle.innerShadowAlpha),
            topLeft = Offset(0.5f, 0.5f),
            size = Size(size.width - 1f, size.height - 1f),
            cornerRadius = cr,
            style = Stroke(width = 2.dp.toPx()),
        )
        return
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRect(
            0f, 0f, size.width, size.height,
            android.graphics.Paint().apply { isAntiAlias = true; this.shader = shader },
        )
    }
}

private const val INNER_SHADOW_SHADER = """
uniform float2 size;
uniform float radius;
uniform float blur;
uniform float alpha;

float sdRoundedRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - (halfSize - float2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float d = sdRoundedRect(p, halfSize, radius);
    // 只在「贴着边缘内侧 blur 像素」的窄带里有值，越靠边越黑
    float t = 1.0 - smoothstep(0.0, blur, max(-d, 0.0));
    float edge = 1.0 - smoothstep(-blur, 0.0, d);
    float a = clamp(t * edge, 0.0, 1.0) * alpha;
    return half4(0.0, 0.0, 0.0, a);
}
"""

private fun buildInnerShadowShader(
    size: Size,
    radiusPx: Float,
    density: Density,
): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (size.width <= 0f || size.height <= 0f) return null
    return try {
        RuntimeShader(INNER_SHADOW_SHADER).apply {
            setFloatUniform("size", size.width, size.height)
            setFloatUniform(
                "radius",
                radiusPx.coerceAtMost(minOf(size.width, size.height) / 2f),
            )
            setFloatUniform("blur", with(density) { 5.dp.toPx() })
            setFloatUniform("alpha", GlassStyle.innerShadowAlpha)
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * 一块**真正的磨砂玻璃面板**。
 *
 * 与 [glassSurface] 的区别（这是被用户纠正过的关键点）：
 *
 * | | 糊什么 | 用在哪 |
 * |---|---|---|
 * | [glassSurface] | 什么都不糊（只有底色 + 反光 + 内阴影） | 内容区里的卡片（它下面就是纯背景，没有东西可糊） |
 * | [frostedGlassPanel] | **面板自己这一层**真模糊 + 半透明底 | 会话框、dock 这类**浮在别的内容之上**的面板 |
 *
 * 为什么必须区分：`Modifier.blur` 只模糊**它作用的那一层**画出来的像素，
 * 它不会去采样背后的东西。所以
 *   · 把 blur 加在「面板背后的内容」上 → 那内容被糊了，但面板自己还是一块
 *     半透明板子，观感是「整个页面被糊了」（用户第一眼就看出来了，直接说
 *     「你怎么把整个页面都磨砂了，只要弹出的选项框是磨砂」）；
 *   · 把 blur 加在**面板自己**上 → 面板的内容（文字/图标）跟着糊，不可读；
 *   · 正确做法 = **面板自己的纯色底只模糊它自己**（[frostedGlassPanel] 把模糊
 *     与填充画在同一个 draw 层里），文字作为**子节点画在模糊层之上**，是清晰的。
 *     这也是真实磨砂玻璃的样子：玻璃糊、贴在玻璃**前面**的字不糊。
 *
 * 视觉效果：模糊层的边缘本来就有柔和衰减，所以面板边缘自带一圈「磨砂渗出去」
 * 的效果，正是磨砂玻璃的边界感。
 *
 * API 31 以下没有 `RenderEffect`，此时只剩半透明底色（[fillAlpha] 给得比较高，
 * 保证文字始终可读）。
 */
/**
 * 一块玻璃/磨砂面板的绘制器。
 *
 * [radius] 与 [frostRadius] 的区别：前者是**圆角半径**（dp）—— 用来算 AGSL 内阴影
 * 的距离场；后者是**磨砂半径**（dp）—— 现在只作为「这块面板是否算磨砂面板」的
 * 语义标记保留（真正的模糊已按注释里的理由移除，见 [frostedBackdrop]）。
 */
fun Modifier.frostedGlassPanel(
    shape: Shape,
    density: Density,
    radius: Dp,
    fillColor: Color = GlassStyle.surface,
    fillAlpha: Float = GlassStyle.panelAlpha,
): Modifier = this.drawWithCache {
    val w = size.width
    val h = size.height
    val radiusPx = shape.cornerRadiusPx(size, density)
    val radii = androidx.compose.ui.geometry.CornerRadius(radiusPx)
    val body = fillColor.copy(alpha = fillAlpha)
    val tint = Brush.verticalGradient(
        colors = listOf(GlassStyle.tintTop, GlassStyle.tintBottom),
        startY = 0f, endY = h,
    )
    val sheen = Brush.linearGradient(
        colors = listOf(
            Color(if (AppColor.isDark) 0x00FFFFFF else 0x00000000),
            if (AppColor.isDark) Color(0x1EFFFFFF) else Color(0x12000000),
            Color(if (AppColor.isDark) 0x00FFFFFF else 0x00000000),
        ),
        start = Offset(w * 0.02f, 0f),
        end = Offset(w * 0.98f, h),
    )
    val strokeBrush = Brush.verticalGradient(
        colors = listOf(GlassStyle.strokeTop, GlassStyle.strokeBottom),
        startY = 0f, endY = h,
    )
    onDrawBehind {
        drawRoundRect(color = body, size = size, cornerRadius = radii)
        drawRoundRect(brush = tint, size = size, cornerRadius = radii)
        drawRoundRect(brush = sheen, size = size, cornerRadius = radii)
        drawInnerShadow(size, radiusPx, density)
        drawRoundRect(
            brush = strokeBrush,
            topLeft = Offset(0.5f, 0.5f),
            size = Size((w - 1f).coerceAtLeast(0f), (h - 1f).coerceAtLeast(0f)),
            cornerRadius = radii,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * 一个玻璃容器：负责给出形状与玻璃外观，内容放在玻璃**上面**
 * （真实玻璃也是这个层序：反光不会被文字盖住）。
 *
 * [frosted] = true 时走 [frostedGlassPanel]（浮在内容之上的**磨砂面板**：更高不
 * 透明度、更明显的反光与内阴影），用于会话框；false 走 [glassSurface]，用于普通卡片。
 *
 * [radius] 同时决定两件事：圆角半径（送进 [frostedGlassPanel] 算 AGSL 内阴影）
 * 与裁剪形状。
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: Shape,
    strong: Boolean = false,
    frosted: Boolean = false,
    radius: Dp = 20.dp,
    fillColor: Color = GlassStyle.surface,
    fillAlpha: Float = if (frosted) GlassStyle.panelAlpha else GlassStyle.surfaceAlpha,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val m = if (frosted) {
        modifier.clip(shape).frostedGlassPanel(shape, density, radius, fillColor, fillAlpha)
    } else {
        modifier.glassSurface(shape, density, strong, fillColor, fillAlpha)
    }
    Box(modifier = m, content = content)
}

/**
 * 真实高斯模糊（`RenderEffect`，API 31+）。
 *
 * **当前没有一处面板在用它。** 保留这个函数是为了把踩过的坑写下来，避免下次
 * 又走一遍：
 *
 *   1. `Modifier.blur` 只模糊**它作用的那一层自己画的像素**，它不会去采样背后的
 *      东西。所以「让面板磨砂」不能靠在面板背后加模糊（那只会把整页糊掉，
 *      用户当场指出「你怎么把整个页面都磨砂了」）。
 *   2. 加在**面板自己**上时，模糊会**向外溢出**（半径越大溢出越多，真机
 *      density 4.0 下 8dp 就溢出约 80px），在近黑背景上表现为「面板外面还有
 *      一圈发灰的雾」，看起来就是「糊的位置多了、有遮罩、还漏边角」。
 *   3. 更糟的是它同时把**面板自己的内容**（会话名/摘要）压成一团灰，真机截图
 *      `dev/ui_rail_ok.png` 里面板内容基本不可读，用户报「看不见内容了」。
 *   4. 给 dock 加这个模糊还会**推歪布局**（dock 从底部跑到屏幕中间），因为离屏
 *      层会扩大绘制边界，而 dock 处在 `align(BottomCenter)` + 外层 padding 的
 *      Box 里。
 *
 * 结论：**面板的「磨砂感」用高不透明度底色 + 上下渐变反光 + 内阴影 + 亮边来做**
 * （见 [frostedGlassPanel]），清晰、可读、不吃离屏渲染，也不动布局。
 * 这个函数只留给「将来要做真正的背后采样折射」时当入口，参数保持原样。
 */
@Suppress("unused")
fun Modifier.frostedBackdrop(radius: Dp, clipToBounds: Boolean = true): Modifier =
    this

/** 供调试/降级判断用：这台机器能不能走 AGSL 路径。 */
val agslAvailable: Boolean get() = GlassShader.supported
