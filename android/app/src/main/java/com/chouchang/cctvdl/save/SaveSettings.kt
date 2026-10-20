package com.chouchang.cctvdl.save

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale

/** 保存位置 */
enum class SaveLocation(val label: String, val detail: String) {
    DOWNLOADS("下载", "系统「下载」目录，文件管理器中随时可见"),
    MOVIES("影片", "系统「Movies」目录，会出现在相册和视频播放器中"),
    CUSTOM("自定义文件夹", "通过系统文件选择器指定，可选 SD 卡或其他位置"),
}

/** 文件命名规则 */
enum class NamingRule(val label: String) {
    TITLE_QUALITY("标题_清晰度"),
    TITLE("标题"),
    SERIES_EPISODE("剧名 第N集"),
    DATE_TITLE("日期_标题"),
}

/** 遇到同名文件 */
enum class ConflictRule(val label: String, val detail: String) {
    RENAME("自动编号", "保留旧文件，新文件名后加 (2)、(3)…"),
    OVERWRITE("覆盖", "替换本应用之前保存的同名文件"),
    SKIP("跳过", "已存在同名文件时不再重复下载"),
}

data class SaveSettings(
    val location: SaveLocation = SaveLocation.DOWNLOADS,
    val customTree: String = "",
    val customLabel: String = "",
    val folder: String = "CCTV",
    val seriesFolder: Boolean = true,
    val naming: NamingRule = NamingRule.TITLE_QUALITY,
    val conflict: ConflictRule = ConflictRule.RENAME,
    val saveCover: Boolean = false,
) {
    /** 实际生效的位置：自定义文件夹未授权时退回「下载」 */
    val effectiveLocation: SaveLocation
        get() = if (location == SaveLocation.CUSTOM && customTree.isEmpty()) SaveLocation.DOWNLOADS else location

    val coverSupported: Boolean get() = effectiveLocation != SaveLocation.MOVIES || Build.VERSION.SDK_INT < 29

    fun toJson(): JSONObject = JSONObject()
        .put("location", location.name).put("customTree", customTree).put("customLabel", customLabel)
        .put("folder", folder).put("seriesFolder", seriesFolder).put("naming", naming.name)
        .put("conflict", conflict.name).put("saveCover", saveCover)

    companion object {
        fun fromJson(o: JSONObject?): SaveSettings {
            if (o == null) return SaveSettings()
            fun <T : Enum<T>> e(values: Array<T>, key: String, def: T) =
                values.firstOrNull { it.name == o.optString(key) } ?: def
            return SaveSettings(
                location = e(SaveLocation.entries.toTypedArray(), "location", SaveLocation.DOWNLOADS),
                customTree = o.optString("customTree"),
                customLabel = o.optString("customLabel"),
                folder = cleanFolder(o.optString("folder", "CCTV")),
                seriesFolder = o.optBoolean("seriesFolder", true),
                naming = e(NamingRule.entries.toTypedArray(), "naming", NamingRule.TITLE_QUALITY),
                conflict = e(ConflictRule.entries.toTypedArray(), "conflict", ConflictRule.RENAME),
                saveCover = o.optBoolean("saveCover", false),
            )
        }

        private const val PREFS = "save"

        fun load(ctx: Context): SaveSettings = runCatching {
            fromJson(JSONObject(ctx.getSharedPreferences(PREFS, 0).getString("json", "{}") ?: "{}"))
        }.getOrDefault(SaveSettings())

        fun store(ctx: Context, s: SaveSettings) {
            ctx.getSharedPreferences(PREFS, 0).edit().putString("json", s.toJson().toString()).apply()
        }

        /** 文件夹名：去掉非法字符，允许多级（a/b） */
        fun cleanFolder(f: String): String = f.replace(Regex("[\\\\:*?\"<>|\\u0000-\\u001f]"), "")
            .split('/').map { it.trim().trim('.') }.filter { it.isNotEmpty() }.joinToString("/").take(80)

        fun cleanName(n: String): String = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ")
            .replace(Regex("\\s+"), " ").trim().trim('.').take(120)
    }
}

/** 一个待保存视频的描述（来自下载引擎） */
data class SaveMeta(
    val ext: String,
    val title: String,
    val quality: String,
    val series: String,
    val episode: Int,
    val column: String,
    val channel: String,
    val guid: String,
    val publishedAt: String,
    val pageUrl: String,
    val cover: String,
    val duration: Double,
) {
    companion object {
        fun parse(json: String): SaveMeta {
            val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            return SaveMeta(
                ext = o.optString("ext", "mp4").ifEmpty { "mp4" },
                title = o.optString("title").ifEmpty { "CCTV视频" },
                quality = o.optString("quality"),
                series = o.optString("series"),
                episode = o.optInt("episode", 0),
                column = o.optString("column"),
                channel = o.optString("channel"),
                guid = o.optString("guid"),
                publishedAt = o.optString("publishedAt"),
                pageUrl = o.optString("pageUrl"),
                cover = o.optString("cover"),
                duration = o.optDouble("duration", 0.0),
            )
        }
    }
}

object Naming {
    /** 「2024-05-01 20:00:00」→ 20240501 */
    fun dateStamp(publishedAt: String): String {
        val m = Regex("(\\d{4})[-/.年]?(\\d{1,2})[-/.月]?(\\d{1,2})").find(publishedAt) ?: return ""
        val (y, mo, d) = m.destructured
        return String.format(Locale.ROOT, "%s%02d%02d", y, mo.toInt(), d.toInt())
    }

    fun fileStem(meta: SaveMeta, rule: NamingRule): String {
        val title = SaveSettings.cleanName(meta.title).ifEmpty { "CCTV视频" }
        val stem = when (rule) {
            NamingRule.TITLE -> title
            NamingRule.TITLE_QUALITY -> if (meta.quality.isNotEmpty()) "${title}_${meta.quality}" else title
            NamingRule.SERIES_EPISODE -> {
                val series = SaveSettings.cleanName(meta.series)
                if (series.isNotEmpty() && meta.episode > 0) String.format(Locale.ROOT, "%s 第%02d集", series, meta.episode)
                else title
            }
            NamingRule.DATE_TITLE -> dateStamp(meta.publishedAt).let { if (it.isEmpty()) title else "${it}_$title" }
        }
        return SaveSettings.cleanName(stem).ifEmpty { "CCTV视频" }
    }

    /** 相对于保存位置根目录的子目录（不含首尾斜杠） */
    fun subFolder(meta: SaveMeta, s: SaveSettings): String {
        val parts = mutableListOf<String>()
        if (s.folder.isNotEmpty()) parts += s.folder
        if (s.seriesFolder) SaveSettings.cleanName(meta.series).takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString("/")
    }

    /** 设置页里的示例 */
    fun preview(s: SaveSettings): String {
        val sample = SaveMeta("mp4", "觉醒年代 第3集", "超清", "觉醒年代", 3, "", "CCTV-8", "", "2021-02-01 20:00:00", "", "", 0.0)
        val root = when (s.effectiveLocation) {
            SaveLocation.DOWNLOADS -> "下载"
            SaveLocation.MOVIES -> "Movies"
            SaveLocation.CUSTOM -> s.customLabel.ifEmpty { "自定义文件夹" }
        }
        val sub = subFolder(sample, s)
        return listOf(root, sub, fileStem(sample, s.naming) + ".mp4").filter { it.isNotEmpty() }.joinToString(" › ").replace("/", " › ")
    }
}
