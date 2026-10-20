/*
 * Adapted from Kyant0/AndroidLiquidGlass catalog (Apache License 2.0).
 * Source revision: 896a94a3ade1cc1a940b92365f942a34971fecda (Android 1.0.6).
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Changes: package/internal visibility, theme accent, enabled state,
 * full-height tap target, current callbacks and single-value range handling.
 * Full license: app/src/main/assets/licenses/backdrop-demo-LICENSE.txt
 * Further adapted from woliubang256/kudo-android (Apache License 2.0) for CCTV 下载器.
 */
package com.chouchang.cctvdl.ui.components.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.chouchang.cctvdl.ui.theme.Accent
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isLightTheme = !com.chouchang.cctvdl.ui.theme.LocalDark.current
    val accentColor = Accent
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberLayerBackdrop()
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val canInteract by rememberUpdatedState(enabled && valueRange.endInclusive > valueRange.start)
    val span = valueRange.endInclusive - valueRange.start
    fun normalize(value: Float) = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth
        val latestTrackWidth by rememberUpdatedState(trackWidth.coerceAtLeast(1))

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        var settleRevision by remember { mutableIntStateOf(0) }
        // Position is a continuous fraction, independent of the number of floors or layout width.
        val dampedDragAnimation = remember(animationScope, valueRange, isLtr) {
            var dragValue = normalize(value())
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = normalize(value()),
                valueRange = 0f..1f,
                visibilityThreshold = .0001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = { didDrag = false; dragging = true; dragValue = this.value },
                onDragStopped = {
                    if (didDrag) settleRevision++
                    dragging = false
                },
                onDrag = { _, dragAmount ->
                    if (!canInteract || dragAmount.x == 0f) return@DampedDragAnimation
                    didDrag = true
                    val delta = dragAmount.x / latestTrackWidth
                    dragValue = (dragValue + if (isLtr) delta else -delta).coerceIn(0f, 1f)
                    updateValue(dragValue)
                    latestChange(valueRange.start + dragValue * span)
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            var handledRevision = settleRevision
            snapshotFlow { if (dragging) null else latestValue() to settleRevision }
                .collectLatest { update ->
                    val (value, revision) = update ?: return@collectLatest
                    val target = normalize(value)
                    if (revision != handledRevision) {
                        handledRevision = revision
                        dampedDragAnimation.animatePressedValue(target)
                    } else if (dampedDragAnimation.targetValue != target) dampedDragAnimation.updateValue(target)
                }
        }

        Box(Modifier.fillMaxWidth().height(48.dp)
            .pointerInput(dampedDragAnimation, enabled) {
                if (canInteract) detectTapGestures { position ->
                    val delta = (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                    val targetValue = (if (isLtr) valueRange.start + delta
                        else valueRange.endInclusive - delta).coerceIn(valueRange)
                    settleRevision++
                    dampedDragAnimation.animateToValue(normalize(targetValue))
                    latestChange(targetValue)
                }
            }
            .layerBackdrop(trackBackdrop), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
                .testTag("liquid-slider-thumb")
        )
    }
}
