package com.chouchang.cctvdl.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chouchang.cctvdl.data.Playlist
import com.chouchang.cctvdl.ui.AppState
import com.chouchang.cctvdl.ui.Fmt
import com.chouchang.cctvdl.ui.components.GlassButton
import com.chouchang.cctvdl.ui.components.GlassSegmented
import com.chouchang.cctvdl.ui.theme.Accent
import com.chouchang.cctvdl.ui.theme.OnAccent

val QualityPrefs = listOf("best" to "最高", "1080" to "1080P", "720" to "720P", "360" to "360P")

@Composable
fun EpisodesContent(state: AppState, p: Playlist, close: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val selected = remember(p) { mutableStateListOf<Int>().apply { addAll(p.items.indices) } }
    var quality by remember { mutableStateOf(state.ui.quality) }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    Column(Modifier.padding(horizontal = 22.dp)) {
        Text("${p.kind} · 共 ${p.total} ${p.unit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selected.size} ${p.unit}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val all = selected.size == p.items.size
            Text(if (all) "全不选" else "全选", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(CircleShape).clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    selected.clear(); if (!all) selected.addAll(p.items.indices)
                }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
    LazyColumn(Modifier.heightIn(max = maxH).padding(horizontal = 10.dp)) {
        itemsIndexed(p.items, key = { i, it -> it.guid.ifEmpty { it.url } + i }) { i, item ->
            val on = i in selected
            val bg by animateColorAsState(if (on) Accent.copy(alpha = 0.12f) else Color.Transparent, tween(200), label = "bg")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
                .clickable { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); if (on) selected.remove(i) else selected.add(i) }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckDot(on)
                Spacer(Modifier.width(12.dp))
                AsyncImage(item.cover, null, Modifier.size(width = 70.dp, height = 40.dp).clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(if (p.source == "album") "第 ${i + 1} ${p.unit}" else "", item.date.take(10), if (item.duration > 0) Fmt.duration(item.duration) else "")
                        .filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Column(Modifier.padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("清晰度（每集取不超过所选的最高一档）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        GlassSegmented(QualityPrefs.map { it.first }, quality, { k -> QualityPrefs.first { it.first == k }.second }, { quality = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        GlassButton("下载 ${selected.size} ${p.unit}", {
            val items = selected.sorted().map { p.items[it] }
            close()
            state.downloadPlaylist(p, items, quality)
        }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, primary = true, enabled = selected.isNotEmpty(), height = 54.dp)
    }
}

@Composable
private fun CheckDot(on: Boolean) {
    val s by animateFloatAsState(if (on) 1f else 0f, spring(0.5f, 600f), label = "c")
    Box(Modifier.size(24.dp).clip(CircleShape)
        .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f * (1 - s)), CircleShape)
        .background(Accent.copy(alpha = s)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp).graphicsLayer { scaleX = s; scaleY = s }, tint = OnAccent)
    }
}
