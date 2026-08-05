package com.yenaly.han1meviewer.util

import android.content.Context
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.PostHog

/**
 * Han1meViewer 匿名统计 (PostHog KMP 6.29.0)。
 *
 * 与 CO3 共用同一 PostHog 项目（免费版仅一个项目），key 相同，
 * 用 app 属性（APP_NAME）区分两个 App 的数据。
 */
object PostHogManager {

    private const val POSTHOG_KEY = "phc_nK8D285fUri5raFY7RFhztnYGqMukLNR6PfymaUB2R27"
    private const val POSTHOG_HOST = "https://e.anglesya.win"
    private const val APP_NAME = "han1meviewer"

    private var initialized = false

    /** 初始化统计。enabled=false 时跳过。 */
    fun init(context: Context, enabled: Boolean) {
        if (!enabled || initialized) return
        try {
            val config = PostHogAndroidConfig(
                apiKey = POSTHOG_KEY,
                host = POSTHOG_HOST,
            ).apply {
                // 可选：禁用自动捕获（页面浏览、点击等），我们只手动埋点
                captureScreenViews = false
                captureApplicationLifecycleEvents = false
                captureDeepLinks = false
            }
            PostHogAndroid.setup(context, config)
            initialized = true
            track("app_launch")
        } catch (e: Exception) {
            // 静默失败，不影响主功能
        }
    }

    /** 发送事件。所有事件自动带 app=han1meviewer 标签。 */
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!initialized) return
        try {
            val fullProps = buildMap {
                put("app", APP_NAME)
                properties.forEach { (k, v) ->
                    val s = v?.toString()
                    put(k, if (s != null && s.length > 200) s.take(200) + "…" else v)
                }
            }
            PostHog.capture(event, fullProps)
        } catch (e: Exception) {
            // 静默失败
        }
    }

    /** 用户拒绝统计 → optOut 禁用 SDK。 */
    fun disable() {
        if (initialized) {
            PostHog.optOut()
            initialized = false
        }
    }

    /** 检查是否已禁用（供 UI 状态用）。 */
    fun isDisabled(): Boolean = !initialized || PostHog.isOptOut()
}