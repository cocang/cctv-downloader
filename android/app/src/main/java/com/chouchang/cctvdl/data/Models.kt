package com.chouchang.cctvdl.data

import org.json.JSONArray
import org.json.JSONObject

data class Quality(val id: String, val name: String, val short: String, val resolution: String, val bandwidth: Long) {
    val height: Int get() = resolution.substringAfter('x', "0").toIntOrNull() ?: 0
}

data class Video(
    val guid: String,
    val title: String,
    val channel: String,
    val column: String,
    val cover: String,
    val duration: Double,
    val publishedAt: String,
    val encrypted: Boolean,
    val qualities: List<Quality>,
    val raw: JSONObject,
) {
    companion object {
        fun parse(o: JSONObject) = Video(
            guid = o.optString("guid"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            column = o.optString("column"),
            cover = o.optString("cover"),
            duration = o.optDouble("duration", 0.0),
            publishedAt = o.optString("publishedAt"),
            encrypted = o.optBoolean("encrypted"),
            qualities = o.optJSONArray("qualities").objects().map {
                Quality(it.optString("id"), it.optString("name"), it.optString("short"), it.optString("resolution"), it.optLong("bandwidth"))
            },
            raw = o,
        )
    }
}

data class PlaylistItem(val url: String, val guid: String, val title: String, val cover: String, val duration: Double, val date: String, val raw: JSONObject)

data class Playlist(val source: String, val name: String, val total: Int, val items: List<PlaylistItem>) {
    /** 剧集按「集」计数，其余按「期 / 个」 */
    val unit: String get() = if (source == "album") "集" else "期"
    val kind: String get() = when (source) { "album" -> "整部剧"; "column", "vplist" -> "栏目"; else -> "本页视频" }

    companion object {
        fun parse(o: JSONObject) = Playlist(
            source = o.optString("source"),
            name = o.optString("name"),
            total = o.optInt("total"),
            items = o.optJSONArray("items").objects().map {
                PlaylistItem(it.optString("url"), it.optString("guid"), it.optString("title"), it.optString("cover"),
                    it.optDouble("duration", 0.0), it.optString("date"), it)
            },
        )
    }
}

enum class JobStatus(val label: String) {
    QUEUED("排队中"), PREPARING("准备中"), DOWNLOADING("下载中"), RECORDING("录制中"), SAVING("正在保存"),
    COMPLETED("已完成"), FAILED("失败"), CANCELLED("已取消");

    val active get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == RECORDING || this == SAVING

    companion object {
        fun of(s: String) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: FAILED
    }
}

data class Job(
    val id: String,
    val guid: String,
    val title: String,
    val cover: String,
    val channel: String,
    val duration: Double,
    val qualityName: String,
    val qualityShort: String,
    val status: JobStatus,
    val done: Int,
    val total: Int,
    val bytes: Long,
    val speed: Double,
    val eta: Double,
    val error: String,
    val filename: String,
    val uri: String,
    val size: Long,
    val createdAt: Long,
    val finishedAt: Long,
    val tint: String,
    val checkLevel: String,
    val checkMessage: String,
    val series: String,
    val episode: Int,
    val missing: Boolean,
    val existed: Boolean,
    val location: String,
    val unresolved: Boolean,
    /** "" 点播 · "live" 直播录制 · "replay" 回看 */
    val kind: String = "",
    val recorded: Double = 0.0,
    val until: Long = 0,
    val recordStartedAt: Long = 0,
    val gaps: Int = 0,
) {
    val isLive get() = kind == "live"

    val progress: Float get() = when {
        status == JobStatus.COMPLETED -> 1f
        total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
        else -> 0f
    }

    companion object {
        fun parse(o: JSONObject): Job {
            val q = o.optJSONObject("quality") ?: JSONObject()
            val c = o.optJSONObject("check") ?: JSONObject()
            return Job(
                id = o.optString("id"), guid = o.optString("guid"), title = o.optString("title"),
                cover = o.optString("cover"), channel = o.optString("channel"), duration = o.optDouble("duration", 0.0),
                qualityName = q.optString("name"), qualityShort = q.optString("short"),
                status = JobStatus.of(o.optString("status")),
                done = o.optInt("done"), total = o.optInt("total"), bytes = o.optLong("bytes"),
                speed = o.optDouble("speed", 0.0), eta = o.optDouble("eta", 0.0),
                error = o.optString("error"), filename = o.optString("filename"), uri = o.optString("uri"),
                size = o.optLong("size"), createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"),
                tint = o.optString("tint"), checkLevel = c.optString("level"), checkMessage = c.optString("message"),
                series = o.optString("series"), episode = o.optInt("episode"), missing = o.optBoolean("missing"),
                existed = o.optBoolean("existed"), location = o.optString("location"), unresolved = o.optBoolean("unresolved"),
                kind = o.optString("kind"), recorded = o.optDouble("recorded", 0.0), until = o.optLong("until"),
                recordStartedAt = o.optLong("recordStartedAt"), gaps = o.optInt("gaps"),
            )
        }
    }
}

data class Program(val title: String, val start: Long, val end: Long) {
    fun isLive(now: Long = System.currentTimeMillis()) = start <= now && end > now
    fun isFuture(now: Long = System.currentTimeMillis()) = start > now
}

data class LiveChannel(val id: String, val name: String) {
    /** 「CCTV-13 新闻」→「CCTV-13」与「新闻」 */
    val short: String get() = name.substringBefore(' ')
    val sub: String get() = name.substringAfter(' ', "")
}

data class LiveInfo(
    val mode: String,
    val channel: String,
    val channelName: String,
    val now: String,
    val programs: List<Program>,
    val noEpg: Boolean,
    val channels: List<LiveChannel>,
) {
    val current: Program? get() = programs.firstOrNull { it.isLive() }

    companion object {
        fun parsePrograms(a: JSONArray?) = a.objects().map { Program(it.optString("title"), it.optLong("start"), it.optLong("end")) }
        fun parse(o: JSONObject) = LiveInfo(
            mode = o.optString("mode", "live"),
            channel = o.optString("channel"),
            channelName = o.optString("channelName"),
            now = o.optString("now"),
            programs = parsePrograms(o.optJSONArray("programs")),
            noEpg = o.optBoolean("noEpg"),
            channels = o.optJSONArray("channels").objects().map { LiveChannel(it.optString("id"), it.optString("name")) },
        )
    }
}

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
