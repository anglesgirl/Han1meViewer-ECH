package com.yenaly.han1meviewer.util

import android.content.Context
import com.posthog.android.PostHog
import com.posthog.android.PostHogConfig

/**
 * Han1meViewer 匿名统计 (PostHog)。
 *
 * PostHog 免费版仅一个项目，与 CO3 共用同一项目（同 key / 同 host），
 * 但所有事件自动附带 `app: "han1meviewer"` 属性，后台按该属性过滤即可
 * 单独查看本 App 的数据，与 CO3 互不干扰。
 *
 * 设计原则（同 CO3）：
 *  - 只报手动埋的事件，不自动采集
 *  - 超长属性截断
 *  - 用户可在设置关统计数据采集
 *  - 永不阻塞 UI，异常静默失败
 */
object PostHogManager {

    // 与 CO3 共用同一 PostHog 项目（免费版仅一个项目）。
    private const val POSTHOG_KEY = "phc_nK8D285fUri5raFY7RFhztnYGqMukLNR6PfymaUB2R27"
    private const val POSTHOG_HOST = "https://e.anglesya.win"
    private const val APP_NAME = "han1meviewer"

    private var initialized = false

    /** 初始化统计。enabled=false 时跳过。 */
    fun init(context: Context, enabled: Boolean) {
        if (!enabled || initialized) return
        try {
            PostHog.setup(context, PostHogConfig(
                apiKey = POSTHOG_KEY,
                host = POSTHOG_HOST,
            ).also {
                it.autocapture = false
                it.captureApplicationLifecycleEvents = true
                it.captureScreenViews = false
            })
            initialized = true
            track("app_launch")
        } catch (e: Exception) {
            // 统计失败不影响 App
        }
    }

    /** 上报事件。未初始化时 no-op。自动附带 app 标识；属性值截断到 200 字符。 */
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

    /** 关闭统计（用户在设置里关闭时） */
    fun disable() {
        if (initialized) {
            try { PostHog.optOut() } catch (e: Exception) {}
            initialized = false
        }
    }
}