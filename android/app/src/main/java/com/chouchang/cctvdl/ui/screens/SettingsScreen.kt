package com.chouchang.cctvdl.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chouchang.cctvdl.BuildConfig
import com.chouchang.cctvdl.save.ConflictRule
import com.chouchang.cctvdl.save.Naming
import com.chouchang.cctvdl.save.NamingRule
import com.chouchang.cctvdl.save.SaveLocation
import com.chouchang.cctvdl.ui.AppState
import com.chouchang.cctvdl.ui.Sheet
import com.chouchang.cctvdl.ui.components.*
import com.chouchang.cctvdl.ui.components.liquid.LiquidSlider
import com.chouchang.cctvdl.ui.theme.*
import org.json.JSONObject
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: AppState, contentPadding: PaddingValues) {
    val ui = state.ui
    val save = state.save
    val glass = LocalGlass.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) state.setCustomFolder(uri) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader("设置", "偏好与保存", Modifier.padding(top = 12.dp).entrance(0))

        Group("外观", 1) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                RowTitle(Icons.Rounded.DarkMode, "主题")
                Spacer(Modifier.height(12.dp))
                GlassSegmented(ThemeMode.entries, ui.theme, { it.label }, { state.updateUi(ui.copy(theme = it)) }, Modifier.fillMaxWidth())
            }
            Divider()
            SwitchRow(Icons.Rounded.Animation, "环境动效", "背景流光、封面推近等循环动画；关闭可省电", ui.motion) { state.updateUi(ui.copy(motion = it)) }
            Divider()
            NavRow(Icons.Rounded.BlurOn, "界面材质", (if (ui.glass == null) "自动 · " else "") + glass.label) { state.sheet = Sheet.Choice("glass") }
            MaterialPreview(glass)
        }

        Group("下载", 2) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle(Icons.Rounded.Speed, "同时下载", Modifier.weight(1f))
                    AnimatedContent(state.maxJobs, transitionSpec = { (slideInVertically { -it / 2 } + fadeIn()).togetherWith(fadeOut()) }, label = "n") {
                        Text("$it 个", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val backdrop = LocalBackdrop.current
                if (backdrop != null) {
                    var v by remember { mutableFloatStateOf(state.maxJobs.toFloat()) }
                    val haptic = LocalHapticFeedback.current
                    LiquidSlider({ v }, { nv ->
                        v = nv
                        val n = nv.roundToInt().coerceIn(1, 4)
                        if (n != state.maxJobs) { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick); state.updateEngine(JSONObject().put("maxJobs", n)) }
                    }, 1f..4f, backdrop, Modifier.fillMaxWidth())
                }
                Text("同时进行的任务越多越快，但更耗电，也更容易被限速", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider()
            NavRow(Icons.Rounded.HighQuality, "默认清晰度", QualityPrefs.first { it.first == ui.quality }.second.let { if (ui.quality == "best") "最高画质" else "不超过 $it" }) { state.sheet = Sheet.Choice("quality") }
            Divider()
            SwitchRow(Icons.Rounded.NotificationsActive, "完成通知", "下载完成或失败时提醒，可直接播放", state.notify) { state.updateEngine(JSONObject().put("notify", it)) }
        }

        Group("保存", 3) {
            NavRow(Icons.Rounded.FolderSpecial, "保存位置", when (save.effectiveLocation) {
                SaveLocation.CUSTOM -> save.customLabel.ifEmpty { "自定义文件夹" }
                else -> save.effectiveLocation.label
            }) { state.sheet = Sheet.Choice("location") }
            Divider()
            NavRow(Icons.Rounded.CreateNewFolder, "文件夹", save.folder.ifEmpty { "不使用" }) { state.sheet = Sheet.FolderName }
            Divider()
            SwitchRow(Icons.Rounded.VideoLibrary, "按剧集分文件夹", "整部剧或栏目下载到以剧名命名的子文件夹", save.seriesFolder) { state.updateSave(save.copy(seriesFolder = it)) }
            Divider()
            NavRow(Icons.Rounded.TextFields, "文件命名", save.naming.label) { state.sheet = Sheet.Choice("naming") }
            Divider()
            NavRow(Icons.Rounded.FileCopy, "同名文件", save.conflict.label) { state.sheet = Sheet.Choice("conflict") }
            Divider()
            SwitchRow(Icons.Rounded.Image, "同时保存封面", if (save.coverSupported) "在视频旁保存同名 JPG 封面" else "保存到「影片」时不可用", save.saveCover && save.coverSupported,
                enabled = save.coverSupported) { state.updateSave(save.copy(saveCover = it)) }
            Divider()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("示例", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                AnimatedContent(Naming.preview(save), transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) }, label = "pv") {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("写入完成并校验大小后才会出现在文件管理和相册中；中途取消或失败不会留下残缺文件。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Group("关于", 4) {
            InfoNav(Icons.Rounded.Info, "版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            Divider()
            InfoNav(Icons.Rounded.PhoneAndroid, "系统", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            Divider()
            InfoNav(Icons.Rounded.Web, "下载引擎", runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()?.let { "WebView $it" } ?: "系统 WebView")
            Divider()
            NavRow(Icons.Rounded.Gavel, "开源许可", "") { state.sheet = Sheet.Licenses }
        }
        Text("仅供个人学习与备份使用，请尊重版权。", Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Group(title: String, index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.entrance(index)) {
        Text(title, Modifier.padding(start = 30.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 26.dp, padding = PaddingValues(vertical = 4.dp), content = content)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(0.6.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(Accent, AccentDeep))), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(17.dp), tint = OnAccent)
    }
}

@Composable
private fun RowTitle(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon); Spacer(Modifier.width(14.dp)); Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
        .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun InfoNav(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon); Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onChange(!checked) }
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked, { haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff); onChange(it) }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), uncheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White))
    }
}

/** 材质实时预览：三块玻璃叠在流动色彩上 */
@Composable
private fun MaterialPreview(mode: GlassMode) {
    // 预览自带一块彩色条纹底，玻璃从它取样，折射与模糊一目了然
    val local = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).padding(bottom = 10.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().layerBackdrop(local)) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF5B8CFF), Color(0xFFB36BFF), Color(0xFFFFB648), Color(0xFF3FD0B4))))
            val step = 22.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(Color.White.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), 5.dp.toPx())
                x += step
            }
        }
        CompositionLocalProvider(LocalBackdrop provides local) {
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().glass(corner = 16.dp, refraction = 1.5f), contentAlignment = Alignment.Center) {
                Text(mode.label, style = MaterialTheme.typography.labelLarge)
            }
            Box(Modifier.size(58.dp).glass(capsule = true, refraction = 1.8f), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        }
    }
}

/** 设置项的单选面板内容 */
@Composable
fun ChoiceContent(state: AppState, key: String, close: () -> Unit) {
    val ui = state.ui
    val save = state.save
    when (key) {
        "glass" -> {
            ChoiceRow("自动", "使用本机支持的最佳材质（${GlassMode.best.label}）", ui.glass == null) { state.updateUi(ui.copy(glass = null)); close() }
            GlassMode.entries.forEach { m ->
                ChoiceRow(m.label, if (m.supported) m.detail else "${m.detail}（本机不支持）", ui.glass == m, enabled = m.supported) { state.updateUi(ui.copy(glass = m)); close() }
            }
        }
        "quality" -> listOf("best" to "最高画质", "1080" to "不超过 1080P", "720" to "不超过 720P", "360" to "不超过 360P").forEach { (k, l) ->
            ChoiceRow(l, if (k == "best") "解析后默认选中最高一档" else null, ui.quality == k) { state.updateUi(ui.copy(quality = k)); close() }
        }
        "location" -> {
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { state.setCustomFolder(uri); close() } }
            SaveLocation.entries.forEach { l ->
                val detail = if (l == SaveLocation.CUSTOM && save.customTree.isNotEmpty()) "当前：${save.customLabel} · 点按重新选择" else l.detail
                ChoiceRow(l.label, detail, save.effectiveLocation == l) {
                    if (l == SaveLocation.CUSTOM) pick.launch(null) else { state.updateSave(save.copy(location = l)); close() }
                }
            }
            if (Build.VERSION.SDK_INT < 29) Text("Android 9 及以下保存到公共目录需要存储权限，首次下载时会请求。", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "naming" -> NamingRule.entries.forEach { r ->
            val sample = com.chouchang.cctvdl.save.SaveMeta("mp4", "觉醒年代 第3集", "超清", "觉醒年代", 3, "", "", "", "2021-02-01", "", "", 0.0)
            ChoiceRow(r.label, Naming.fileStem(sample, r) + ".mp4", save.naming == r) { state.updateSave(save.copy(naming = r)); close() }
        }
        "conflict" -> ConflictRule.entries.forEach { r ->
            ChoiceRow(r.label, r.detail, save.conflict == r) { state.updateSave(save.copy(conflict = r)); close() }
        }
    }
}

fun choiceTitle(key: String) = when (key) {
    "glass" -> "界面材质"; "quality" -> "默认清晰度"; "location" -> "保存位置"; "naming" -> "文件命名"; "conflict" -> "遇到同名文件"; else -> ""
}

@Composable
fun FolderNameContent(state: AppState, close: () -> Unit) {
    var text by remember { mutableStateOf(state.save.folder) }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text("在保存位置下新建的文件夹，可用「/」建立多级，留空则直接保存在根目录。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("CCTV") },
            shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = AccentDeep))
        Spacer(Modifier.height(16.dp))
        GlassButton("完成", {
            state.updateSave(state.save.copy(folder = com.chouchang.cctvdl.save.SaveSettings.cleanFolder(text)))
            close()
        }, Modifier.fillMaxWidth(), primary = true)
    }
}

@Composable
fun LicensesContent() {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            "Kyant Backdrop 1.0.6 · Apache-2.0" to "液态玻璃取样、模糊、折射与高光",
            "AndroidLiquidGlass catalog（Kyant0）· Apache-2.0" to "底栏、按钮、滑块等液态玻璃组件",
            "kudo-android（woliubang256）· Apache-2.0" to "以上组件的主题化与兼容材质改编",
            "Jetpack Compose / AndroidX · Apache-2.0" to "原生界面",
            "Coil 3 · Apache-2.0" to "封面图片加载",
            "mux.js（Brightcove）· Apache-2.0" to "MP4 封装",
            "CCTVVideoDownloader（letr007）" to "h5e 解密算法参考",
        ).forEach { (t, d) ->
            Column {
                Text(t, style = MaterialTheme.typography.bodyMedium)
                Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
