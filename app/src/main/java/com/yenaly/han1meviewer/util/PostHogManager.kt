package com.yenaly.han1meviewer.util

import android.content.Context
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/**
 * Han1meViewer 匿名统计 (PostHog Android SDK)。
 *
 * 与 CO3 共用同一 PostHog 项目（免费版仅一个项目），key 相同，
 * 用 app 属性（APP_NAME）区分两个 App 的数据。
 *
 * 关键修复（2026-08-06）：项目 OkHttp 从 5.3.2 降回 4.12.0（与 Retrofit、
 * PostHog SDK 依赖对齐）——之前 5.3.2 与 SDK 期望的 4.x 不兼容，
 * SDK 内部抛 NoSuchMethodError（Error 不是 Exception），导致：
 *   1. 播放/启动时崩溃（debug 也崩，与 R8 无关）
 *   2. track() 里 catch(Exception) 抓不住 Error → 崩溃处理链断掉 → 崩溃日志写不出
 * 现在 track() 全部 catch Throwable，初始化放后台线程，绝不阻塞主线程。
 */
object PostHogManager {

    private const val POSTHOG_KEY = "phc_nK8D285fUri5raFY7RFhztnYGqMukLNR6PfymaUB2R27"
    private const val POSTHOG_HOST = "https://e.anglesya.win"
    private const val APP_NAME = "han1meviewer"

    private var initialized = false

    /** 初始化统计。enabled=false 时跳过。 */
    fun init(context: Context, enabled: Boolean) {
        if (initialized) return
        if (!enabled) return
        // 后台线程初始化：SDK setup 内部有磁盘 IO / 反射，避免阻塞主线程
        Thread {
            try {
                val config = PostHogAndroidConfig(
                    apiKey = POSTHOG_KEY,
                    host = POSTHOG_HOST,
                ).apply {
                    // 只手动埋点，禁用自动捕获
                    captureScreenViews = false
                    captureApplicationLifecycleEvents = false
                    captureDeepLinks = false
                }
                PostHogAndroid.setup(context.applicationContext, config)
                initialized = true
                track("app_launch")
            } catch (t: Throwable) {
                // 静默失败，绝不因统计影响主功能
            }
        }.start()
    }

    /** 发送事件。所有事件自动带 app=han1meviewer 标签。 */
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!initialized) return
        try {
            val fullProps = buildMap<String, Any> {
                put("app", APP_NAME)
                properties.forEach { (k, v) ->
                    val s = v?.toString()
                    if (s != null) {
                        put(k, if (s.length > 200) s.take(200) + "…" else s)
                    } else if (v != null) {
                        put(k, v)
                    }
                }
            }
            // 注意：必须 catch Throwable 而非 Exception —— 之前 OkHttp 版本冲突时
            // SDK 抛 NoSuchMethodError（Error），Exception 抓不住导致崩溃处理链断掉。
            PostHog.capture(event, distinctId = null, properties = fullProps)
        } catch (t: Throwable) {
            // 静默失败
        }
    }

    /** 用户拒绝统计 → optOut 禁用 SDK。 */
    fun disable() {
        if (initialized) {
            try {
                PostHog.optOut()
            } catch (_: Throwable) {
            }
            initialized = false
        }
    }

    /** 检查是否已禁用（供 UI 状态用）。 */
    fun isDisabled(): Boolean = !initialized || runCatching { PostHog.isOptOut() }.getOrDefault(true)
}
