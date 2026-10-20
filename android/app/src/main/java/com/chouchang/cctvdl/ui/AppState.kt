package com.chouchang.cctvdl.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chouchang.cctvdl.data.Job
import com.chouchang.cctvdl.data.JobStatus
import com.chouchang.cctvdl.data.LiveChannel
import com.chouchang.cctvdl.data.LiveInfo
import com.chouchang.cctvdl.data.Program
import com.chouchang.cctvdl.data.Playlist
import com.chouchang.cctvdl.data.PlaylistItem
import com.chouchang.cctvdl.data.Video
import com.chouchang.cctvdl.data.objects
import com.chouchang.cctvdl.engine.Engine
import com.chouchang.cctvdl.save.SaveSettings
import com.chouchang.cctvdl.ui.theme.GlassMode
import com.chouchang.cctvdl.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 界面设置（主题、材质、默认清晰度…），保存在 SharedPreferences */
data class UiSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val glass: GlassMode? = null,
    val quality: String = "best",
    val haptics: Boolean = true,
    val motion: Boolean = true,
) {
    companion object {
        fun load(ctx: Context): UiSettings {
            val p = ctx.getSharedPreferences("ui", 0)
            return UiSettings(
                theme = ThemeMode.entries.firstOrNull { it.name == p.getString("theme", null) } ?: ThemeMode.SYSTEM,
                glass = GlassMode.entries.firstOrNull { it.name == p.getString("glass", null) },
                quality = p.getString("quality", "best") ?: "best",
                haptics = p.getBoolean("haptics", true),
                motion = p.getBoolean("motion", true),
            )
        }
        fun store(ctx: Context, s: UiSettings) {
            ctx.getSharedPreferences("ui", 0).edit()
                .putString("theme", s.theme.name).putString("glass", s.glass?.name)
                .putString("quality", s.quality).putBoolean("haptics", s.haptics).putBoolean("motion", s.motion).apply()
        }
    }
}

sealed interface ParseState {
    data object Idle : ParseState
    data object Loading : ParseState
    data class Error(val message: String) : ParseState
    data class Done(val url: String, val videos: List<Video>, val hasPage: Boolean) : ParseState
    data class Live(val url: String, val info: LiveInfo) : ParseState
}

sealed interface PlaylistState {
    data object None : PlaylistState
    data object Loading : PlaylistState
    data class Found(val playlist: Playlist) : PlaylistState
}

sealed interface Sheet {
    data class Episodes(val playlist: Playlist) : Sheet
    data class JobMenu(val jobId: String) : Sheet
    data class Channel(val channel: LiveChannel) : Sheet
    data class JobInfo(val jobId: String) : Sheet
    data class Choice(val key: String) : Sheet
    data object FolderName : Sheet
    data object Licenses : Sheet
}

data class Toast(val text: String, val error: Boolean = false, val id: Long = System.nanoTime())

class AppState(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val engine = Engine.get(app)
    val jobs: StateFlow<List<Job>> = engine.jobs

    var tab by mutableStateOf(0)
    var input by mutableStateOf("")
    var parse by mutableStateOf<ParseState>(ParseState.Idle)
    var playlist by mutableStateOf<PlaylistState>(PlaylistState.None)
    var selectedQuality = mutableStateOf<Map<String, String>>(emptyMap())
    var added = mutableStateListOf<String>()
    var palette by mutableStateOf<List<Color>?>(null)
    var toast by mutableStateOf<Toast?>(null)
    var ui by mutableStateOf(UiSettings.load(app))
        private set
    var save by mutableStateOf(SaveSettings.load(app))
        private set
    var maxJobs by mutableStateOf(2)
    var notify by mutableStateOf(true)
    var clipboardHint by mutableStateOf<String?>(null)
    /** 当前打开的底部面板（见 AppRoot） */
    var sheet by mutableStateOf<Sheet?>(null)
    val recent = mutableStateListOf<String>().apply { addAll(loadRecent()) }

    private var parseJob: CoJob? = null
    private var lastClipboard = ""

    init {
        engine.start()
        viewModelScope.launch {
            engine.settings.collect { s ->
                if (s.has("maxJobs")) maxJobs = s.optInt("maxJobs", 2)
                if (s.has("notify")) notify = s.optBoolean("notify", true)
            }
        }
        viewModelScope.launch { engine.errors.collect { show(it, true) } }
    }

    fun show(text: String, error: Boolean = false) { toast = Toast(text, error) }

    // -------------------------------------------------------------------
    // 解析
    // -------------------------------------------------------------------
    fun submit(text: String = input) {
        val t = text.trim()
        if (t.isEmpty()) { parse = ParseState.Error("请先粘贴央视网视频链接"); return }
        input = t
        parseJob?.cancel()
        parse = ParseState.Loading
        playlist = PlaylistState.None
        added.clear()
        palette = null
        parseJob = viewModelScope.launch {
            try {
                val r = engine.call("link:resolve", JSONObject().put("input", t), 60_000)
                r.optJSONObject("live")?.let { live ->
                    parse = ParseState.Live(r.optString("url"), LiveInfo.parse(live))
                    remember(t)
                    return@launch
                }
                val videos = r.optJSONArray("videos").objects().map(Video::parse)
                if (videos.isEmpty()) throw Engine.EngineException(r.optJSONArray("errors")?.optJSONObject(0)?.optString("error") ?: "没有解析到视频")
                val url = r.optString("url")
                selectedQuality.value = videos.associate { v -> v.guid to (pick(v)?.id ?: "") }
                parse = ParseState.Done(url, videos, r.optBoolean("hasPage"))
                remember(t)
                if (url.isNotEmpty()) loadPlaylist(url)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                parse = ParseState.Error(e.message ?: "解析失败")
            }
        }
    }

    private fun pick(v: Video) = when (ui.quality) {
        "best" -> v.qualities.firstOrNull()
        else -> ui.quality.toIntOrNull()?.let { lim -> v.qualities.firstOrNull { it.height in 1..lim } } ?: v.qualities.lastOrNull()
    }

    private fun loadPlaylist(url: String) {
        playlist = PlaylistState.Loading
        viewModelScope.launch {
            playlist = try {
                val p = Playlist.parse(engine.call("link:playlist", JSONObject().put("url", url), 60_000))
                if (p.items.size > 1) PlaylistState.Found(p) else PlaylistState.None
            } catch (e: Exception) { PlaylistState.None }
        }
    }

    fun clearParse() {
        parseJob?.cancel(); parse = ParseState.Idle; playlist = PlaylistState.None; input = ""; palette = null
    }

    fun download(v: Video) {
        val q = selectedQuality.value[v.guid] ?: v.qualities.firstOrNull()?.id ?: return
        val pageUrl = (parse as? ParseState.Done)?.url ?: ""
        viewModelScope.launch {
            try {
                engine.call("job:add", JSONObject().put("video", v.raw).put("qualityId", q).put("meta", JSONObject().put("pageUrl", pageUrl)))
                added += v.guid
                show("已加入下载：${v.title}")
            } catch (e: Exception) { show(e.message ?: "无法开始下载", true) }
        }
    }

    fun downloadPlaylist(p: Playlist, items: List<PlaylistItem>, quality: String) {
        // 剧集按集数编号（列表已按集数排序）
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject(item.raw.toString())
            if (p.source == "album") o.put("episode", p.items.indexOf(item) + 1)
            arr.put(o)
        }
        viewModelScope.launch {
            try {
                val r = engine.call("playlist:enqueue", JSONObject().put("items", arr).put("pref", quality).put("series", p.name))
                show("已加入 ${r.optInt("accepted", items.size)} ${p.unit}，正在排队下载")
                tab = 2
            } catch (e: Exception) { show(e.message ?: "无法加入下载", true) }
        }
    }

    // -------------------------------------------------------------------
    // 直播 / 回看
    // -------------------------------------------------------------------
    /** 频道 id → (正在播出, 下一档) */
    var liveNow by mutableStateOf<Map<String, Pair<Program?, Program?>>>(emptyMap())
    var liveChannels by mutableStateOf<List<LiveChannel>>(emptyList())
    var liveNowLoading by mutableStateOf(false)

    fun loadLiveNow(force: Boolean = false) {
        if (liveNowLoading || (!force && liveNow.isNotEmpty())) return
        liveNowLoading = true
        viewModelScope.launch {
            try {
                if (liveChannels.isEmpty()) liveChannels = engine.call("live:channels").optJSONArray("channels").objects()
                    .map { LiveChannel(it.optString("id"), it.optString("name")) }
                val now = engine.call("live:now", JSONObject(), 30_000).optJSONObject("now") ?: JSONObject()
                val map = mutableMapOf<String, Pair<Program?, Program?>>()
                now.keys().forEach { k ->
                    val o = now.optJSONObject(k) ?: return@forEach
                    fun p(x: JSONObject?) = x?.let { Program(it.optString("title"), it.optLong("start"), it.optLong("end")) }
                    map[k] = p(o.optJSONObject("now")) to p(o.optJSONObject("next"))
                }
                liveNow = map
            } catch (e: Exception) {
                if (liveChannels.isEmpty()) show(e.message ?: "无法读取频道列表", true)
            } finally { liveNowLoading = false }
        }
    }

    suspend fun channelInfo(channel: String): LiveInfo = LiveInfo.parse(engine.call("live:info", JSONObject().put("channel", channel), 30_000))

    suspend fun epg(channel: String, date: String): List<Program> =
        LiveInfo.parsePrograms(engine.call("live:epg", JSONObject().put("channel", channel).put("date", date), 30_000).optJSONArray("programs"))

    fun record(info: LiveInfo, until: Long, programTitle: String) {
        viewModelScope.launch {
            try {
                engine.call("live:record", JSONObject().put("channel", info.channel).put("channelName", info.channelName)
                    .put("until", until).put("programTitle", programTitle))
                show("已开始录制 ${info.channelName}")
                sheet = null
                tab = 2
            } catch (e: Exception) { show(e.message ?: "无法开始录制", true) }
        }
    }

    /** 返回是否成功加入 */
    suspend fun replay(info: LiveInfo, p: Program): Boolean = try {
        engine.call("live:replay", JSONObject().put("channel", info.channel).put("channelName", info.channelName)
            .put("start", p.start).put("end", p.end).put("title", p.title))
        show("已加入下载：${p.title}")
        true
    } catch (e: Exception) { show(e.message ?: "无法保存回看", true); false }

    // -------------------------------------------------------------------
    // 任务
    // -------------------------------------------------------------------
    fun extendRecording(id: String, minutes: Int) {
        viewModelScope.launch {
            runCatching { engine.call("job:extend", JSONObject().put("id", id).put("minutes", minutes)) }
                .onSuccess { r -> show("已延长，" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(r.optLong("until"))) + " 结束") }
                .onFailure { show(it.message ?: "无法延长", true) }
        }
    }

    fun jobAction(type: String, id: String) {
        viewModelScope.launch { runCatching { engine.call(type, JSONObject().put("id", id)) }.onFailure { show(it.message ?: "操作失败", true) } }
    }

    fun clearFinished() {
        viewModelScope.launch { runCatching { engine.call("jobs:clear") } }
    }

    /** 检查已完成任务的文件是否还在（被用户在文件管理里删掉或移动） */
    fun verifyFiles(list: List<Job>) {
        viewModelScope.launch(Dispatchers.IO) {
            val missing = list.filter { it.status == JobStatus.COMPLETED && !it.missing && it.uri.isNotEmpty() && !FileActions.exists(ctx, it.uri) }.map { it.id }
            if (missing.isNotEmpty()) runCatching { engine.call("job:missing", JSONObject().put("ids", JSONArray(missing))) }
        }
    }

    fun deleteFile(job: Job) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { FileActions.delete(ctx, job.uri) }
            if (ok) { engine.call("job:remove", JSONObject().put("id", job.id)); show("已删除文件") }
            else show("无法删除文件，可能已被移动", true)
        }
    }

    // -------------------------------------------------------------------
    // 设置
    // -------------------------------------------------------------------
    fun updateUi(s: UiSettings) { ui = s; UiSettings.store(ctx, s) }
    fun updateSave(s: SaveSettings) { save = s; SaveSettings.store(ctx, s) }

    fun updateEngine(patch: JSONObject) {
        if (patch.has("maxJobs")) maxJobs = patch.getInt("maxJobs")
        if (patch.has("notify")) notify = patch.getBoolean("notify")
        engine.updateSettings(patch)
        viewModelScope.launch { runCatching { engine.call("settings:set", JSONObject().put("patch", patch)) } }
    }

    fun setCustomFolder(tree: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val label = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            docId.substringAfter(':').ifEmpty { "存储根目录" }.substringAfterLast('/').ifEmpty { docId }
        }.getOrDefault("自定义文件夹")
        updateSave(save.copy(location = com.chouchang.cctvdl.save.SaveLocation.CUSTOM, customTree = tree.toString(), customLabel = label))
        show("保存位置已设为「$label」")
    }

    // -------------------------------------------------------------------
    // 剪贴板 / 外部链接
    // -------------------------------------------------------------------
    fun checkClipboard() {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull().orEmpty().trim()
        if (text.isEmpty() || text == lastClipboard || text == input) return
        if (LINK.containsMatchIn(text) || GUID.matches(text)) { clipboardHint = text; lastClipboard = text }
    }

    fun readClipboard(): String {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return ""
        return runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull().orEmpty().trim()
    }

    fun openShared(text: String) {
        if (text.isBlank()) return
        tab = 0
        clipboardHint = null
        viewModelScope.launch { delay(250); submit(text) }
    }

    private fun loadRecent(): List<String> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences("ui", 0).getString("recent", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun remember(t: String) {
        recent.remove(t); recent.add(0, t)
        while (recent.size > 6) recent.removeAt(recent.lastIndex)
        ctx.getSharedPreferences("ui", 0).edit().putString("recent", JSONArray(recent.toList()).toString()).apply()
    }

    fun forgetRecent() { recent.clear(); ctx.getSharedPreferences("ui", 0).edit().remove("recent").apply() }

    companion object {
        val LINK = Regex("https?://[^\\s]*(cctv|cntv)\\.(com|cn)", RegexOption.IGNORE_CASE)
        val GUID = Regex("^[0-9a-fA-F]{32}$")
    }
}
