package com.chouchang.cctvdl.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chouchang.cctvdl.data.LiveChannel
import com.chouchang.cctvdl.data.LiveInfo
import com.chouchang.cctvdl.data.Program
import com.chouchang.cctvdl.ui.*
import com.chouchang.cctvdl.ui.components.*
import com.chouchang.cctvdl.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 直播红：录制按钮、直播角标 */
val LiveRed = Color(0xFFFF453A)

private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms))

/** 每 30 秒刷新一次的「现在」，用于节目进度条 */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

// ---------------------------------------------------------------------------
// 直播页：频道网格
// ---------------------------------------------------------------------------
@Composable
fun LiveScreen(state: AppState, pad: PaddingValues) {
    LaunchedEffect(Unit) { state.loadLiveNow() }
    val now = rememberNow()
    val channels = state.liveChannels
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 132.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
            PageHeader("直播", "录制直播 · 保存回看", Modifier.padding(top = 12.dp, bottom = 6.dp).offset(x = (-8).dp).entrance(0)) {
                GlassIconButton(Icons.Rounded.Refresh, "刷新", { state.loadLiveNow(force = true) })
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "tip") {
            Text("点选频道即可开始录制，或保存 7 天内播出过的节目。直播与回看以 720P 原始画质保存。",
                Modifier.padding(horizontal = 8.dp).entrance(1), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (channels.isEmpty()) {
            items(8, key = { "sk$it" }) { Skeleton(Modifier.fillMaxWidth().height(118.dp), 26.dp) }
        } else {
            items(channels, key = { it.id }) { ch ->
                val i = channels.indexOf(ch)
                ChannelTile(ch, state.liveNow[ch.id], now, Modifier.entrance(2 + i)) {
                    state.sheet = Sheet.Channel(ch)
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(ch: LiveChannel, np: Pair<Program?, Program?>?, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val cur = np?.first
    GlassCard(modifier.fillMaxWidth().height(118.dp), corner = 26.dp, padding = PaddingValues(14.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.short, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, modifier = Modifier.weight(1f))
            LiveDot()
        }
        Text(ch.sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(cur?.title ?: (if (np == null) "" else "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        val f = if (cur != null) ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(Accent, LiveRed))))
        }
    }
}

@Composable
fun LiveDot(size: Int = 7) {
    val motion = LocalMotion.current
    val a: State<Float> = if (motion) rememberInfiniteTransition(label = "dot").animateFloat(1f, 0.3f,
        infiniteRepeatable(tween(800, easing = EaseInOutSine), RepeatMode.Reverse), label = "a") else remember { mutableFloatStateOf(1f) }
    Box(Modifier.size(size.dp).graphicsLayer { alpha = a.value }.clip(CircleShape).background(LiveRed))
}

// ---------------------------------------------------------------------------
// 频道面板（直播页弹出面板，以及解析到直播链接时内嵌显示）
// ---------------------------------------------------------------------------
@Composable
fun ChannelSheetContent(state: AppState, ch: LiveChannel, close: () -> Unit) {
    var info by remember(ch.id) { mutableStateOf<LiveInfo?>(null) }
    var error by remember(ch.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(ch.id) {
        try { info = state.channelInfo(ch.id) } catch (e: Exception) { error = e.message ?: "无法读取频道信息" }
    }
    Column(Modifier.padding(horizontal = 16.dp).heightIn(max = 640.dp)) {
        val i = info
        when {
            i != null -> LivePanel(state, i, inSheet = true)
            error != null -> Text(error!!, Modifier.padding(16.dp), color = Danger)
            else -> {
                Skeleton(Modifier.fillMaxWidth().height(170.dp).padding(top = 12.dp), 24.dp)
                Spacer(Modifier.height(12.dp))
                Skeleton(Modifier.fillMaxWidth().height(220.dp), 24.dp)
            }
        }
    }
}


@Composable
fun LivePanel(state: AppState, info: LiveInfo, inSheet: Boolean = false, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val cur = info.programs.firstOrNull { it.start <= now && it.end > now }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- 直播卡片 ----
        Column(Modifier.fillMaxWidth().padding(top = if (inSheet) 10.dp else 0.dp).then(if (inSheet) Modifier else Modifier.glass(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(LiveRed.copy(alpha = 0.16f), Color.Transparent), radius = 900f))
            .padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFFF6B60), LiveRed))).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    Text("直播", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(if (cur != null) "${hm(cur.start)}–${hm(cur.end)}" else "实时", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(info.channelName, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Text(cur?.title?.let { "正在播出 · $it" } ?: info.now.ifEmpty { "实时直播" }.let { if (info.now.isNotEmpty()) "正在播出 · $it" else it },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (cur != null) {
                Spacer(Modifier.height(10.dp))
                val f = ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(LiveRed))
                }
            }
            Spacer(Modifier.height(18.dp))
            RecPlanner(state, info, now)
        }
        if (!info.noEpg) ReplayCard(state, info, inSheet)
    }
}

@Composable
private fun RecordButton(label: String = "开始录制", onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(0.5f, 500f), label = "rec")
    Row(
        Modifier.fillMaxWidth().height(54.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFFF6B60), LiveRed, Color(0xFFE0302A))))
            .clickable(interaction, null, role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(label, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "rl") {
            Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ReplayCard(state: AppState, info: LiveInfo, inSheet: Boolean) {
    val scope = rememberCoroutineScope()
    val days = remember {
        (0 until 7).map { i ->
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            val label = when (i) { 0 -> "今天"; 1 -> "昨天"; else -> "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}" }
            label to SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(c.time)
        }
    }
    var day by remember(info.channel) { mutableIntStateOf(0) }
    var programs by remember(info.channel) { mutableStateOf(info.programs) }
    var loading by remember { mutableStateOf(false) }
    val added = remember(info.channel) { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(info.channel, day) {
        if (day == 0 && info.programs.isNotEmpty()) { programs = info.programs; return@LaunchedEffect }
        loading = true
        programs = try { state.epg(info.channel, days[day].second) } catch (_: Exception) { emptyList() }
        loading = false
    }
    val now = rememberNow()
    Column(Modifier.fillMaxWidth().then(if (inSheet) Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)) else Modifier.glass(28.dp))
        .padding(vertical = 14.dp)) {
        Text("回看", Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text("保存 7 天内播出过的节目", Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { i, (label, _) ->
                val on = i == day
                val bg by animateColorAsState(if (on) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), label = "d")
                Box(Modifier.clip(CircleShape).background(bg).clickable(role = Role.Tab) { day = i }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) OnAccent else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(programs) {
            val k = programs.indexOfFirst { it.isLive() }
            if (k > 2) listState.scrollToItem(k - 2)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = if (inSheet) 300.dp else 420.dp)) {
            when {
                loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { Skeleton(Modifier.fillMaxWidth().height(20.dp)) }
                }
                programs.isEmpty() -> Text("这一天没有节目单。", Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    itemsIndexed(programs, key = { _, p -> p.start }) { _, p ->
                        val live = p.start <= now && p.end > now
                        val future = p.start > now
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (live) LiveRed.copy(alpha = 0.1f) else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 8.dp).graphicsLayer { alpha = if (future) 0.45f else 1f },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(hm(p.start), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                            Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                future -> Text("未播", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                live -> Text("直播中", style = MaterialTheme.typography.labelSmall, color = LiveRed, fontWeight = FontWeight.Bold)
                                added[p.start] == true -> Pill("已加入", icon = Icons.Rounded.Check, color = Success)
                                else -> Box(Modifier.clip(CircleShape).background(Accent.copy(alpha = 0.16f))
                                    .clickable(role = Role.Button) { scope.launch { if (state.replay(info, p)) added[p.start] = true } }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Rounded.Download, null, Modifier.size(14.dp), tint = AccentDeep)
                                        Text("保存", style = MaterialTheme.typography.labelMedium, color = AccentDeep)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// 录制规划：「定时结束 / 直到停止」+ 时长读数 + 节目时间轴（近密远疏，吸附节目结束点）
// ---------------------------------------------------------------------------
private object Rec {
    const val MIN = 5f
    const val MAX = 360f
    const val MBPS = 1.85
    private val K = floatArrayOf(0f, 0.5f, 0.8f, 1f)
    private val M = floatArrayOf(0f, 60f, 180f, 360f)
    fun f2m(f: Float): Float { for (i in 1 until K.size) if (f <= K[i]) return M[i - 1] + (f - K[i - 1]) / (K[i] - K[i - 1]) * (M[i] - M[i - 1]); return MAX }
    fun m2f(m: Float): Float { for (i in 1 until M.size) if (m <= M[i]) return K[i - 1] + (m - M[i - 1]) / (M[i] - M[i - 1]) * (K[i] - K[i - 1]); return 1f }
    fun size(min: Float): String { val mb = min * 60 * MBPS / 8; return if (mb >= 1000) String.format(Locale.US, "%.1f GB", mb / 1024) else "${(mb / 10).roundToInt().coerceAtLeast(1) * 10} MB" }
    fun span(min: Float): String { val t = min.roundToInt(); val h = t / 60; val m = t % 60; return if (h == 0) "$m 分钟" else if (m == 0) "$h 小时" else "$h 小时 $m 分" }
}

/** 录制计划：按节目（取节目结束时刻）或按时长（从现在起算） */
private data class RecPlan(val prog: Program? = null, val min: Float = 60f) {
    fun until(now: Long) = prog?.end ?: (now + (min * 60_000).toLong())
    fun minutes(now: Long) = (until(now) - now) / 60_000f
}

private fun upcoming(ps: List<Program>, now: Long) = ps.filter { it.end > now && it.start < now + (Rec.MAX * 60_000).toLong() }.sortedBy { it.start }

private fun defaultPlan(ps: List<Program>, now: Long): RecPlan {
    val up = upcoming(ps, now)
    val pick = listOfNotNull(up.firstOrNull { it.start <= now }, up.getOrNull(1)).firstOrNull { (it.end - now) / 60_000f >= Rec.MIN }
    return if (pick != null) RecPlan(prog = pick) else RecPlan(min = 60f)
}

@Composable
private fun RecPlanner(state: AppState, info: LiveInfo, now: Long) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var timed by remember { mutableStateOf(prefs.getString("rec.mode", "timed") != "manual") }
    var programs by remember(info.channel) { mutableStateOf(info.programs) }
    var plan by remember(info.channel) { mutableStateOf(defaultPlan(info.programs, System.currentTimeMillis())) }
    // 6 小时内跨过零点：补第二天节目单，让时间轴连续
    LaunchedEffect(info.channel) {
        val end = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + (Rec.MAX * 60_000).toLong() }
        if (!info.noEpg && end.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) {
            runCatching { state.epg(info.channel, SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(end.time)) }.getOrNull()?.let { more ->
                val seen = programs.map { it.start }.toSet()
                programs = programs + more.filter { it.start !in seen }
            }
        }
    }
    if (plan.prog != null && plan.minutes(now) < Rec.MIN) plan = defaultPlan(programs, now)
    val up = remember(programs, now) { upcoming(programs, now) }
    val bounds = remember(up, now) { up.map { it to (it.end - now) / 60_000f }.filter { it.second in Rec.MIN..Rec.MAX } }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("录制", style = MaterialTheme.typography.titleSmall, color = onSurface, maxLines = 1)
        Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
        GlassSegmented(listOf(true, false), timed, { if (it) "定时结束" else "直到停止" }, {
            timed = it; prefs.edit().putString("rec.mode", if (it) "timed" else "manual").apply()
        }, Modifier.width(208.dp))
    }
    AnimatedVisibility(timed, enter = expandVertically(spring(0.85f, 380f)) + fadeIn(), exit = shrinkVertically(spring(1f, 500f)) + fadeOut()) {
        Column {
            val min = plan.minutes(now)
            val until = plan.until(now)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    val t = min.roundToInt(); val h = t / 60; val m = t % 60
                    @Composable fun num(v: Int) = Text("$v", fontSize = 36.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = onSurface,
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum", letterSpacing = (-1).sp))
                    @Composable fun unit(u: String) = Text(u, style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.padding(start = 3.dp, end = 6.dp, bottom = 4.dp))
                    if (h > 0) { num(h); unit("小时"); if (m > 0) { num(m); unit("分") } } else { num(m); unit("分钟") }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(hm(until), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = onSurface)
                    Text("结束", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            val at = programs.firstOrNull { it.start < until && it.end > until }
            Text(listOf(plan.prog?.let { "录完《${it.title}》" } ?: at?.let { "结束时正在播《${it.title}》" } ?: "按时长结束", "约 ${Rec.size(min)}").joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            RecTimeline(up, now, bounds, Rec.m2f(min.coerceIn(Rec.MIN, Rec.MAX))) { p -> plan = p }
            // 刻度
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                val labels = listOf(0f to "现在", 0.25f to "30 分", 0.5f to "1 时", 0.65f to "2 时", 0.8f to "3 时", 1f to "6 时")
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = maxWidth
                    labels.forEach { (f, l) ->
                        Text(l, fontSize = 10.sp, color = muted.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.TopStart)
                            .graphicsLayer { translationX = (w.toPx() * f) - (if (f == 0f) 0f else if (f == 1f) size.width else size.width / 2) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 快捷项
            val quick: List<Triple<String, String, RecPlan>> = remember(up, now) {
                val ps = up.filter { (it.end - now) / 60_000f >= Rec.MIN }
                if (ps.isNotEmpty()) ps.take(2).mapIndexed { i, p ->
                    val label = if (p.start <= now) "录完本节目" else if (i == 0 || ps[0].start <= now) "录完下一节目" else "再录一个节目"
                    Triple(label, "${p.title} · ${hm(p.end)}", RecPlan(prog = p))
                } else listOf(30f, 60f, 120f).map { m -> Triple(Rec.span(m), "至 ${hm(now + (m * 60_000).toLong())}", RecPlan(min = m)) }
            }
            val haptic = LocalHapticFeedback.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { (label, sub, p) ->
                    val on = if (p.prog != null) plan.prog?.start == p.prog.start else plan.prog == null && plan.min == p.min
                    val bg by animateColorAsState(if (on) LiveRed.copy(alpha = 0.13f) else onSurface.copy(alpha = 0.06f), label = "q")
                    val border by animateColorAsState(if (on) LiveRed.copy(alpha = 0.6f) else Color.Transparent, label = "qb")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg)
                        .border(1.5.dp, border, RoundedCornerShape(14.dp))
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); plan = p }
                        .padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) LiveRed else onSurface)
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    AnimatedVisibility(!timed, enter = expandVertically(spring(0.85f, 380f)) + fadeIn(), exit = shrinkVertically(spring(1f, 500f)) + fadeOut()) {
        Text("一直录制，直到你在下载列表里停止。停止后已录内容会自动保存。", style = MaterialTheme.typography.bodySmall, color = muted,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(onSurface.copy(alpha = 0.06f)).padding(14.dp))
    }
    Spacer(Modifier.height(16.dp))
    val label = if (timed) "录制 ${Rec.span(plan.minutes(now))}" else "开始录制"
    RecordButton(label) {
        val t = System.currentTimeMillis()
        if (timed && plan.until(t) - t < 60_000) { plan = defaultPlan(programs, t); state.show("所选节目已结束，已重新规划", true); return@RecordButton }
        state.record(info, if (timed) plan.until(t) else 0L, if (timed) plan.prog?.title.orEmpty() else "")
    }
    Spacer(Modifier.height(10.dp))
    Text("以原始画质保存为 MP4，切到后台也会继续录制。", style = MaterialTheme.typography.bodySmall, color = muted)
}

/** 节目时间轴：灰色节目分段 + 红色已选区间 + 白色把手；拖动时吸附节目结束点并给触感 */
@Composable
private fun RecTimeline(up: List<Program>, now: Long, bounds: List<Pair<Program, Float>>, pos: Float, onPlan: (RecPlan) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalDark.current) 0.16f else 0.11f)
    var dragging by remember { mutableStateOf(false) }
    var lastSnap by remember { mutableStateOf<Long?>(null) }
    var lastStep by remember { mutableIntStateOf(-1) }
    val shown by animateFloatAsState(pos, if (dragging) snap() else spring(0.8f, 420f), label = "pos")
    val thumbScale by animateFloatAsState(if (dragging) 1.18f else 1f, spring(0.5f, 600f), label = "ts")
    val snapPulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val blocks = remember(up, now) {
        if (up.isNotEmpty()) up.map { Rec.m2f(((it.start - now) / 60_000f).coerceAtLeast(0f)) to Rec.m2f(((it.end - now) / 60_000f).coerceAtMost(Rec.MAX)) }
        else (0 until 6).map { Rec.m2f(it * 60f) to Rec.m2f(minOf(Rec.MAX, it * 60f + 60f)) }
    }
    fun pick(f: Float, widthPx: Float) {
        val m = Rec.f2m(f.coerceIn(0f, 1f)).coerceAtLeast(Rec.MIN)
        val snapB = bounds.minByOrNull { kotlin.math.abs(Rec.m2f(it.second) - f) }?.takeIf { kotlin.math.abs(Rec.m2f(it.second) - f) * widthPx < 22f }
        if (snapB != null) {
            if (lastSnap != snapB.first.start) {
                lastSnap = snapB.first.start
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                scope.launch { snapPulse.snapTo(1f); snapPulse.animateTo(1.25f, spring(0.6f, 900f)); snapPulse.animateTo(1f, spring(0.5f, 500f)) }
            }
            onPlan(RecPlan(prog = snapB.first))
        } else {
            lastSnap = null
            val g = if (m <= 60) 1f else if (m <= 180) 5f else 10f
            val v = ((m / g).roundToInt() * g).coerceIn(Rec.MIN, Rec.MAX)
            val step = (v / (if (m <= 60) 5f else g)).toInt()
            if (step != lastStep) { lastStep = step; haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
            onPlan(RecPlan(min = v))
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)
        .pointerInput(bounds) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragging = true; pick(o.x / size.width, size.width.toFloat()) },
                onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
            ) { change, _ -> change.consume(); pick(change.position.x / size.width, size.width.toFloat()) }
        }
        .pointerInput(bounds) { detectTapGestures { o -> pick(o.x / size.width, size.width.toFloat()) } }
        .semantics { contentDescription = "录制时长" }
    ) {
        val w = constraints.maxWidth.toFloat()
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(12.dp).align(Alignment.CenterStart)) {
            val h = size.height
            val r = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            val gap = 1.dp.toPx()
            val cut = size.width * shown
            blocks.forEach { (s, e) ->
                val l = size.width * s + gap; val rr = size.width * e - gap
                if (rr <= l) return@forEach
                drawRoundRect(base, androidx.compose.ui.geometry.Offset(l, 0f), androidx.compose.ui.geometry.Size(rr - l, h), r)
                if (l < cut) {
                    clipRect(right = cut) {
                        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFF6B60), LiveRed, Color(0xFFE0302A))),
                            androidx.compose.ui.geometry.Offset(l, 0f), androidx.compose.ui.geometry.Size(rr - l, h), r)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.CenterStart)
            .graphicsLayer {
                translationX = w * shown - 4.dp.toPx()
                scaleX = thumbScale * snapPulse.value; scaleY = thumbScale
                shadowElevation = 6.dp.toPx(); shape = RoundedCornerShape(4.dp); clip = false
            }
            .size(8.dp, 30.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
    }
}
