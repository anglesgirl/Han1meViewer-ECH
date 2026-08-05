package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import com.yenaly.han1meviewer.util.DiagnosticsLog
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import android.os.SystemClock

/**
 * ECH 拦截器：把 **所有** HTTPS 请求改写为
 * http://127.0.0.1:<echPort>/<path> + X-Ech-Target:<host> header，
 * 让 Go ECH 代理统一处理：
 *   - 目标支持 ECH → ECH 握手（隐藏 SNI，绕过封锁，如 javchu.com）
 *   - 目标不支持 ECH → 普通 TLS（DoH 解析 + 直连）
 * 所有流量交给 ECH 代理，不需要用户配置代理或开关。
 *
 * 代理未启动（port<=0）时放行直连，避免全断。
 */
class EchInterceptor : Interceptor {

    /** ECH 未就绪时等待的最长时间。 */
    private companion object {
        const val WAIT_ECH_TIMEOUT_MS = 10_000L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // 等待 ECH 代理就绪（最多 10 秒）：ECH 未启动前不放行直连，
        // 否则首请求直连真实 IP 会被墙拦截。
        var echPort = EchProxyManager.port
        if (echPort <= 0) {
            val deadline = SystemClock.elapsedRealtime() + WAIT_ECH_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                echPort = EchProxyManager.port
                if (echPort > 0) break
                Thread.sleep(50)
            }
        }
        if (echPort <= 0) return chain.proceed(request)

        val originHost = url.host
        // 本地/内网/空 host 不走代理
        if (originHost.isBlank() ||
            originHost == "127.0.0.1" || originHost == "localhost" ||
            originHost.endsWith(".local")
        ) {
            return chain.proceed(request)
        }

        // 改写：http://127.0.0.1:port/path?query + X-Ech-Target: host
        val proxyUrl = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(echPort)
            .encodedPath(url.encodedPath)
            .encodedQuery(url.encodedQuery ?: "")
            .build()

        val builder = request.newBuilder()
            .url(proxyUrl)
            .header("X-Ech-Target", originHost)
            .header("Host", originHost)

        // 手动注入原始域名的 cookie（OkHttp CookieJar 按 127.0.0.1 匹配不到）
        val originCookies = HCookieJar.cookieMap[originHost] ?: emptyList()
        if (originCookies.isNotEmpty()) {
            val cookieHeader = originCookies.joinToString("; ") { "${it.name}=${it.value}" }
            builder.header("Cookie", cookieHeader)
        }

        val proxied = builder.build()
        DiagnosticsLog.event("HTTP", "ECH route ${originHost}${url.encodedPath} -> 127.0.0.1:$echPort")

        val startMs = SystemClock.elapsedRealtime()
        val response = chain.proceed(proxied)
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        DiagnosticsLog.event("HTTP", "ECH route ${originHost}${url.encodedPath} -> ${elapsedMs}ms ${response.code}")

        // 响应里的 Set-Cookie 存回原始域名
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val parsed = setCookies.mapNotNull { raw ->
                runCatching { Cookie.parse(proxyUrl, raw) }.getOrNull()
            }
            if (parsed.isNotEmpty()) {
                val existing = HCookieJar.cookieMap[originHost] ?: mutableListOf()
                existing.addAll(parsed)
                HCookieJar.cookieMap[originHost] = existing
            }
        }
        return response
    }
}
