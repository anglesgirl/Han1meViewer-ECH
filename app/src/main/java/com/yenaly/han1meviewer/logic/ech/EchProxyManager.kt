package com.yenaly.han1meviewer.logic.ech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import echproxy.Echproxy
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.util.DiagnosticsLog
import com.yenaly.han1meviewer.util.PostHogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Owns the app-private loopback ECH proxy.
 *
 * 启动策略（种子机制）：
 *  1. 用多种子 IP-DoH（alidns 223.5.5.5 / 360 101.226.4.6 / 腾讯 doh.pub）查
 *     `ech-config.anglesgirl.eu.org` 的 TXT 记录，取 doh/doh2/doh3/ip 配置。
 *     - IP 直连防 DoH 域名被劫持；多种子防单点失效。
 *  2. 立即用本地缓存/默认配置启动代理（不等网络），后台刷新种子配置后热更新
 *     （SetEndpoints），首屏不等网。
 *  3. 所有 JNI 调用 guarded：native 失败只降级为 ECH 不可用，不拖垮 App。
 */
object EchProxyManager {
    private const val TAG = "EchProxy"

    /** 种子配置域名：TXT 记录下发 doh/doh2/doh3/ip。 */
    private const val REMOTE_CONFIG_DOMAIN = "ech-config.anglesgirl.eu.org"

    /** 多种子 IP-DoH（按顺序尝试，全部失败才降级）。只用于 TXT 获取，IP 直连防域名劫持。
     *  2026-08-06：移除 alidns（223.5.5.5/223.6.6.6）——国内频繁超时/抖动，
     *  且疑似与 429 限流相关；保留 360 + DNSPod + 腾讯备用。 */
    private val SEED_DOH_LIST = listOf(
        "https://101.226.4.6/resolve",    // 360（IP 直连）
        "https://120.53.53.53/resolve",   // 腾讯 DNSPod（IP 直连）
        "https://1.12.12.12/resolve",     // 腾讯备用（IP 直连）
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean
        get() = port > 0 && runCatching { Echproxy.isRunning() }.getOrDefault(false)

    private var configCacheFile: File? = null

    /** 启动（非挂起版，供 Application 调用）。 */
    fun startAsync(context: Context) {
        DiagnosticsLog.event("ECH", "start requested (will delay 500ms); running=$isRunning")
        mainHandler.postDelayed({
            if (port > 0) return@postDelayed
            executor.execute { startNow(context) }
        }, 500)
    }

    /** 停止（非挂起版）。 */
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

    private fun startNow(context: Context) {
        try {
            configCacheFile = File(context.filesDir, "ech-remote-config.txt")
            val chosenPort = ServerSocket(0).use { it.localPort }
            val cachePath = File(context.filesDir, "ech-public-config.json").absolutePath

            // 1. 种子只做一件事：从多种子 IP-DoH 查 TXT，拿 doh/doh2/doh3/ip。
            //    成功 → 立即用；写缓存（优选 IP 等）供下次冷启动直接使用。
            val seed = runCatching { fetchRemoteConfig() }.getOrNull()
            val seedDoh = seed?.let {
                listOfNotNull(it.doh, it.doh2, it.doh3).distinct().joinToString(",")
            }?.takeIf { it.isNotBlank() }
            val seedIp = seed?.ip?.takeIf { it.isNotBlank() }
            val seedOverride = seed?.override?.takeIf { it.isNotBlank() }
            if (seedDoh != null || seedIp != null || seedOverride != null) {
                saveConfigCache(seedDoh, seedIp, seedOverride)
                DiagnosticsLog.event("ECH", "seed hit: doh=$seedDoh, ip=$seedIp, override=$seedOverride")
            }

            // 2. 种子失败 → 用上次缓存的优选 IP / DoH（不碰 alidns 等污染源）。
            val cached = loadConfigCache()
            val dohArg = seedDoh ?: cached?.first
            val ipArg = seedIp ?: cached?.second ?: ""
            val overrideArg = seedOverride ?: cached?.third ?: ""

            // 3. 都没有 → 断网（不启动 ECH），提示用户重启 App。
            if (dohArg.isNullOrBlank()) {
                DiagnosticsLog.event(
                    "ECH",
                    "no seed config and no cached DoH; ECH disabled (restart app to retry)"
                )
                Log.w(TAG, "no seed config and no cached DoH; ECH disabled (restart app to retry)")
                PostHogManager.track("ech_proxy_start", mapOf("ok" to false, "reason" to "no_seed_config"))
                port = -1
                return
            }
            DiagnosticsLog.event("ECH", "starting on 127.0.0.1:$chosenPort (doh=$dohArg, ip=$ipArg)")

            runCatching {
                Echproxy.start(
                    "127.0.0.1:$chosenPort",
                    dohArg,
                    cachePath,
                    false,
                )
            }.onFailure { throwable ->
                DiagnosticsLog.event("ECH", "native start failed; ECH disabled", throwable)
                PostHogManager.track("ech_proxy_start", mapOf(
                    "ok" to false,
                    "reason" to "native_failed",
                    "error" to (throwable.message ?: "").take(120),
                ))
                return
            }
            port = chosenPort
            HProxySelector.rebuildNetwork()
            val message = "ECH proxy listening on 127.0.0.1:$chosenPort; ${status()}"
            DiagnosticsLog.event("ECH", message)
            Log.i(TAG, message)
            PostHogManager.track("ech_proxy_start", mapOf(
                "ok" to true,
                "seed" to (seedDoh != null || seedIp != null),
                "port" to chosenPort,
            ))

            // 启动后立即下发 per-host override（getchu 等被单 IP 掐的域名走指定 IP）。
            if (overrideArg.isNotBlank()) {
                runCatching { Echproxy.setOverrides(overrideArg) }
                    .onSuccess {
                        DiagnosticsLog.event("ECH", "overrides applied: $overrideArg")
                    }
                    .onFailure { e ->
                        DiagnosticsLog.event("ECH", "overrides apply failed: ${e.message}")
                    }
            }

            // 4. 后台再刷一次种子配置（不阻塞启动），若变化则 SetEndpoints 热更新。
            scope.launch { refreshRemoteConfig(dohArg, ipArg) }
        } catch (e: Throwable) {
            port = -1
            DiagnosticsLog.event("ECH", "ECH proxy start failed; keeping normal network path", e)
            Log.e(TAG, "ECH proxy start failed; keeping normal network path", e)
            PostHogManager.track("ech_proxy_start", mapOf(
                "ok" to false,
                "reason" to "exception",
                "error" to (e.message ?: "").take(120),
            ))
        }
    }

    /**
     * 后台刷新远端配置（不阻塞启动）。
     * 成功：写缓存文件，若与当前端点不同则 SetEndpoints 热更新（无需重启代理）。
     * 失败：沿用缓存/当前配置，仅记录。
     */
    private suspend fun refreshRemoteConfig(currentDoh: String, currentIp: String) {
        runCatching { fetchRemoteConfig() }
            .onSuccess { cfg ->
                val list = listOfNotNull(cfg.doh, cfg.doh2, cfg.doh3).distinct()
                val newDoh = if (list.isNotEmpty()) list.joinToString(",") else null
                val newIp = cfg.ip?.takeIf { it.isNotBlank() }
                val newOverride = cfg.override?.takeIf { it.isNotBlank() }
                DiagnosticsLog.event("ECH", "remote config: doh=$newDoh, ip=$newIp, override=$newOverride")
                saveConfigCache(newDoh, newIp, newOverride)
                if (newDoh != null || newIp != null) {
                    val finalDoh = newDoh ?: currentDoh
                    val finalIp = newIp ?: ""
                    if (finalDoh != currentDoh || finalIp != currentIp) {
                        runCatching { Echproxy.setEndpoints(finalDoh, finalIp) }
                            .onSuccess {
                                DiagnosticsLog.event("ECH", "endpoints hot-updated (doh=$finalDoh, ip=$finalIp)")
                            }
                            .onFailure { e ->
                                DiagnosticsLog.event("ECH", "endpoints hot-update failed: ${e.message}")
                            }
                    }
                }
                // per-host override 独立热更新（无需重启代理）。
                if (newOverride != null) {
                    runCatching { Echproxy.setOverrides(newOverride) }
                        .onSuccess {
                            DiagnosticsLog.event("ECH", "overrides hot-updated: $newOverride")
                        }
                        .onFailure { e ->
                            DiagnosticsLog.event("ECH", "overrides hot-update failed: ${e.message}")
                        }
                }
            }
            .onFailure { e ->
                DiagnosticsLog.event("ECH", "remote config refresh failed (using cached/current): ${e.message}")
            }
    }

    // --- 种子 TXT 查询与缓存 ---

    /**
     * 从多种子 IP-DoH 查 REMOTE_CONFIG_DOMAIN 的 TXT 记录，解析 doh/doh2/doh3/ip。
     * 依次尝试种子列表，全部失败抛异常。阻塞调用（在 IO 线程执行）。
     */
    private fun fetchRemoteConfig(): RemoteEchConfig {
        var lastError: Exception? = null
        for (seed in SEED_DOH_LIST) {
            try {
                val txt = dohQueryTxt(seed, REMOTE_CONFIG_DOMAIN)
                val cfg = parseRemoteConfig(txt)
                if (cfg.doh != null || cfg.ip != null) {
                    DiagnosticsLog.event("ECH", "seed config via $seed")
                    return cfg
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("no seed DoH endpoint available")
    }

    /** 对单个种子 DoH 发起 JSON TXT 查询（IP 直连，防域名劫持）。 */
    private fun dohQueryTxt(doh: String, name: String): String {
        val u = URL("$doh?name=$name&type=TXT")
        // 必须显式 NO_PROXY：ECH 开启时系统代理指向本机代理 → 递归。
        val conn = u.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("accept", "application/dns-json")
        conn.setRequestProperty("User-Agent", "Han1meViewer-ECH")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val code = conn.responseCode
        if (code != 200) throw Exception("seed DoH HTTP $code via $doh")
        val body = conn.inputStream.use { it.readBytes() }.decodeToString()
        return parseTxtJson(body)
    }

    /** 解析 DoH JSON 响应，返回 TXT 记录（每条一行）。去掉转义、剥离引号包裹。 */
    private fun parseTxtJson(json: String): String {
        val lines = mutableListOf<String>()
        val re = Regex("\"data\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (m in re.findAll(json)) {
            val raw = m.groupValues[1]
            val cleaned = raw.replace("\\\"", "\"").replace("\\\\", "\\")
            lines.add(cleaned)
        }
        if (lines.isEmpty()) throw Exception("no TXT records in DoH response")
        return lines.joinToString("\n")
    }

    /** 解析种子 TXT 内容（`;` 分隔的 key=value）。 */
    private fun parseRemoteConfig(txt: String): RemoteEchConfig {
        val cfg = RemoteEchConfig()
        txt.split("\n").forEach { line ->
            line.split(";").forEach { part ->
                val idx = part.indexOf("=")
                if (idx > 0) {
                    val key = part.substring(0, idx).trim().trim('"').lowercase()
                    val value = part.substring(idx + 1).trim().trim('"')
                    when (key) {
                        "doh" -> cfg.doh = value
                        "doh2" -> cfg.doh2 = value
                        "doh3" -> cfg.doh3 = value
                        "ip", "ips" -> cfg.ip = value
                        "override" -> cfg.override = value
                    }
                }
            }
        }
        return cfg
    }

    /** 读取上次成功的种子配置缓存（三行：doh 逗号串 / ip 串 / override 串）。 */
    private fun loadConfigCache(): Triple<String, String, String>? {
        val f = configCacheFile ?: return null
        return runCatching {
            val lines = f.readLines()
            if (lines.isEmpty() || lines[0].isBlank()) null
            else Triple(lines[0], lines.getOrNull(1) ?: "", lines.getOrNull(2) ?: "")
        }.getOrNull()
    }

    private fun saveConfigCache(doh: String?, ip: String?, override: String? = null) {
        val f = configCacheFile ?: return
        val text = "${doh.orEmpty()}\n${ip.orEmpty()}\n${override.orEmpty()}"
        runCatching { f.writeText(text) }
    }

    private data class RemoteEchConfig(
        var doh: String? = null,
        var doh2: String? = null,
        var doh3: String? = null,
        var ip: String? = null,
        var override: String? = null,
    )
}