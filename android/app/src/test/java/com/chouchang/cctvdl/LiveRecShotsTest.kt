package com.chouchang.cctvdl

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chouchang.cctvdl.data.LiveInfo
import com.chouchang.cctvdl.data.Program
import com.chouchang.cctvdl.ui.AppState
import com.chouchang.cctvdl.ui.screens.LivePanel
import com.chouchang.cctvdl.ui.theme.CctvTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 录制规划面板截图（浅色 + 深色）。需 X86=1 运行（原生图形）。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi", sdk = [35])
class LiveRecShotsTest {
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()
    @get:org.junit.Rule val rule = compose
    private val dir = System.getProperty("shots.dir") ?: "build/shots"

    private fun idle(ms: Long) {
        var t = 0L
        while (t < ms) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); Thread.sleep(10); t += 100 }
    }

    @Test fun recPlanner() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.getSharedPreferences("ui", 0).edit().putBoolean("asked", true).putBoolean("motion", false).putString("rec.mode", "timed").commit()
        compose.mainClock.autoAdvance = false
        val state = ViewModelProvider(compose.activity)[AppState::class.java]
        val now = System.currentTimeMillis()
        val m = 60_000L
        val titles = listOf("新闻直播间", "共同关注", "新闻联播", "焦点访谈", "新闻 1+1", "国际时讯", "环球视线", "新闻调查", "午夜新闻")
        val starts = listOf(-25L, 38L, 60L, 90L, 110L, 150L, 190L, 230L, 290L, 360L)
        val programs = titles.indices.map { Program(titles[it], now + starts[it] * m, now + starts[it + 1] * m) }
        val info = LiveInfo("live", "cctv13", "CCTV-13 新闻", "", programs, false, emptyList())
        File(dir).mkdirs()
        for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                compose.activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(com.chouchang.cctvdl.ui.theme.LocalDark provides dark) { CctvTheme(dark) {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            if (dark) listOf(Color(0xFF0F1830), Color(0xFF1D2A4D), Color(0xFF3A3552)) else listOf(Color(0xFFB9D3F0), Color(0xFFE4EEFA), Color(0xFFF6E7DC))))) {
                            LivePanel(state, info, modifier = Modifier.statusBarsPadding().padding(16.dp))
                        }
                    } }
                }
            }
            idle(2500)
            captureScreenRoboImage("$dir/live-rec-${if (dark) "dark" else "light"}.png")
        }
    }
}
