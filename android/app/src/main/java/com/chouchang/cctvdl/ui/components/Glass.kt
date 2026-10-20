package com.chouchang.cctvdl.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.chouchang.cctvdl.ui.theme.Accent
import com.chouchang.cctvdl.ui.theme.GlassMode
import com.chouchang.cctvdl.ui.theme.LocalDark
import com.chouchang.cctvdl.ui.theme.LocalGlass
import com.chouchang.cctvdl.ui.theme.OnAccent
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 是否播放循环动效（系统「移除动画」或设置中关闭时为 false） */
val LocalMotion = staticCompositionLocalOf { true }

/** 背景层：所有内容卡片从这里取样（卡片本身不能在自己的取样源里） */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 三档材质的玻璃面：
 *  - 液态玻璃（Android 13+）：背景模糊 + 边缘折射 + 高光
 *  - 背景模糊（Android 12）：模糊 + 提亮
 *  - 半透明（Android 8–11 或手动选择）：不取样，只画半透明表面
 */
@Composable
fun Modifier.glass(
    corner: Dp = 28.dp,
    backdrop: Backdrop? = LocalBackdrop.current,
    tint: Color = Color.Unspecified,
    capsule: Boolean = false,
    refraction: Float = 1f,
    elevated: Boolean = true,
    opacity: Float = 0f,
): Modifier {
    val mode = LocalGlass.current
    val dark = LocalDark.current
    val surface = MaterialTheme.colorScheme.surface
    val outline = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f)
    val shapeProvider = { if (capsule) Capsule() else RoundedRectangle(corner) }
    if (backdrop == null || mode == GlassMode.TRANSLUCENT) {
        val fill = if (tint != Color.Unspecified) tint.copy(alpha = 0.9f)
            else surface.copy(alpha = if (dark) 0.78f else 0.82f)
        val shape = if (capsule) CircleShape else RoundedCornerShape(corner)
        return this
            .then(if (elevated) Modifier.graphicsLayer { shadowElevation = 18f; this.shape = shape; clip = false; ambientShadowColor = Color.Black.copy(alpha = 0.18f); spotShadowColor = Color.Black.copy(alpha = 0.14f) } else Modifier)
            .clip(shape)
            .background(fill)
            .drawWithContent {
                drawContent()
                // 顶部细高光，营造材质的厚度
                drawRect(Brush.verticalGradient(listOf(outline, Color.Transparent), endY = 2.dp.toPx() * 18))
            }
    }
    val canRefract = mode == GlassMode.LIQUID
    val container = when {
        tint != Color.Unspecified -> tint.copy(alpha = 0.62f)
        dark -> surface.copy(alpha = maxOf(0.34f, opacity * 0.85f))
        else -> surface.copy(alpha = maxOf(0.46f, opacity))
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeProvider,
        effects = {
            vibrancy()
            colorControls(brightness = if (dark) 0f else 0.06f, saturation = 1.35f)
            blur((if (dark) 14.dp else 18.dp).toPx())
            if (canRefract) lens(14.dp.toPx() * refraction, 28.dp.toPx() * refraction, depthEffect = true)
        },
        highlight = { if (canRefract) Highlight.Default.copy(alpha = if (dark) 0.55f else 0.9f) else Highlight.Plain },
        shadow = { if (elevated) Shadow(radius = 24.dp, alpha = if (dark) 0.45f else 0.16f) else Shadow(alpha = 0f) },
        onDrawSurface = { drawRect(container) },
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
    padding: PaddingValues = PaddingValues(20.dp),
    tint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(0.55f, 420f), label = "press")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(corner, tint = tint)
            .then(if (onClick != null) Modifier.clickable(interaction, null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 环境光背景：缓慢漂移的大色块，颜色随当前视频封面过渡
// ---------------------------------------------------------------------------
@Composable
fun AmbientBackground(palette: List<Color>, modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    val base = MaterialTheme.colorScheme.background
    val colors = (0 until 4).map { i ->
        animateColorAsState(palette[i % palette.size], tween(1400, easing = FastOutSlowInEasing), label = "c$i").value
    }
    val motion = LocalMotion.current
    val phaseState: State<Float> = if (motion) {
        rememberInfiniteTransition(label = "ambient").animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing)), label = "phase")
    } else remember { mutableFloatStateOf(0.12f) }
    Canvas(modifier) {
        drawRect(base)
        val w = size.width
        val h = size.height
        val a = (phaseState.value * 2 * PI).toFloat()
        val blobs = listOf(
            Triple(Offset(w * (0.18f + 0.10f * cos(a)), h * (0.12f + 0.05f * sin(a))), w * 0.95f, colors[0]),
            Triple(Offset(w * (0.92f + 0.08f * sin(a * 1.3f)), h * (0.30f + 0.08f * cos(a))), w * 0.85f, colors[1]),
            Triple(Offset(w * (0.12f + 0.12f * sin(a + 1f)), h * (0.68f + 0.06f * cos(a * 0.7f))), w * 0.9f, colors[2]),
            Triple(Offset(w * (0.85f + 0.10f * cos(a + 2f)), h * (0.95f + 0.04f * sin(a))), w * 0.9f, colors[3]),
        )
        val strength = if (dark) 0.55f else 0.62f
        for ((c, r, col) in blobs) {
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = strength), col.copy(alpha = strength * 0.35f), Color.Transparent), c, r), r, c)
        }
        // 底部压暗 / 提亮，保证文字对比度
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, base.copy(alpha = if (dark) 0.55f else 0.35f)), startY = h * 0.45f))
    }
}

val DefaultPaletteLight = listOf(Color(0xFF86B6FF), Color(0xFFFFC56B), Color(0xFFB9A2FF), Color(0xFF7FE0CC))
val DefaultPaletteDark = listOf(Color(0xFF2D5BFF), Color(0xFFE08A12), Color(0xFF7438FF), Color(0xFF00A99D))

// ---------------------------------------------------------------------------
// 小部件
// ---------------------------------------------------------------------------

/** 渐变流光：加载占位与进度条 */
fun Modifier.shimmer(active: Boolean = true): Modifier = composed {
    if (!active || !LocalMotion.current) return@composed this
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "x")
    val hi = if (LocalDark.current) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f)
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(Brush.linearGradient(listOf(Color.Transparent, hi, Color.Transparent),
            start = Offset(w * x - w * 0.4f, 0f), end = Offset(w * x, size.height)))
    }
}

@Composable
fun Skeleton(modifier: Modifier, corner: Dp = 10.dp) {
    val c = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Box(modifier.clip(RoundedCornerShape(corner)).background(c).shimmer())
}

/** 玻璃胶囊按钮（材质随设置降级） */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 52.dp,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(0.5f, 500f), label = "btn")
    val fg = if (primary) OnAccent else MaterialTheme.colorScheme.onSurface
    val alpha = if (enabled) 1f else 0.45f
    Box(
        modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .then(if (primary) Modifier.clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFFFC94A), Accent, Color(0xFFEB9F12))))
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), endY = size.height * 0.55f))
                } else Modifier.glass(capsule = true, elevated = false, refraction = 0.6f))
            .then(if (primary && loading) Modifier.shimmer() else Modifier)
            .clickable(interaction, null, enabled = enabled && !loading, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) Spinner(fg, 18.dp) else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = Color.Unspecified) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(0.45f, 600f), label = "ib")
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(capsule = true, elevated = false, refraction = 0.5f)
            .clickable(interaction, null, role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(size * 0.48f), tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint)
    }
}

/** 旋转的弧线加载指示 */
@Composable
fun Spinner(color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r")
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = r }) {
        drawArc(color, 0f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(size.toPx() * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** 玻璃分段选择器：选中块带弹性滑动 */
@Composable
fun <T> GlassSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: (T) -> Boolean = { true }) {
    val haptic = LocalHapticFeedback.current
    var width by remember { mutableStateOf(IntSize.Zero) }
    val index = options.indexOf(selected).coerceAtLeast(0)
    val pos by animateFloatAsState(index.toFloat(), spring(0.72f, 380f), label = "seg")
    val density = LocalDensity.current
    val dark = LocalDark.current
    Box(modifier.height(40.dp).glass(capsule = true, elevated = false, refraction = 0.4f).padding(3.dp).onSizeChanged { width = it }) {
        if (width.width > 0) {
            val segW = with(density) { (width.width / options.size).toDp() }
            Box(Modifier.offset(x = segW * pos).width(segW).fillMaxHeight().clip(CircleShape)
                .background(if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)))
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == index
                val ok = enabled(o)
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .clickable(enabled = ok, role = Role.RadioButton) { if (!on) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(o) } },
                    contentAlignment = Alignment.Center) {
                    Text(label(o), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = when { !ok -> 0.3f; on -> 1f; else -> 0.62f }))
                }
            }
        }
    }
}

/** 小号胶囊标签 */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, background: Color = color.copy(alpha = 0.1f), icon: ImageVector? = null) {
    Row(modifier.clip(CircleShape).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun PageHeader(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 渐入 + 上浮：列表与卡片的出场 */
fun Modifier.entrance(index: Int = 0): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(520, delayMillis = 45 * index.coerceAtMost(8), easing = EaseOutCubic), label = "in")
    graphicsLayer { alpha = p; translationY = (1f - p) * 36.dp.toPx(); scaleX = 0.98f + 0.02f * p; scaleY = scaleX }
}

@Composable
fun ContentAlpha(alpha: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = alpha)) { content() }
}
