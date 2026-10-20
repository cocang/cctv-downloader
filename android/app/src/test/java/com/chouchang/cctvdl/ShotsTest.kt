@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.chouchang.cctvdl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chouchang.cctvdl.data.Job
import com.chouchang.cctvdl.data.JobStatus
import com.chouchang.cctvdl.data.Playlist
import com.chouchang.cctvdl.data.PlaylistItem
import com.chouchang.cctvdl.data.Video
import com.chouchang.cctvdl.ui.AppState
import com.chouchang.cctvdl.ui.ParseState
import com.chouchang.cctvdl.ui.PlaylistState
import com.chouchang.cctvdl.ui.Sheet
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 界面截图（Robolectric 原生图形）：启动真实的 MainActivity，注入示例数据，依次截取各页面。
 * 截图输出到 build/shots/。液态玻璃的折射着色器（AGSL）在 Robolectric 中不可用，截图里只会看到模糊与高光。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi")
class ShotsTest {
    private var dark = false
    private var glass: String? = null
    private val prefs = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
            val name = names.methodName
            dark = name.startsWith("dark") || name.endsWith("26")
            glass = if (name == "blurMode") "BLUR" else null
            ctx.getSharedPreferences("ui", 0).edit()
                .putString("theme", if (dark) "DARK" else "LIGHT").putString("glass", glass)
                .putBoolean("asked", true).putBoolean("motion", false)
                .putString("recent", JSONArray(listOf("https://tv.cctv.com/2021/02/01/VIDE觉醒年代.shtml", "https://tv.cctv.com/lm/xwlb/")).toString())
                .commit()
        }
    }
    private val names = org.junit.rules.TestName()
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()
    @get:org.junit.Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(names).around(prefs).around(compose)

    private val dir = System.getProperty("shots.dir") ?: "build/shots"

    @Test @Config(sdk = [35]) fun lightAuto() = shoot("a-light", dark = false, glass = null, full = true)
    @Test @Config(sdk = [35]) fun darkAuto() = shoot("b-dark", dark = true, glass = null, full = true)
    @Test @Config(sdk = [33]) fun blurMode() = shoot("c-blur", dark = false, glass = "BLUR", full = false)
    @Test @Config(sdk = [29]) fun translucentApi29() = shoot("d-api29", dark = false, glass = null, full = false)
    @Test @Config(sdk = [26]) fun translucentApi26() = shoot("e-api26", dark = true, glass = null, full = false)

    private fun idle(ms: Long) {
        var t = 0L
        while (t < ms) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); Thread.sleep(10); t += 100 }
    }
    private fun ui(block: () -> Unit) = compose.runOnUiThread(block)

    private fun shot(name: String) {
        File(dir).mkdirs()
        captureScreenRoboImage("$dir/$name.png")
    }

    private fun shoot(prefix: String, dark: Boolean, glass: String?, full: Boolean) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.mainClock.autoAdvance = false
        val covers = (0 until 6).map { cover(ctx, it) }
        val state = ViewModelProvider(compose.activity)[AppState::class.java]
        idle(1500)
        shot("$prefix-1-parse")

        // 解析结果 + 剧集
        val video = Video.parse(JSONObject()
            .put("guid", "a".repeat(32)).put("title", "《觉醒年代》第3集 陈独秀回国创办《新青年》")
            .put("channel", "CCTV-8").put("column", "电视剧").put("cover", covers[0]).put("duration", 2712.0)
            .put("publishedAt", "2021-02-01 20:00:00").put("encrypted", true)
            .put("qualities", JSONArray()
                .put(JSONObject().put("id", "4000").put("name", "超清").put("short", "1080P").put("resolution", "1920x1080"))
                .put(JSONObject().put("id", "2000").put("name", "高清").put("short", "720P").put("resolution", "1280x720"))
                .put(JSONObject().put("id", "850").put("name", "标清").put("short", "480P").put("resolution", "854x480"))))
        ui {
            state.input = "https://tv.cctv.com/2021/02/01/VIDEcxjyBAmG.shtml"
            state.selectedQuality.value = mapOf(video.guid to "4000")
            state.parse = ParseState.Done("https://tv.cctv.com/x", listOf(video), true)
        }
        val items = (1..43).map { i ->
            PlaylistItem("https://tv.cctv.com/$i", "%032d".format(i), "《觉醒年代》第${i}集", covers[i % covers.size], 2700.0, "2021-02-01", JSONObject())
        }
        val pl = Playlist("album", "觉醒年代", 43, items)
        ui { state.playlist = PlaylistState.Found(pl) }
        idle(3500)
        shot("$prefix-2-result")
        if (!full) {
            ui { jobs(state, covers); state.tab = 1 }; idle(2500); shot("$prefix-3-downloads")
            ui { state.tab = 2 }; idle(2000); shot("$prefix-4-settings")
            return
        }
        ui { state.sheet = Sheet.Episodes(pl) }
        idle(1500)
        shot("$prefix-3-episodes")
        ui { state.sheet = null }
        idle(800)

        ui { jobs(state, covers); state.tab = 1 }
        idle(2500)
        shot("$prefix-4-downloads")
        ui { state.sheet = Sheet.JobMenu("j2") }
        idle(1500)
        shot("$prefix-5-jobmenu")
        ui { state.sheet = null }
        ui { state.tab = 2 }
        idle(2500)
        shot("$prefix-6-settings")
        ui { state.sheet = Sheet.Choice("glass") }
        idle(1500)
        shot("$prefix-7-material")
        ui { state.sheet = null }
        idle(600)
        ui { state.toast = com.chouchang.cctvdl.ui.Toast("已加入下载：觉醒年代 第3集") }
        idle(700)
        shot("$prefix-8-toast")
    }

    private fun jobs(state: AppState, covers: List<String>) {
        fun job(id: String, title: String, status: JobStatus, done: Int, size: Long, cover: String, err: String = "", ep: Int = 0) = Job(
            id, "g$id", title, cover, "CCTV-8", 2712.0, "超清", "1080P", status, done, 420, done * 1_800_000L, 6_400_000.0, 95.0,
            err, if (status == JobStatus.COMPLETED) "下载/CCTV/觉醒年代/$title" + "_超清.mp4" else "", "content://media/1", size,
            System.currentTimeMillis() - 600_000, if (status == JobStatus.COMPLETED) System.currentTimeMillis() - 120_000 else 0, "",
            if (status == JobStatus.COMPLETED) "ok" else "", "", "觉醒年代", ep, false, false, "下载/CCTV", false)
        state.engine.setJobsForTest(listOf(
            job("j1", "《觉醒年代》第4集", JobStatus.DOWNLOADING, 263, 0, covers[1], ep = 4),
            job("j5", "《觉醒年代》第5集", JobStatus.QUEUED, 0, 0, covers[4], ep = 5),
            job("j2", "《觉醒年代》第3集", JobStatus.COMPLETED, 420, 1_243_000_000, covers[0], ep = 3),
            job("j3", "新闻联播 2026年10月18日", JobStatus.COMPLETED, 420, 412_000_000, covers[2]),
            job("j4", "《焦点访谈》秋粮收获进度", JobStatus.FAILED, 31, 0, covers[3], err = "网络连接中断，已重试 8 次"),
        ))
    }

    /** 生成示例封面（避免联网）：不同色调的渐变与光斑 */
    private fun cover(ctx: android.content.Context, i: Int): String {
        val palettes = listOf(
            intArrayOf(0xFF8B1E1E.toInt(), 0xFFE2A64B.toInt()), intArrayOf(0xFF0E3B6E.toInt(), 0xFF4FA3D9.toInt()),
            intArrayOf(0xFF153E2E.toInt(), 0xFF9BCB6E.toInt()), intArrayOf(0xFF3A1D5C.toInt(), 0xFFE07AA8.toInt()),
            intArrayOf(0xFF4A2F12.toInt(), 0xFFF2C46D.toInt()), intArrayOf(0xFF102A43.toInt(), 0xFFF0B429.toInt()),
        )
        val (a, b) = palettes[i % palettes.size].let { it[0] to it[1] }
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 640f, 360f, Paint().apply { shader = LinearGradient(0f, 0f, 640f, 360f, a, b, Shader.TileMode.CLAMP) })
        c.drawCircle(470f, 120f, 160f, Paint().apply { shader = RadialGradient(470f, 120f, 160f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP) })
        c.drawRect(0f, 250f, 640f, 360f, Paint().apply { shader = LinearGradient(0f, 250f, 0f, 360f, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP) })
        val f = File(ctx.cacheDir, "cover$i.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://" + f.absolutePath
    }
}
