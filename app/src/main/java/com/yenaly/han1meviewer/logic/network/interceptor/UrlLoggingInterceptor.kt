package com.yenaly.han1meviewer.logic.network.interceptor

import com.yenaly.han1meviewer.util.DiagnosticsLog
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class UrlLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        val safeUrl = DiagnosticsLog.sanitizedUrl(decodedUrl)
        val start = System.nanoTime()
        DiagnosticsLog.event("HTTP", "--> ${request.method} $safeUrl")
        return try {
            chain.proceed(request).also { response ->
                val ms = (System.nanoTime() - start) / 1_000_000
                DiagnosticsLog.event("HTTP", "<-- ${response.code} ${ms}ms $safeUrl")
            }
        } catch (error: Throwable) {
            val ms = (System.nanoTime() - start) / 1_000_000
            DiagnosticsLog.event("HTTP", "<-- FAILED ${ms}ms $safeUrl: ${error.javaClass.simpleName}: ${error.message}", error)
            throw error
        }
    }
}