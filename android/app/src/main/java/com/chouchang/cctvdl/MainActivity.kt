package com.chouchang.cctvdl

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chouchang.cctvdl.data.JobStatus
import com.chouchang.cctvdl.ui.AppRoot
import com.chouchang.cctvdl.ui.AppState
import com.chouchang.cctvdl.ui.theme.CctvTheme
import com.chouchang.cctvdl.ui.theme.GlassMode
import com.chouchang.cctvdl.ui.theme.LocalDark
import com.chouchang.cctvdl.ui.theme.LocalGlass
import com.chouchang.cctvdl.ui.theme.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Notifications.ensureChannels(this) }
        enableEdgeToEdge()
        setContent {
            val ui = state.ui
            val dark = when (ui.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                onDispose {}
            }
            val systemMotion = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            CompositionLocalProvider(LocalDark provides dark, LocalGlass provides GlassMode.resolve(ui.glass),
                com.chouchang.cctvdl.ui.components.LocalMotion provides (ui.motion && systemMotion)) {
                CctvTheme(dark) { AppRoot(state) }
            }
            LaunchedEffect(Unit) { state.engine.ready.collect { if (it) state.engine.attach(this@MainActivity) } }
        }
        App.takeCrash(this)?.let { showCrash(it) }
        if (savedInstanceState == null) handleIntent(intent)

        // 首次开始下载时请求需要的权限
        lifecycleScope.launch {
            state.jobs.map { list -> list.any { it.status.active } }.distinctUntilChanged().collect { active -> if (active) askPermissions() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { window.decorView.post { state.checkClipboard() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        state.engine.detach(this)
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when {
            i.getStringExtra("tab") == "jobs" -> state.tab = 2
            i.action == Intent.ACTION_SEND -> state.openShared(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            i.action == Intent.ACTION_VIEW && i.data != null -> state.openShared(i.dataString.orEmpty())
        }
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val prefs = getSharedPreferences("ui", 0)
        if (need.isNotEmpty() && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(need.toTypedArray(), 7)
        }
    }

    private fun showCrash(text: String) {
        val tv = TextView(this).apply {
            this.text = text; setTextIsSelectable(true); textSize = 11f
            val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("上次运行时出错了")
            .setView(ScrollView(this).apply { addView(tv) })
            .setNeutralButton("复制") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CCTV 下载器崩溃记录", text))
                state.show("已复制，可以发给开发者")
            }
            .setPositiveButton("知道了", null)
            .show()
    }
}
