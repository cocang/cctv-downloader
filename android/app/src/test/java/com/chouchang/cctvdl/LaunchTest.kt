package com.chouchang.cctvdl

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chouchang.cctvdl.save.ConflictRule
import com.chouchang.cctvdl.save.Naming
import com.chouchang.cctvdl.save.NamingRule
import com.chouchang.cctvdl.save.SaveMeta
import com.chouchang.cctvdl.save.SaveSettings
import com.chouchang.cctvdl.ui.AppState
import com.chouchang.cctvdl.ui.Sheet
import com.chouchang.cctvdl.ui.theme.GlassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 启动与各页面切换不崩溃（覆盖 Android 8 / 10 / 11 / 13 / 15），以及保存命名规则 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LaunchTest {

    /** 启动前写入设置：关闭循环动效、跳过权限请求 */
    private val prefs = object : org.junit.rules.ExternalResource() {
        override fun before() {
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
                .getSharedPreferences("ui", 0).edit().putBoolean("motion", false).putBoolean("asked", true).commit()
        }
    }
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()
    @get:org.junit.Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(prefs).around(compose)

    private fun idle(ms: Long = 600) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }

    private fun walk() {
        compose.mainClock.autoAdvance = false
        val s = ViewModelProvider(compose.activity)[AppState::class.java]
        idle(1200)
        s.openShared("看这个 https://tv.cctv.com/2026/06/04/VIDEHt8P0uS4S8BiqYxXCi31260604.shtml")
        idle(800)
        for (t in listOf(1, 2, 3, 0)) { compose.runOnUiThread { s.tab = t }; idle() }
        for (k in listOf("glass", "location", "naming", "conflict", "quality")) {
            compose.runOnUiThread { s.sheet = Sheet.Choice(k) }; idle(); compose.runOnUiThread { s.sheet = null }; idle(300)
        }
        compose.runOnUiThread { s.sheet = Sheet.FolderName }; idle()
        compose.runOnUiThread { s.sheet = Sheet.Licenses }; idle()
        compose.runOnUiThread { s.sheet = null }; idle()
    }

    @Test @Config(sdk = [26]) fun api26() = walk()
    @Test @Config(sdk = [29]) fun api29() = walk()
    @Test @Config(sdk = [30]) fun api30() = walk()
    @Test @Config(sdk = [33]) fun api33() = walk()
    @Test @Config(sdk = [35]) fun api35() = walk()

    @Test @Config(sdk = [35]) fun glassResolve() {
        assertEquals(GlassMode.LIQUID, GlassMode.resolve(null))
        assertEquals(GlassMode.BLUR, GlassMode.resolve(GlassMode.BLUR))
    }

    @Test @Config(sdk = [30]) fun glassFallbackOnAndroid11() {
        assertEquals(GlassMode.TRANSLUCENT, GlassMode.resolve(null))
        assertEquals(GlassMode.TRANSLUCENT, GlassMode.resolve(GlassMode.LIQUID))
    }

    @Test fun naming() {
        val m = SaveMeta("mp4", "《觉醒年代》第3集: 新青年?", "超清", "觉醒年代", 3, "", "", "", "2021-02-01 20:00:00", "", "", 0.0)
        assertEquals("《觉醒年代》第3集 新青年_超清", Naming.fileStem(m, NamingRule.TITLE_QUALITY))
        assertEquals("觉醒年代 第03集", Naming.fileStem(m, NamingRule.SERIES_EPISODE))
        assertEquals("20210201_《觉醒年代》第3集 新青年", Naming.fileStem(m, NamingRule.DATE_TITLE))
        val s = SaveSettings(folder = "CCTV/央视", seriesFolder = true)
        assertEquals("CCTV/央视/觉醒年代", Naming.subFolder(m, s))
        assertEquals("a/b", SaveSettings.cleanFolder("/a/../b/"))
        val rt = SaveSettings.fromJson(SaveSettings(conflict = ConflictRule.SKIP, naming = NamingRule.DATE_TITLE).toJson())
        assertTrue(rt.conflict == ConflictRule.SKIP && rt.naming == NamingRule.DATE_TITLE)
    }
}
