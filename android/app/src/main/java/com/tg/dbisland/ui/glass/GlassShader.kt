package com.tg.dbisland.ui.glass

import android.graphics.RuntimeShader
import android.os.Build

/**
 * 液体玻璃（liquid glass）折射着色器。
 *
 * ## 出处与许可
 * 算法与 GLSL 主体**改编自 Kyant0/AndroidLiquidGlass**（Apache License 2.0，
 * <https://github.com/Kyant0/AndroidLiquidGlass>），与 SukiSU-Ultra 管理器界面用的是
 * 同一套「圆角矩形 SDF + 边缘折射（lens）+ 色散（chromatic aberration）」做法 ——
 * 见 `SukiSU-Ultra/manager/app/src/main/java/com/sukisu/ultra/ui/component/liquid/Lens.kt`
 * （该文件头同样标注 "Adapted from Kyant0/AndroidLiquidGlass"）。
 *
 * 我们**没有**直接依赖 SukiSU 用的 `top.yukonga.miuix.kmp` / `io.github.kyant0:backdrop`：
 * 那两个库要求在 `BackdropEffectScope` 里用 miuix 的 `runtimeShaderEffect` 把
 * 背后内容当作 shader 输入，属于整套 Compose Multiplatform 运行时；本机 Gradle 缓存里
 * 没有它们、Maven Central 又不可达，装不进来。所以这里是**等价的自实现**：
 * 用 Android 13+ 原生 `RuntimeShader` + `RenderEffect.createRuntimeShaderEffect`，
 * 由 framework 把「这个 View 背后已经画好的内容」作为 `uniform shader content` 喂进来。
 *
 * 对齐方式（与参考实现的差异，如实记录）：
 *   · 参考实现用 miuix 的 `downscaleFactor` 做降采样模糊后再折射；这里先用
 *     `RenderEffect.createBlurEffect` 做背景模糊，再把模糊结果交给折射 shader，
 *     效果同向（玻璃把背景糊掉 + 边缘抠出折射），但**不是逐像素等价的实现**。
 *   · 参考实现支持 `dispersionEnabled`（7 抽样色散）。这里保留同一算法，
 *     默认关（`chromaticAberration = 0`），因为它在小控件上容易显得脏。
 *
 * ## 能力边界
 *  · 只在 **Android 13（API 33）+** 且支持 AGSL 的设备上启用；低版本一律走
 *    [GlassFallback]（半透明 + 描边 + 内阴影，没有真实折射）。
 *  · `RuntimeShader` 的 shader 字符串必须能被 AGSL 编译，编译失败会抛
 *    `IllegalArgumentException` —— 所有入口都做了 try/catch，失败即降级。
 */
internal object GlassShader {

    /** AGSL 在本机是否可用（API 33+）。真正能不能编译要在 [build] 里试。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 圆角矩形 SDF 工具函数（与参考实现同名同义）。 */
    private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

    /** 主折射 shader：边缘 [refractionHeight] 像素内把背景按 lens 位移量折射。 */
    private const val REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(
        gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
        + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

    /** 主折射 + 7 抽样色散（默认关，留作可选项）。 */
    private const val REFRACTION_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(
        gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
        + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity =
        chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5; color.a += red.a / 7.0;
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5; color.a += green.a / 7.0;
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;

    return color;
}
"""

    /** 折射参数的像素口径（调用方按 dp 给，这里换算成 px）。 */
    data class Params(
        val widthPx: Float,
        val heightPx: Float,
        val cornerRadiiPx: FloatArray,
        val refractionHeightPx: Float,
        val refractionAmountPx: Float,
        val depthEffect: Boolean = true,
        val chromaticAberration: Float = 0f,
    )

    /**
     * 建一个配好 uniform 的 [RuntimeShader]。
     *
     * @return null 表示这台机器用不了（API < 33，或 AGSL 编译失败）——
     *         调用方必须按 [GlassFallback] 渲染，不能假定一定成功。
     */
    fun build(p: Params): RuntimeShader? {
        if (!supported) return null
        val dispersion = p.chromaticAberration > 0f
        return try {
            val shader = RuntimeShader(
                if (dispersion) REFRACTION_DISPERSION_SHADER else REFRACTION_SHADER
            )
            shader.setFloatUniform(
                "size", p.widthPx.coerceAtLeast(1f), p.heightPx.coerceAtLeast(1f),
            )
            // offset 让 shader 的坐标系与控件对齐（参考实现同义）
            shader.setFloatUniform("offset", 0f, 0f)
            shader.setFloatUniform("cornerRadii", p.cornerRadiiPx)
            shader.setFloatUniform("refractionHeight", p.refractionHeightPx)
            // 参考实现里 refractionAmount 取负号传入（向内折射）
            shader.setFloatUniform("refractionAmount", -p.refractionAmountPx)
            shader.setFloatUniform("depthEffect", if (p.depthEffect) 1f else 0f)
            if (dispersion) shader.setFloatUniform("chromaticAberration", p.chromaticAberration)
            shader
        } catch (_: Throwable) {
            null
        }
    }

    /** 四角半径数组（顺序：左上、右上、右下、左下 —— 与 shader 里的 radii.xyzw 一致）。 */
    fun radii(tl: Float, tr: Float, br: Float, bl: Float) =
        floatArrayOf(tl, tr, br, bl)

    fun uniform(tl: Float, tr: Float = tl, br: Float = tl, bl: Float = tr) =
        radii(tl, tr, br, bl)
}
