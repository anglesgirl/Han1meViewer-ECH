package com.yenaly.han1meviewer.logic.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream

/**
 * WebView 的 ECH 拦截：把站点的子资源请求（HTML/JS/CSS/图片等）通过
 * OkHttp + EchInterceptor 走本地 ECH 代理（隐藏 SNI），再返回给 WebView。
 *
 * WebView 自身的网络栈走系统代理 CONNECT 隧道，无法隐藏 SNI，封锁站点
 * （如 javchu.com）会被 GFW 重置。这里在 shouldInterceptRequest 拦截，
 * 让站点流量走与 OkHttp 相同的 ECH 路径。
 */
object EchWebViewClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(EchInterceptor())
            .build()
    }

    /**
     * 拦截站点请求走 ECH。非站点域名或 ECH 未开启返回 null（走 WebView 默认）。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (EchProxyManager.port <= 0) return null
        val url = request.url ?: return null
        // 仅站点域名（拦截器内部也判断，这里提前过滤避免额外开销）
        if (!isSiteHost(url.host)) return null

        return try {
            val okRequest = okhttp3.Request.Builder()
                .url(url.toString())
                .method(request.method ?: "GET", null)
                .build()
            val resp = client.newCall(okRequest).execute()

            // 重定向交给 WebView 处理
            if (resp.isRedirect || resp.code >= 400 && resp.code != 403) {
                resp.close()
                return null
            }

            val body = resp.body?.bytes() ?: ByteArray(0)
            val contentType = resp.header("Content-Type") ?: "text/html"
            val encoding = resp.header("Content-Encoding")

            WebResourceResponse(
                contentType,
                encoding,
                ByteArrayInputStream(body),
            ).apply {
                resp.headers.forEach { (name, value) ->
                    if (!name.equals("Content-Type", true) &&
                        !name.equals("Content-Encoding", true) &&
                        !name.equals("Content-Length", true)
                    ) {
                        responseHeaders = (responseHeaders ?: emptyMap()) + (name to value)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 站点域名判断：与当前站点配置一致。 */
    private fun isSiteHost(host: String): Boolean {
        val baseHost = runCatching { Preferences.baseUrl.toHttpUrl().host }.getOrNull() ?: return false
        return host == baseHost || host.endsWith("." + baseHost)
    }
}
