package com.yenaly.han1meviewer.logic.ech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import echproxy.Echproxy
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.util.DiagnosticsLog
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Owns the app-private loopback ECH proxy. The app's ProxySelector supplies this
 * port to every normal HTTP client, so client URLs and cookies stay unchanged.
 *
 * Every JNI call is guarded: if the native library cannot load or the Go proxy
 * fails, ECH is simply unavailable and the app keeps its normal network path.
 * ECH starts a few seconds after process start to avoid the Application
 * initialization window.
 */
object EchProxyManager {
    private const val TAG = "EchProxy"
    // ech-proxy-go's current desktop resolver consumes the DoH JSON API;
    // AliDNS exposes that API at /resolve (not /dns-query).
    private const val DEFAULT_DOH = "https://dns.alidns.com/resolve"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean
        get() = port > 0 && runCatching { Echproxy.isRunning() }.getOrDefault(false)

    fun startAsync(context: Context) {
        DiagnosticsLog.event("ECH", "start requested (will delay 500ms); running=$isRunning")
        // 短延迟启动：仅避开 Application 初始化最脆弱的一瞬。
        // firebase-perf 已移除，无需长延迟；首页请求在 MainActivity 创建后立即发出，
        // 若代理起得太晚，首请求会直连真实 IP 被墙拦截。
        mainHandler.postDelayed({
            if (port > 0) return@postDelayed
            executor.execute { startNow(context) }
        }, 500)
    }

    private fun startNow(context: Context) {
        try {
            val chosenPort = ServerSocket(0).use { it.localPort }
            val cachePath = File(context.filesDir, "ech-public-config.json").absolutePath
            runCatching {
                Echproxy.start(
                    "127.0.0.1:$chosenPort",
                    DEFAULT_DOH,
                    cachePath,
                    false,
                )
            }.onFailure { throwable ->
                DiagnosticsLog.event("ECH", "native start failed; ECH disabled", throwable)
                return
            }
            port = chosenPort
            HProxySelector.rebuildNetwork()
            val message = "ECH proxy listening on 127.0.0.1:$chosenPort; ${status()}"
            DiagnosticsLog.event("ECH", message)
            Log.i(TAG, message)
        } catch (e: Throwable) {
            port = -1
            DiagnosticsLog.event("ECH", "ECH proxy start failed; keeping normal network path", e)
            Log.e(TAG, "ECH proxy start failed; keeping normal network path", e)
        }
    }

    fun stopAsync() {
        executor.execute {
            runCatching { Echproxy.stop() }
                .onFailure { Log.w(TAG, "ECH proxy stop failed", it) }
            port = -1
            HProxySelector.rebuildNetwork()
        }
    }

    fun diagnostics(): String = runCatching { Echproxy.diagnostics() }
        .getOrElse { "diagnostics unavailable: ${it.message}" }

    fun status(): String = runCatching { Echproxy.lastStatus() }
        .getOrElse { "status unavailable: ${it.message}" }
}
