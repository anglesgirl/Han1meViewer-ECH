package com.yenaly.han1meviewer.logic.ech

import android.content.Context
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
 */
object EchProxyManager {
    private const val TAG = "EchProxy"
    // ech-proxy-go's current desktop resolver consumes the DoH JSON API;
    // AliDNS exposes that API at /resolve (not /dns-query).
    private const val DEFAULT_DOH = "https://dns.alidns.com/resolve"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean
        get() = port > 0 && Echproxy.isRunning()

    fun startAsync(context: Context) {
        DiagnosticsLog.event("ECH", "start requested; running=$isRunning")
        if (port > 0) return
        executor.execute {
            try {
                val chosenPort = ServerSocket(0).use { it.localPort }
                val cachePath = File(context.filesDir, "ech-public-config.json").absolutePath
                Echproxy.start(
                    "127.0.0.1:$chosenPort",
                    DEFAULT_DOH,
                    cachePath,
                    false,
                )
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
