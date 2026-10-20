package com.chouchang.cctvdl.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.chouchang.cctvdl.Core
import com.chouchang.cctvdl.DownloadService
import com.chouchang.cctvdl.Notifications
import com.chouchang.cctvdl.data.Job
import com.chouchang.cctvdl.data.objects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载引擎（进程级单例）。
 *
 * 解析、下载、h5e 解密、MP4 封装复用浏览器扩展里验证过的 JS 核心，运行在一个不显示的 WebView 的
 * Web Worker 中；界面是原生 Compose，两者通过 JSON 消息通信。界面关闭后只要进程还在（前台服务保活），
 * 下载就会继续。
 */
class Engine private constructor(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var lastGone = 0L

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _settings = MutableStateFlow(JSONObject())
    val settings: StateFlow<JSONObject> = _settings.asStateFlow()

    /** 引擎级错误（例如渲染进程被回收） */
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // -------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start() }; return }
        if (web != null) return
        val server = try { Core.server(app) } catch (e: Exception) {
            Log.e(TAG, "server", e)
            errors.tryEmit("无法启动本机服务：${e.message}")
            return
        }
        val w = try { WebView(app) } catch (e: RuntimeException) {
            Log.e(TAG, "webview", e)
            errors.tryEmit("系统 WebView 不可用，请在应用商店安装或更新「Android System WebView」")
            return
        }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
        }
        w.addJavascriptInterface(Bridge(), "CctvEngine")
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "[js] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "engine render process gone, crashed=${detail.didCrash()}")
                restart(view)
                return true
            }
        }
        web = w
        w.loadUrl(server.entryUrl())
    }

    private fun restart(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.destroy() }
        if (web === view) web = null
        _ready.value = false
        failPending("下载引擎已重启，请重试")
        val now = System.currentTimeMillis()
        val loop = now - lastGone < 20_000
        lastGone = now
        errors.tryEmit(if (loop) "下载引擎反复崩溃，请更新系统 WebView" else "下载引擎已重启，进行中的任务已标记为中断")
        if (!loop) main.postDelayed({ start() }, 400)
    }

    /** 挂到界面上（1×1、不可见），保证 WebView 的计时器与渲染不被系统挂起 */
    fun attach(activity: Activity) {
        val w = web ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (w.parent === root) return
        (w.parent as? ViewGroup)?.removeView(w)
        w.alpha = 0f
        root.addView(w, 0, FrameLayout.LayoutParams(1, 1))
        w.onResume()
    }

    fun detach(activity: Activity) {
        val w = web ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (w.parent === root) root.removeView(w)
    }

    private fun failPending(msg: String) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.completeExceptionally(EngineException(msg)) }
    }

    // -------------------------------------------------------------------
    // 调用
    // -------------------------------------------------------------------
    suspend fun call(type: String, payload: JSONObject = JSONObject(), timeoutMs: Long = 120_000): JSONObject {
        withTimeout(20_000) { ready.first { it } }
        val id = seq.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        withContext(Dispatchers.Main) {
            val w = web ?: run { pending.remove(id); throw EngineException("下载引擎未启动") }
            w.evaluateJavascript("Engine.call($id, ${JSONObject.quote(type)}, ${JSONObject.quote(payload.toString())})", null)
        }
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
    }

    private fun onMessage(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (o.has("reply")) {
            val d = pending.remove(o.optInt("reply")) ?: return
            if (o.optBoolean("ok")) d.complete(o.optJSONObject("data") ?: JSONObject())
            else d.completeExceptionally(EngineException(o.optString("error").ifEmpty { "操作失败" }))
            return
        }
        when (o.optString("event")) {
            "jobs" -> _jobs.value = o.optJSONArray("jobs").objects().map(Job::parse)
            "ready" -> { _settings.value = o.optJSONObject("settings") ?: JSONObject(); _ready.value = true }
            "save" -> Core.saveState(app, o.optString("state", "{}"))
            "error" -> errors.tryEmit(o.optString("message"))
            "native" -> native(o.optString("fn"), o.optJSONArray("args"))
        }
    }

    private fun native(fn: String, args: org.json.JSONArray?) {
        val a = args ?: org.json.JSONArray()
        when (fn) {
            "progress" -> DownloadService.update(app, a.optInt(0), a.optInt(1), a.optString(2))
            "notifyDone" -> Notifications.done(app, a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optBoolean(4))
        }
    }

    /** 仅供测试：注入任务列表 */
    @androidx.annotation.VisibleForTesting
    fun setJobsForTest(list: List<Job>) { _jobs.value = list }

    fun updateSettings(patch: JSONObject) {
        _settings.value = JSONObject(_settings.value.toString()).also { s -> patch.keys().forEach { s.put(it, patch.get(it)) } }
    }

    /** 页面调用的原生接口：所有异常都要吞掉，否则会让应用崩溃 */
    inner class Bridge {
        @JavascriptInterface fun post(json: String) {
            try { onMessage(json) } catch (t: Throwable) { Log.w(TAG, "bridge.post", t) }
        }
        @JavascriptInterface fun state(): String = try { Core.loadState(app) } catch (t: Throwable) { "{}" }
    }

    class EngineException(msg: String) : Exception(msg)

    companion object {
        private const val TAG = "CCTVDL"
        @Volatile private var instance: Engine? = null
        fun get(ctx: Context): Engine = instance ?: synchronized(this) {
            instance ?: Engine(ctx.applicationContext).also { instance = it }
        }
    }
}
