package com.chouchang.cctvdl.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.chouchang.cctvdl.data.Job
import com.chouchang.cctvdl.data.JobStatus
import com.chouchang.cctvdl.ui.*
import com.chouchang.cctvdl.ui.components.*
import com.chouchang.cctvdl.ui.theme.*

private enum class Filter(val label: String) { ALL("全部"), ACTIVE("进行中"), DONE("已完成"), FAILED("失败") }

@Composable
fun DownloadsScreen(state: AppState, contentPadding: PaddingValues) {
    val jobs by state.jobs.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { state.verifyFiles(jobs) }

    val active = jobs.count { it.status.active }
    val todayBytes = jobs.filter { it.status == JobStatus.COMPLETED && System.currentTimeMillis() - it.finishedAt < 86_400_000 }.sumOf { it.size }
    val shown = jobs.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.ACTIVE -> it.status.active
            Filter.DONE -> it.status == JobStatus.COMPLETED
            Filter.FAILED -> it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            val sub = when {
                active > 0 -> "$active 个任务进行中"
                todayBytes > 0 -> "今天已保存 ${Fmt.size(todayBytes)}"
                else -> "下载记录"
            }
            PageHeader("下载", sub, Modifier.padding(top = 12.dp).entrance(0)) {
                AnimatedVisibility(jobs.any { !it.status.active }, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    GlassIconButton(Icons.Rounded.DeleteSweep, "清除已结束的记录", { state.clearFinished(); state.show("已清除已结束的记录") })
                }
                GlassIconButton(Icons.Rounded.FolderOpen, "打开保存位置", {
                    if (!FileActions.openLocation(ctx, state.save.customTree.takeIf { state.save.location == com.chouchang.cctvdl.save.SaveLocation.CUSTOM }))
                        state.show("没有可以打开文件夹的应用", true)
                })
            }
        }
        item(key = "filter") {
            GlassSegmented(Filter.entries, filter, { f ->
                val n = when (f) {
                    Filter.ALL -> jobs.size; Filter.ACTIVE -> active
                    Filter.DONE -> jobs.count { it.status == JobStatus.COMPLETED }
                    Filter.FAILED -> jobs.count { it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }
                }
                if (n > 0) "${f.label} $n" else f.label
            }, { filter = it }, Modifier.padding(horizontal = 16.dp).fillMaxWidth().entrance(1))
        }
        if (shown.isEmpty()) {
            item(key = "empty") { EmptyState(filter == Filter.ALL) { state.tab = 0 } }
        }
        items(shown, key = { it.id }) { job ->
            JobCard(state, job, Modifier.animateItem(fadeInSpec = tween(300), placementSpec = spring(0.8f, 300f), fadeOutSpec = tween(200)))
        }
    }
}

@Composable
private fun EmptyState(all: Boolean, go: () -> Unit) {
    val float: State<Float> = if (LocalMotion.current) rememberInfiniteTransition(label = "float").animateFloat(-6f, 6f,
        infiniteRepeatable(tween(2600, easing = EaseInOutSine), RepeatMode.Reverse), label = "f") else remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().padding(top = 60.dp).entrance(2), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(108.dp).graphicsLayer { translationY = float.value.dp.toPx() }.glass(capsule = true, refraction = 1.4f),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CloudDownload, null, Modifier.size(46.dp), tint = AccentDeep)
        }
        Spacer(Modifier.height(22.dp))
        Text(if (all) "还没有下载" else "这里没有任务", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(if (all) "解析一个央视网视频，下载后会出现在这里" else "切换到「全部」查看其他记录",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (all) {
            Spacer(Modifier.height(22.dp))
            GlassButton("去解析视频", go, icon = Icons.Rounded.Link, primary = true, height = 48.dp)
        }
    }
}

@Composable
private fun JobCard(state: AppState, job: Job, modifier: Modifier) {
    val ctx = LocalContext.current
    val failed = job.status == JobStatus.FAILED || job.status == JobStatus.CANCELLED
    val done = job.status == JobStatus.COMPLETED
    GlassCard(
        modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 26.dp, padding = PaddingValues(12.dp),
        onClick = {
            when {
                done && !job.missing -> if (!FileActions.open(ctx, job.uri)) state.show("没有可以播放视频的应用", true)
                else -> state.sheet = Sheet.JobMenu(job.id)
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(job)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                StatusLine(job)
            }
            Spacer(Modifier.width(4.dp))
            when {
                job.status == JobStatus.RECORDING -> StopButton { state.jobAction("job:stop", job.id); state.show("正在停止录制，已录内容会保存") }
                job.status.active -> SmallIcon(Icons.Rounded.Close, "取消") { state.jobAction("job:cancel", job.id) }
                failed && !job.unresolved -> SmallIcon(Icons.Rounded.Refresh, "重试") { state.jobAction("job:retry", job.id) }
                else -> SmallIcon(Icons.Rounded.MoreHoriz, "更多") { state.sheet = Sheet.JobMenu(job.id) }
            }
        }
        AnimatedVisibility(job.status.active, enter = expandVertically() + fadeIn(), exit = shrinkVertically(spring(0.9f, 300f)) + fadeOut()) {
            Column {
                Spacer(Modifier.height(12.dp))
                ProgressBar(job)
            }
        }
        if (job.checkLevel == "decode" || job.checkLevel == "format") {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFB020).copy(alpha = 0.14f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = Color(0xFFE08A00))
                Spacer(Modifier.width(8.dp))
                Text(job.checkMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Thumb(job: Job) {
    Box(Modifier.size(width = 104.dp, height = 62.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))) {
        if (job.cover.isEmpty() && job.kind.isNotEmpty()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B2F3A), Color(0xFF151821)))), contentAlignment = Alignment.Center) {
                Icon(if (job.isLive) Icons.Rounded.LiveTv else Icons.Rounded.History, null, Modifier.size(24.dp), tint = if (job.isLive) LiveRed else Accent)
            }
        } else AsyncImage(job.cover, null, Modifier.fillMaxSize().graphicsLayer { alpha = if (job.missing) 0.4f else 1f }, contentScale = ContentScale.Crop)
        when {
            job.status == JobStatus.RECORDING -> Box(Modifier.align(Alignment.TopStart).padding(5.dp).clip(CircleShape).background(LiveRed).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("REC", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
            job.status == JobStatus.COMPLETED && !job.missing -> Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = Color.White)
            }
            job.status.active -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                ProgressRing(job.progress, job.status == JobStatus.QUEUED || job.status == JobStatus.PREPARING)
            }
        }
        if (job.qualityShort.isNotEmpty()) Text(job.qualityShort, Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun ProgressRing(progress: Float, indeterminate: Boolean) {
    val p by animateFloatAsState(progress, spring(1f, 60f), label = "ring")
    val spin = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "s")
    Canvas(Modifier.size(32.dp).graphicsLayer { if (indeterminate) rotationZ = spin.value }) {
        val sw = 3.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(Accent, -90f, if (indeterminate) 90f else 360f * p, false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun StatusLine(job: Job) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text: String
    var color = muted
    var icon: androidx.compose.ui.graphics.vector.ImageVector? = null
    when {
        job.status == JobStatus.RECORDING -> {
            text = listOf("录制中 ${Fmt.duration(job.recorded)}", Fmt.size(job.bytes),
                if (job.until > 0) "至 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(job.until)) else "直到停止",
                if (job.gaps > 0) "缺 ${job.gaps} 段" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
            color = LiveRed; icon = Icons.Rounded.FiberManualRecord
        }
        job.status == JobStatus.DOWNLOADING -> text = listOf("${(job.progress * 100).toInt()}%", Fmt.speed(job.speed), Fmt.eta(job.eta)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        job.status.active -> text = job.status.label
        job.status == JobStatus.COMPLETED && job.missing -> { text = "文件已被删除或移动"; color = Danger; icon = Icons.Rounded.LinkOff }
        job.status == JobStatus.COMPLETED -> {
            text = listOf(job.qualityName, Fmt.size(job.size), if (job.existed) "已存在" else Fmt.ago(job.finishedAt)).filter { it.isNotEmpty() && it != "—" }.joinToString("  ·  ")
            icon = Icons.Rounded.CheckCircle; color = Success
        }
        job.status == JobStatus.CANCELLED -> text = "已取消"
        else -> { text = job.error.ifEmpty { "下载失败" }; color = Danger; icon = Icons.Rounded.ErrorOutline }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = color); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (color == Success) muted else color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (job.status == JobStatus.COMPLETED && !job.missing && job.filename.isNotEmpty()) {
        Text(job.filename.substringBeforeLast('/').replace("/", " › "), style = MaterialTheme.typography.labelMedium,
            color = muted.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 流光进度条：宽度弹性跟随，正在保存时整条流动 */
@Composable
private fun ProgressBar(job: Job) {
    val xs: State<Float> = if (LocalMotion.current) rememberInfiniteTransition(label = "flow")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x") else remember { mutableFloatStateOf(-1f) }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val recording = job.status == JobStatus.RECORDING
    val timed = recording && job.until > 0 && job.recordStartedAt > 0
    val rp = if (timed) ((System.currentTimeMillis() - job.recordStartedAt).toFloat() / (job.until - job.recordStartedAt).coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
    val p by animateFloatAsState(if (recording) rp else job.progress, spring(1f, 50f), label = "bar2")
    val indeterminate = if (recording) !timed else job.status != JobStatus.DOWNLOADING
    val colors = if (recording) listOf(Color(0xFFFF8A80), LiveRed, Color(0xFFE0302A)) else listOf(Color(0xFFFFD25E), Accent, AccentDeep)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        val w = if (indeterminate) size.width else size.width * p
        if (w > 0f) {
            drawRoundRect(Brush.horizontalGradient(colors), size = size.copy(width = w.coerceAtLeast(size.height)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), alpha = if (indeterminate) 0.45f else 1f)
            val sx = w * xs.value
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent), startX = sx - 60f, endX = sx + 60f),
                size = size.copy(width = w.coerceAtLeast(size.height)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row {
        Text(when (job.status) {
            JobStatus.QUEUED -> "等待空闲位置"
            JobStatus.PREPARING -> "正在读取切片列表"
            JobStatus.SAVING -> "正在写入并校验"
            JobStatus.RECORDING -> "已录 ${job.done} 段 · ${Fmt.size(job.bytes)}"
            else -> "${job.done} / ${job.total} 片 · ${Fmt.size(job.bytes)}"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(LiveRed.copy(alpha = 0.14f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(LiveRed))
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** 任务操作面板 */
@Composable
fun JobMenuContent(state: AppState, job: Job, close: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(job.cover, null, Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val ok = job.status == JobStatus.COMPLETED && !job.missing
    if (ok) {
        SheetAction(Icons.Rounded.PlayArrow, "播放", { close(); if (!FileActions.open(ctx, job.uri)) state.show("没有可以播放视频的应用", true) })
        SheetAction(Icons.Rounded.IosShare, "分享", { close(); FileActions.share(ctx, job.uri, job.title) })
    }
    SheetAction(Icons.Rounded.Info, "文件信息", { state.sheet = Sheet.JobInfo(job.id) })
    if (job.status == JobStatus.FAILED || job.status == JobStatus.CANCELLED || job.missing) {
        if (!job.unresolved) SheetAction(Icons.Rounded.Refresh, if (job.missing) "重新下载" else "重试", { close(); state.jobAction("job:retry", job.id) })
    }
    if (job.status == JobStatus.RECORDING && job.until > 0) SheetAction(Icons.Rounded.MoreTime, "延长 30 分钟", { close(); state.extendRecording(job.id, 30) },
        detail = "当前 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(job.until)) + " 结束")
    if (job.status == JobStatus.RECORDING) SheetAction(Icons.Rounded.Stop, "停止并保存", { close(); state.jobAction("job:stop", job.id) }, detail = "保存已录制的内容")
    if (job.status.active) SheetAction(Icons.Rounded.Close, if (job.isLive) "取消录制" else "取消下载", { close(); state.jobAction("job:cancel", job.id) }, danger = true)
    else SheetAction(Icons.Rounded.DeleteOutline, "删除记录", { close(); state.jobAction("job:remove", job.id) }, detail = "保留已保存的文件")
    if (ok) SheetAction(Icons.Rounded.DeleteForever, "删除文件", { close(); state.deleteFile(job) }, danger = true, detail = "从设备中永久删除此视频")
}

/** 文件信息面板 */
@Composable
fun JobInfoContent(job: Job) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(job.title, style = MaterialTheme.typography.titleMedium)
        InfoRow("状态", if (job.missing) "文件已不存在" else job.status.label + if (job.existed) "（同名文件已存在，未重复下载）" else "")
        if (job.filename.isNotEmpty()) InfoRow("文件", job.filename.substringAfterLast('/'))
        if (job.filename.contains('/')) InfoRow("位置", job.filename.substringBeforeLast('/').replace("/", " › "))
        InfoRow("大小", Fmt.size(job.size))
        if (job.duration > 0) InfoRow("时长", Fmt.duration(job.duration))
        InfoRow("清晰度", listOf(job.qualityName, job.qualityShort).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" })
        if (job.series.isNotEmpty()) InfoRow("剧集", job.series + if (job.episode > 0) " 第 ${job.episode} 集" else "")
        if (job.channel.isNotEmpty()) InfoRow("频道", job.channel)
        InfoRow("完整性", when (job.checkLevel) {
            "ok" -> "解码自检通过"
            "decode", "format" -> job.checkMessage
            "" -> "—"
            else -> "未完成自检"
        })
        if (job.finishedAt > 0) InfoRow("完成时间", java.text.SimpleDateFormat("yyyy年M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(job.finishedAt)))
        if (job.error.isNotEmpty() && job.status != JobStatus.COMPLETED) InfoRow("错误", job.error)
        if (job.guid.isNotEmpty()) InfoRow("视频 ID", job.guid)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

// rememberSaveable
@Composable
private fun <T : Any> rememberSaveable(init: () -> MutableState<T>): MutableState<T> = remember { init() }
