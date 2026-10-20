/*
 * Adapted from Kyant0/AndroidLiquidGlass catalog (Apache License 2.0).
 * Source revision: 896a94a3ade1cc1a940b92365f942a34971fecda (Android 1.0.6).
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Changes: Kudo theme/dimensions, material fallback, fixed gesture area,
 * independent preview/selection, cancel/publish handling, sampling semantics
 * and separate pressed/dragged position updates.
 * Full license: app/src/main/assets/licenses/backdrop-demo-LICENSE.txt
 * Further adapted from woliubang256/kudo-android (Apache License 2.0) for CCTV 下载器.
 */
package com.chouchang.cctvdl.ui.components.liquid

import android.os.Build
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.chouchang.cctvdl.ui.theme.GlassMode
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
internal fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    backdrop: Backdrop,
    mode: GlassMode,
    tabsCount: Int,
    actionIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(previewIndex: Int?) -> Unit,
) {
    val navigationHeight = 56.dp
    val restingLensHeight = navigationHeight - 8.dp
    val surface = MaterialTheme.colorScheme.surface
    val isLightTheme = surface.luminance() > 0.5f
    val canBlur = mode != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = mode == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    val containerColor = surface.copy(alpha = if (canBlur) 0.2f else 0.94f)
    val resolver = LocalContext.current.contentResolver
    val systemMotionEnabled by produceState(
        initialValue = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f,
        resolver,
    ) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        value = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
    val tabsBackdrop = rememberLayerBackdrop()
    val selectedIndex by rememberUpdatedState(selectedTabIndex)
    val selectTab by rememberUpdatedState(onTabSelected)

    BoxWithConstraints(modifier.height(navigationHeight), contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) { (constraints.maxWidth - 8.dp.toPx()) / tabsCount }
        // Scale the optical profile together with the demo's 56dp resting lens.
        val lensProfileScale = restingLensHeight / 56.dp
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        var dragPosition by remember { mutableStateOf<Float?>(null) }
        var dragDistance by remember { mutableFloatStateOf(0f) }

        fun restorePanel() {
            animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
        }

        val dampedDragAnimation = remember(animationScope, tabWidth, isLtr, tabsCount, actionIndex) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = { position ->
                    dragDistance = 0f
                    val physicalIndex = (position.x - with(density) { 4.dp.toPx() }) / tabWidth - 0.5f
                    dragPosition = (if (isLtr) physicalIndex else tabsCount - 1 - physicalIndex)
                        .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    animatePressedValue(dragPosition!!)
                },
                onDragStopped = {
                    val candidate = (dragPosition ?: targetValue).fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    val target = if (dragDistance > touchSlop && candidate != actionIndex) candidate else selectedIndex()
                    dragPosition = null
                    animateToValue(target.toFloat())
                    if (target != selectedIndex()) selectTab(target)
                    restorePanel()
                },
                onDragCancelled = {
                    dragPosition = null
                    animateToValue(selectedIndex().toFloat())
                    restorePanel()
                },
                onDrag = drag@ { _, dragAmount ->
                    // The inspector emits a zero delta on down; keep the initial press spring.
                    if (dragAmount.x == 0f) return@drag
                    dragDistance += abs(dragAmount.x)
                    val target = ((dragPosition ?: targetValue) + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                        .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    dragPosition = target
                    updateValue(target)
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                },
            )
        }
        val selected = selectedIndex()
        LaunchedEffect(selected, dampedDragAnimation) {
            if (dragPosition == null && dampedDragAnimation.targetValue != selected.toFloat()) {
                dampedDragAnimation.animateToValue(selected.toFloat())
            }
        }
        val previewIndex by remember(dampedDragAnimation) {
            derivedStateOf {
                val index = dragPosition?.fastRoundToInt()
                    ?: if (dampedDragAnimation.pressProgress > 0.001f) dampedDragAnimation.value.fastRoundToInt()
                    else selectedIndex()
                index.takeUnless { it == actionIndex }
            }
        }
        val interactiveHighlight = remember(animationScope, dampedDragAnimation, isLtr) {
            InteractiveHighlight(animationScope, position = { size, _ ->
                Offset(
                    (if (isLtr) dampedDragAnimation.value + 0.5f
                    else tabsCount - dampedDragAnimation.value - 0.5f) * tabWidth + panelOffset,
                    size.height / 2f,
                )
            })
        }

        // The official demo's three drawing layers; only the fixed outer box handles gestures.
        Box(Modifier.fillMaxSize()
            .then(dampedDragAnimation.modifier)
            .then(interactiveHighlight.gestureModifier)) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            if (canBlur) { vibrancy(); blur(4.dp.toPx()) }
                            if (canRefract) lens(24.dp.toPx(), 24.dp.toPx())
                        },
                        layerBlock = {
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, dampedDragAnimation.pressProgress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .testTag("navigation-surface")
                    .height(navigationHeight).fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = { content(previewIndex) },
            )

            CompositionLocalProvider(
                LocalLiquidBottomTabSampling provides true,
                LocalLiquidBottomTabScale provides { lerp(1f, 1.2f, dampedDragAnimation.pressProgress) },
            ) {
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                if (canBlur) { vibrancy(); blur(4.dp.toPx()) }
                                if (canRefract) lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                            },
                            highlight = { Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress) },
                            onDrawSurface = {
                                drawRect(containerColor)
                                // The lens samples this surface before the labels, keeping their colors intact.
                                drawRect(if (isLightTheme) Color.Black.copy(0.12f) else Color.White.copy(0.12f),
                                    alpha = 1f - dampedDragAnimation.pressProgress)
                            },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(restingLensHeight).fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = { content(previewIndex) },
                )
            }

            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        translationX = (if (isLtr) dampedDragAnimation.value
                        else tabsCount - 1 - dampedDragAnimation.value) * tabWidth + panelOffset -
                            4.dp.toPx() * dampedDragAnimation.pressProgress
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            if (canRefract) lens(10.dp.toPx() * lensProfileScale * progress,
                                14.dp.toPx() * lensProfileScale * progress, chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress) },
                        shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(radius = 8.dp * lensProfileScale * progress, alpha = progress)
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            // A zero-duration position jump is not a physical drag velocity.
                            val velocity = if (!systemMotionEnabled || animationScope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f) 0f
                                else dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        },
                    )
                    .testTag("navigation-selection")
                    // Resting end caps share the bar's 4dp inset; dragging restores the wider lens.
                    .height(restingLensHeight).width(with(density) { tabWidth.toDp() } + 8.dp * dampedDragAnimation.pressProgress),
            )
        }
    }
}
