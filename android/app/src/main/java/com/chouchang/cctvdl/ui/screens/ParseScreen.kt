package com.chouchang.cctvdl.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chouchang.cctvdl.data.Playlist
import com.chouchang.cctvdl.data.Video
import com.chouchang.cctvdl.ui.*
import com.chouchang.cctvdl.ui.components.*
import com.chouchang.cctvdl.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ParseScreen(state: AppState, contentPadding: PaddingValues) {
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    val dark = LocalDark.current
    val parse = state.parse

    // 封面取色 → 环境光
    LaunchedEffect(parse, dark) {
        val v = (parse as? ParseState.Done)?.videos?.firstOrNull() ?: return@LaunchedEffect
        state.palette = runCatching { CoverPalette.extract(ctx, v.cover, dark) }.getOrNull()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader("解析视频", "CCTV 下载器", Modifier.entrance(0).padding(top = 12.dp)) {
            AnimatedVisibility(parse !is ParseState.Idle, enter = scaleIn(spring(0.6f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                GlassIconButton(Icons.Rounded.Refresh, "重新开始", { state.clearParse() })
            }
        }

        InputCard(state, Modifier.padding(horizontal = 16.dp).entrance(1)) { focus.clearFocus() }

        // 剪贴板中检测到链接
        AnimatedVisibility(state.clipboardHint != null && parse is ParseState.Idle,
            enter = expandVertically(spring(0.8f, 300f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val hint = state.clipboardHint.orEmpty()
            GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 22.dp, padding = PaddingValues(14.dp),
                onClick = { state.clipboardHint = null; focus.clearFocus(); state.submit(hint) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp), tint = AccentDeep)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("剪贴板里有央视网链接", style = MaterialTheme.typography.titleMedium)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("解析", style = MaterialTheme.typography.labelLarge, color = AccentDeep)
                }
            }
        }

        AnimatedContent(
            targetState = parse,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(tween(320, 80)) + slideInVertically(spring(0.85f, 260f)) { it / 8 } + scaleIn(tween(320), 0.97f))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(tween(160), 0.98f))
                    .using(SizeTransform(clip = false))
            },
            label = "parse",
        ) { p ->
            when (p) {
                ParseState.Idle -> IdleContent(state)
                ParseState.Loading -> LoadingCard()
                is ParseState.Error -> ErrorCard(p.message) { state.submit() }
                is ParseState.Live -> LivePanel(state, p.info, modifier = Modifier.entrance(0))
                is ParseState.Done -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    p.videos.forEachIndexed { i, v -> VideoCard(state, v, Modifier.entrance(i)) }
                    PlaylistArea(state)
                }
            }
        }
    }
}

@Composable
private fun InputCard(state: AppState, modifier: Modifier, done: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val loading = state.parse is ParseState.Loading
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(260), label = "ring")
    GlassCard(modifier.fillMaxWidth()
        .border(1.5.dp, Accent.copy(alpha = 0.7f * ring), RoundedCornerShape(28.dp)), padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Link, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                if (state.input.isEmpty()) {
                    Text("粘贴央视网视频链接、分享文字或视频 ID", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                }
                BasicTextField(
                    value = state.input, onValueChange = { state.input = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(AccentDeep), maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { done(); state.submit() }),
                )
            }
            AnimatedVisibility(state.input.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable { state.input = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "清空", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("粘贴", {
                val t = state.readClipboard()
                if (t.isEmpty()) state.show("剪贴板是空的", true) else { state.input = t; haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
            }, Modifier.weight(1f), icon = Icons.Rounded.ContentPaste)
            GlassButton(if (loading) "解析中" else "解析", { done(); state.submit() }, Modifier.weight(1.4f),
                icon = Icons.Rounded.AutoAwesome, primary = true, loading = loading)
        }
    }
}

@Composable
private fun IdleContent(state: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.recent.isNotEmpty()) {
            Column(Modifier.entrance(2)) {
                Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近解析", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("清除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(CircleShape).clickable { state.forgetRecent() }.padding(8.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recent.forEach { r ->
                        Box(Modifier.widthIn(max = 240.dp).glass(capsule = true, elevated = false, refraction = 0.4f)
                            .clickable(role = Role.Button) { state.submit(r) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(r.removePrefix("https://").removePrefix("http://"), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().entrance(3)) {
            Text("支持的内容", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Feature(Icons.Rounded.PlayCircle, "单个视频", "tv.cctv.com、v.cctv.com 等央视网视频页")
            Feature(Icons.Rounded.VideoLibrary, "整部剧与栏目", "自动识别剧集和栏目，可一次下载多集")
            Feature(Icons.Rounded.HighQuality, "原始画质", "解密原始流，保存为 MP4，不转码")
            Feature(Icons.Rounded.IosShare, "从央视网分享", "在浏览器或央视网中「分享」到本应用即可")
        }
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.32f), Accent.copy(alpha = 0.12f)))),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = AccentDeep) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingCard() {
    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(16f / 9f), 20.dp)
        Spacer(Modifier.height(16.dp))
        Skeleton(Modifier.fillMaxWidth(0.85f).height(20.dp))
        Spacer(Modifier.height(10.dp))
        Skeleton(Modifier.fillMaxWidth(0.5f).height(14.dp))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { Skeleton(Modifier.width(72.dp).height(34.dp), 17.dp) } }
        Spacer(Modifier.height(16.dp))
        Skeleton(Modifier.fillMaxWidth().height(52.dp), 26.dp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(message) {
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x, tween(55))
    }
    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().graphicsLayer { translationX = shake.value }, tint = if (LocalDark.current) Color(0xFF3A1416) else Color(0xFFFFE9E9)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Danger.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Danger)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("解析失败", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassButton("重试", retry, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, height = 46.dp)
    }
}

@Composable
private fun VideoCard(state: AppState, v: Video, modifier: Modifier) {
    val added = v.guid in state.added
    val sel = state.selectedQuality.value[v.guid]
    GlassCard(modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(12.dp), corner = 30.dp) {
        // 封面：缓慢推近 + 底部渐变
        val zoom: State<Float> = if (LocalMotion.current) rememberInfiniteTransition(label = "kb").animateFloat(1f, 1.06f,
            infiniteRepeatable(tween(9000, easing = EaseInOutSine), RepeatMode.Reverse), label = "z") else remember { mutableFloatStateOf(1f) }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
            AsyncImage(v.cover, null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }, contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (v.duration > 0) Pill(Fmt.duration(v.duration), color = Color.White, background = Color.Black.copy(alpha = 0.35f), icon = Icons.Rounded.Schedule)
                if (v.encrypted) Pill("原始流", color = Color.White, background = Color.Black.copy(alpha = 0.35f), icon = Icons.Rounded.Lock)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(listOf(v.channel, v.column, Fmt.date(v.publishedAt)).filter { it.isNotEmpty() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("清晰度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            QualityChips(v, sel) { id -> state.selectedQuality.value = state.selectedQuality.value + (v.guid to id) }
            Spacer(Modifier.height(18.dp))
            DownloadButton(added, { state.download(v) }, { state.tab = 2 })
        }
    }
}

@Composable
private fun QualityChips(v: Video, selected: String?, onSelect: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        v.qualities.forEach { q ->
            val on = q.id == selected
            val bg by animateColorAsState(if (on) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), tween(220), label = "q")
            val scale by animateFloatAsState(if (on) 1f else 0.96f, spring(0.5f, 500f), label = "qs")
            Column(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(16.dp)).background(bg)
                .clickable(role = Role.RadioButton) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(q.id) }
                .padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(q.name, style = MaterialTheme.typography.labelLarge, color = if (on) OnAccent else MaterialTheme.colorScheme.onSurface)
                if (q.short.isNotEmpty()) Text(q.short, style = MaterialTheme.typography.labelMedium,
                    color = (if (on) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.8f))
            }
        }
    }
}

/** 下载按钮：点击后变形为「已加入 · 查看」 */
@Composable
private fun DownloadButton(added: Boolean, onDownload: () -> Unit, onView: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(added) { if (added) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    AnimatedContent(added, transitionSpec = {
        (scaleIn(spring(0.55f, 380f), 0.85f) + fadeIn()).togetherWith(scaleOut(tween(120), 1.05f) + fadeOut(tween(120)))
    }, label = "dl") { a ->
        if (!a) GlassButton("下载到本机", onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, primary = true, height = 54.dp)
        else Row(Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(Success.copy(alpha = 0.16f)).clickable(onClick = onView)
            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Success)
            Spacer(Modifier.width(8.dp))
            Text("已加入下载", style = MaterialTheme.typography.labelLarge, color = Success)
            Spacer(Modifier.width(6.dp))
            Text("· 查看进度", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaylistArea(state: AppState) {
    AnimatedContent(state.playlist, contentKey = { it::class }, transitionSpec = {
        (fadeIn(tween(300)) + expandVertically(spring(0.85f, 280f))).togetherWith(fadeOut(tween(150)))
    }, label = "pl") { p ->
        when (p) {
            PlaylistState.None -> Spacer(Modifier.height(0.dp))
            PlaylistState.Loading -> GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(MaterialTheme.colorScheme.onSurfaceVariant, 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在识别剧集和栏目…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is PlaylistState.Found -> PlaylistCard(p.playlist) { state.sheet = Sheet.Episodes(p.playlist) }
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, open: () -> Unit) {
    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = open, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 叠放的封面
            Box(Modifier.size(width = 86.dp, height = 58.dp)) {
                p.items.take(3).reversed().forEachIndexed { i, it ->
                    val k = 2 - i
                    AsyncImage(it.cover, null, Modifier.align(Alignment.CenterStart).offset(x = (k * 14).dp)
                        .graphicsLayer { rotationZ = (k - 1) * 4f; alpha = 1f - k * 0.18f }
                        .size(width = 62.dp, height = 40.dp).clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.kind, style = MaterialTheme.typography.labelSmall, color = AccentDeep)
                Text(p.name.ifEmpty { "相关视频" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("共 ${p.total} ${p.unit}" + (if (p.items.size < p.total) "，可下载最近 ${p.items.size} ${p.unit}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
