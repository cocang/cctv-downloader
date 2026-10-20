/*
 * Adapted from Kyant0/AndroidLiquidGlass catalog (Apache License 2.0).
 * Source revision: 896a94a3ade1cc1a940b92365f942a34971fecda (Android 1.0.6).
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Changes: package/internal visibility; drag consumption, separate cancellation,
 * immediate pointer tracking and idle velocity reset.
 * Full license: app/src/main/assets/licenses/backdrop-demo-LICENSE.txt
 * Further adapted from woliubang256/kudo-android (Apache License 2.0) for CCTV 下载器.
 */
package com.chouchang.cctvdl.ui.components.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private var positionJob: Job? = null
    private var velocityJob: Job? = null
    private var shapeJob: Job? = null
    private var pointerPressed = false
    private var trackingDrag = false

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    // A new animation instance must also own a new gesture coroutine (e.g. range changes).
    val modifier: Modifier = Modifier.pointerInput(this@DampedDragAnimation) {
        var distance = Offset.Zero
        inspectDragGestures(
            onDragStart = { down ->
                distance = Offset.Zero
                press()
                onDragStarted(down.position)
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCancelled()
                release()
            }
        ) { change, dragAmount ->
            distance += dragAmount
            trackingDrag = distance.getDistance() > viewConfiguration.touchSlop
            onDrag(size, dragAmount)
            // Keep taps clickable; consume movement so crossing publish cannot click it.
            if (distance.getDistance() > viewConfiguration.touchSlop) change.consume()
        }
    }

    fun press() {
        pointerPressed = true
        trackingDrag = false
        velocityTracker.resetTracking()
        velocityJob?.cancel()
        velocityJob = animationScope.launch { velocityAnimation.snapTo(0f) }
        shapeJob?.cancel()
        shapeJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        // Stop accepting samples before scheduling reset: a finishing position spring is not a drag.
        pointerPressed = false
        trackingDrag = false
        settleVelocity()
        shapeJob?.cancel()
        shapeJob = animationScope.launch {
            awaitFrame()
            val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
            snapshotFlow { abs(valueAnimation.value - valueAnimation.targetValue) < threshold }
                .filter { it }.first()
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        positionJob?.cancel()
        // Input can arrive several times per frame. A restarted spring may never advance;
        // snap without waiting for a frame and reserve the position spring for settling.
        positionJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(target)
            if (pointerPressed && trackingDrag) updateVelocity()
            // No position animation remains to signal idle. Clear stretch after input stops,
            // while preserving the pressed lens; the next sample cancels this pending reset.
            delay(100)
            settleVelocity()
        }
    }

    fun animateToValue(value: Float) {
        animatePressedValue(value)
        release()
    }

    fun animatePressedValue(value: Float) {
        val target = value.coerceIn(valueRange)
        positionJob?.cancel()
        positionJob = animationScope.launch { valueAnimation.animateTo(target, valueAnimationSpec) }
    }

    private fun settleVelocity() {
        velocityJob?.cancel()
        velocityJob = animationScope.launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val target = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        velocityJob?.cancel()
        velocityJob = animationScope.launch { velocityAnimation.animateTo(target, velocityAnimationSpec) }
    }
}
