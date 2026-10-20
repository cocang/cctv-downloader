package com.chouchang.cctvdl.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chouchang.cctvdl.ui.theme.Accent
import com.chouchang.cctvdl.ui.theme.Danger
import com.chouchang.cctvdl.ui.theme.LocalDark
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

/** 整页取样源（背景 + 内容），供悬浮层（底栏、弹出面板）使用 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 玻璃底部面板：弹簧滑入，可下拉关闭，背后内容压暗。
 * 面板自身不在取样源内（与底栏相同的层级规则）。
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalDark.current
    BackHandler(visible, onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dark) 0.5f else 0.24f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } })
        }
        AnimatedVisibility(
            visible, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(0.82f, 320f)) { it } + fadeIn(tween(120)),
            exit = slideOutVertically(tween(240, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(200)),
        ) {
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 10.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
                    .graphicsLayer { translationY = drag.value }
                    .glass(corner = 34.dp, backdrop = LocalPageBackdrop.current, refraction = 1.2f, opacity = 0.8f)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (drag.value > with(density) { 110.dp.toPx() }) onDismiss()
                                    drag.animateTo(0f, spring(0.7f, 400f))
                                }
                            },
                        ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
                    }
                    .padding(top = 10.dp, bottom = 16.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 5.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)))
                if (title != null) {
                    Text(title, Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                content()
            }
        }
    }
}

/** 面板里的一行操作 */
@Composable
fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit, danger: Boolean = false, detail: String? = null) {
    val haptic = LocalHapticFeedback.current
    val color = if (danger) Danger else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = color)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 单选列表行 */
@Composable
fun ChoiceRow(title: String, detail: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val check by animateFloatAsState(if (selected) 1f else 0f, spring(0.5f, 500f), label = "check")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.RadioButton) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(24.dp).graphicsLayer { scaleX = 0.4f + 0.6f * check; scaleY = scaleX; alpha = check }
            .clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color(0xFF231803))
        }
    }
}
