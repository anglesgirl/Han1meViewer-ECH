package com.yenaly.han1meviewer.logic.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream

/**
 * WebView 的 ECH 拦截：把所有子资源请求（HTML/JS/CSS/图片/接口）通过
 * OkHttp + EchInterceptor 走本地 ECH 代理（隐藏 SNI），再返回给 WebView。
 *
 * 注意：**所有域名都走 ECH 代理**（不去按站点/家族过滤）——登录/注册/资源
 * 可能落在任意 CDN 域名（如 twimg 类、Cloudflare 边缘），走 WebView 默认的
 * CONNECT 隧道无法隐藏 SNI，会被 GFW 重置（ERR_CONNECTION_RESET）。只有
 * ECH 代理能统一隐藏 SNI。排除本机/内网以免递归。
 */
object EchWebViewClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(EchInterceptor())
            .build()
    }

    /**
     * 拦截请求走 ECH。ECH 未开启或内网/本机返回 null（走 WebView 默认）。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (EchProxyManager.port <= 0) return null
        val url = request.url ?: return null
        val urlString = url.toString()
        val host = url.host ?: return null
        // 排除内网/本机（EchInterceptor 内部也排除，这里提前省开销）
        if (host == "127.0.0.1" || host == "localhost" || host.endsWith(".local")) {
            return null
        }

        return try {
            val okRequest = okhttp3.Request.Builder()
                .url(urlString)
                .method(request.method ?: "GET", null)
                .build()
            val resp = client.newCall(okRequest).execute()

            // OkHttp 默认 followRedirects=true，会自动跟随 3xx 拿到最终响应，
            // 全程走 ECH 代理。绝不能把重定向 return null 丢回 WebView——
            // WebView 自己发请求走 CONNECT 隧道(无法隐藏 SNI)，被 GFW 重置。
            // 仅 4xx/5xx 错误(非 403，Cloudflare 认证用)放回 null 交 WebView。
            if (resp.code >= 400 && resp.code != 403) {
                resp.close()
                return null
            }

            val body = resp.body?.bytes() ?: ByteArray(0)
            val rawContentType = resp.header("Content-Type") ?: "text/html"
            // WebResourceResponse 需要 MIME 与 charset 分开，否则纯代码不渲染。
            val mime = rawContentType.substringBefore(";").trim()
            val charset = Regex("charset=([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .find(rawContentType)?.groupValues?.get(1)?.trim() ?: "utf-8"

            // WebView 对 shouldInterceptRequest 返回的响应不会自动存 cookie。
            // 必须手动把 Set-Cookie 同步进 CookieManager，否则 session 丢失。
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
}