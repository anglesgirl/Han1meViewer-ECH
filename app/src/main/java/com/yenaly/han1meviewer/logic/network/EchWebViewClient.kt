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
        val urlString = url.toString()
        // 仅站点域名（拦截器内部也判断，这里提前过滤避免额外开销）
        val host = url.host ?: return null
        if (!isSiteHost(host)) return null

        return try {
            val okRequest = okhttp3.Request.Builder()
                .url(urlString)
                .method(request.method ?: "GET", null)
                .build()
            val resp = client.newCall(okRequest).execute()

            // OkHttp 默认 followRedirects=true，会自动跟随 3xx 拿到最终响应，
            // 全程走 ECH 代理。绝不能把重定向 return null 丢回 WebView——
            // WebView 自己发请求走 CONNECT 隧道(无法隐藏 SNI)，javchu.com 被
            // GFW 重置 → 页面一直转圈等 onPageFinished。
            // 仅 4xx/5xx 错误(非 403，CF 认证用)放回 null 交给 WebView 处理。
            if (resp.code >= 400 && resp.code != 403) {
                resp.close()
                return null
            }

            val body = resp.body?.bytes() ?: ByteArray(0)
            val rawContentType = resp.header("Content-Type") ?: "text/html"
            // WebResourceResponse 需要 MIME 与 charset 分开：
            // 第一个参数 = 纯 MIME（如 "text/html"），第二个 = 字符编码（如 "utf-8"）。
            // 直接传 "text/html; charset=utf-8" 当 MIME、或把 gzip 当编码，都会导致
            // WebView 显示纯代码/乱码而不渲染。
            val mime = rawContentType.substringBefore(";").trim()
            val charset = Regex("charset=([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .find(rawContentType)?.groupValues?.get(1)?.trim() ?: "utf-8"

            // 登录关键：WebView 对 shouldInterceptRequest 返回的响应不会自动存 cookie。
            // 必须手动把 Set-Cookie 同步进 CookieManager，否则登录页的 session
            // 丢失，登录跳转时 getCookie() 为空 → 登录失败。
            val setCookies = resp.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                val cookieManager = android.webkit.CookieManager.getInstance()
                setCookies.forEach { raw ->
                    runCatching { cookieManager.setCookie(urlString, raw) }
                }
                runCatching { cookieManager.flush() }
            }

            WebResourceResponse(
                mime,
                charset,
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
