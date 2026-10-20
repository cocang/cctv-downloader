package com.chouchang.cctvdl.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chouchang.cctvdl.ui.components.*
import com.chouchang.cctvdl.ui.components.liquid.LiquidBottomTab
import com.chouchang.cctvdl.ui.components.liquid.LiquidBottomTabs
import com.chouchang.cctvdl.ui.components.liquid.LocalLiquidBottomTabSampling
import com.chouchang.cctvdl.ui.screens.*
import com.chouchang.cctvdl.ui.theme.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

@Composable
fun AppRoot(state: AppState) {
    val dark = LocalDark.current
    val glass = LocalGlass.current
    val bg = rememberLayerBackdrop()
    val page = rememberLayerBackdrop()
    val palette = state.palette ?: if (dark) DefaultPaletteDark else DefaultPaletteLight
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pad = PaddingValues(top = top + 8.dp)

    CompositionLocalProvider(LocalBackdrop provides bg, LocalPageBackdrop provides page) {
        Box(Modifier.fillMaxSize()) {
            // 取样源：背景 + 页面内容（底栏与面板不在其中）
            Box(Modifier.fillMaxSize().layerBackdrop(page)) {
                AmbientBackground(palette, Modifier.fillMaxSize().layerBackdrop(bg))
                AnimatedContent(
                    targetState = state.tab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(280, 60)) + slideInHorizontally(spring(0.9f, 320f)) { dir * it / 10 } + scaleIn(tween(300), 0.985f))
                            .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -dir * it / 14 })
                    },
                    label = "tab",
                ) { t ->
                    when (t) {
                        0 -> ParseScreen(state, pad)
                        1 -> LiveScreen(state, pad)
                        2 -> DownloadsScreen(state, pad)
                        else -> SettingsScreen(state, pad)
                    }
                }
                // 状态栏下方的渐隐，滚动内容进入状态栏时更柔和
                Box(Modifier.fillMaxWidth().height(top + 12.dp).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), Color.Transparent))))
            }

            TabBar(state, page, glass, Modifier.align(Alignment.BottomCenter))
            ToastHost(state, Modifier.align(Alignment.TopCenter).padding(top = top + 8.dp))
            Sheets(state)
        }
    }
}

@Composable
private fun TabBar(state: AppState, backdrop: com.kyant.backdrop.Backdrop, glass: GlassMode, modifier: Modifier) {
    val jobs by state.jobs.collectAsStateWithLifecycle()
    val active = jobs.count { it.status.active }
    val haptic = LocalHapticFeedback.current
    val labels = listOf("解析", "直播", "下载", "设置")
    val icons = listOf(Icons.Rounded.AutoAwesome, Icons.Rounded.LiveTv, Icons.Rounded.Download, Icons.Rounded.Tune)
    Box(modifier.navigationBarsPadding().padding(bottom = 14.dp).widthIn(max = 400.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
        LiquidBottomTabs({ state.tab }, { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); state.tab = it }, backdrop, glass, labels.size, -1, Modifier.fillMaxWidth()) { preview ->
            val sampling = LocalLiquidBottomTabSampling.current
            labels.forEachIndexed { i, label ->
                val chosen = state.tab == i
                val lit = sampling || chosen || preview == i
                val color = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                LiquidBottomTab(chosen, { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); state.tab = i }) {
                    Box {
                        Icon(icons[i], null, Modifier.size(22.dp), tint = color)
                        if (i == 2 && active > 0 && !sampling) {
                            val pulse: State<Float> = if (LocalMotion.current) rememberInfiniteTransition(label = "pulse").animateFloat(0.85f, 1.15f,
                                infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse), label = "p") else remember { mutableFloatStateOf(1f) }
                            Box(Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
                                .clip(CircleShape).background(Danger).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                                Text("$active", color = Color.White, fontSize = 10.sp, lineHeight = 14.sp)
                            }
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 14.sp), color = color)
                }
            }
        }
    }
}

@Composable
private fun ToastHost(state: AppState, modifier: Modifier) {
    val t = state.toast
    var shown by remember { mutableStateOf<Toast?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(t) {
        if (t == null) return@LaunchedEffect
        shown = t; visible = true
        delay(if (t.error) 3200 else 2200)
        visible = false
        delay(300)
        if (state.toast == t) state.toast = null
    }
    AnimatedVisibility(visible, modifier, enter = slideInVertically(spring(0.6f, 400f)) { -it * 2 } + fadeIn(),
        exit = slideOutVertically(tween(260)) { -it * 2 } + fadeOut(tween(200))) {
        val s = shown ?: return@AnimatedVisibility
        Row(Modifier.padding(horizontal = 24.dp).glass(capsule = true, backdrop = LocalPageBackdrop.current, refraction = 0.8f)
            .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = if (s.error) Danger else Success)
            Spacer(Modifier.width(10.dp))
            Text(s.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Sheets(state: AppState) {
    val jobs by state.jobs.collectAsStateWithLifecycle()
    val sheet = state.sheet
    // 关闭动画期间保留最后的内容
    var last by remember { mutableStateOf<Sheet?>(null) }
    if (sheet != null) last = sheet
    val close = { state.sheet = null }
    val s = last
    val title = when (s) {
        is Sheet.Episodes -> s.playlist.name.ifEmpty { "选择剧集" }
        is Sheet.Choice -> choiceTitle(s.key)
        Sheet.FolderName -> "文件夹名称"
        Sheet.Licenses -> "开源许可"
        is Sheet.JobInfo -> "文件信息"
        is Sheet.Channel -> null
        else -> null
    }
    GlassSheet(sheet != null, close, title) {
        when (s) {
            is Sheet.Episodes -> EpisodesContent(state, s.playlist, close)
            is Sheet.JobMenu -> jobs.firstOrNull { it.id == s.jobId }?.let { JobMenuContent(state, it, close) }
            is Sheet.JobInfo -> jobs.firstOrNull { it.id == s.jobId }?.let { JobInfoContent(it) }
            is Sheet.Choice -> ChoiceContent(state, s.key, close)
            Sheet.FolderName -> FolderNameContent(state, close)
            Sheet.Licenses -> LicensesContent()
            is Sheet.Channel -> ChannelSheetContent(state, s.channel, close)
            null -> {}
        }
    }
}
