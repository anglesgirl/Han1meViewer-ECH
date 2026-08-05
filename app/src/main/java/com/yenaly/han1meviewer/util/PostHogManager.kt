package com.yenaly.han1meviewer.util

import android.content.Context
import android.os.Build
import com.yenaly.han1meviewer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Han1meViewer 匿名统计 —— 轻量 HTTP 客户端（无 SDK 依赖）。
 *
 * 之前用 posthog-android SDK（KMP 6.29.0 / 3.58.0），但 R8 混淆下
 * SDK 内部反射/序列化会抛 Error（NoClassDefFoundError 等），
 * debug 正常、release 播放崩溃，且崩溃处理链路被卡住写不出日志。
 *
 * 最终方案：完全弃用 SDK，直接用 OkHttp/HttpURLConnection 调
 * PostHog /batch/ API（与 CO3 已验证的 HTTP 直连方案一致）。
 * 只需要统计活跃用户数，不需要 SDK 的高级功能（session replay /
 * feature flags）。
 *
 * 设计原则（同 CO3）：
 * 1. 超长属性截断（max 200 字符）；
 * 2. 提供开关，允许用户关闭数据采集（analyticsEnabled）；
 * 3. 使用自有域名代理上报（e.anglesya.win），大陆可直连；
 * 4. 断网时事件缓存在内存队列，网络恢复后批量补发；
 * 5. 全程 fire-and-forget，永不阻塞 UI，永不抛异常；
 * 6. 与 CO3 共用同一 PostHog 项目（免费版仅一个项目），key 相同，
 *    用 app 属性（APP_NAME）区分两个 App 的数据。
 */
object PostHogManager {

    private const val POSTHOG_KEY = "phc_nK8D285fUri5raFY7RFhztnYGqMukLNR6PfymaUB2R27"
    private const val POSTHOG_HOST = "https://e.anglesya.win"
    private const val APP_NAME = "han1meviewer"

    private const val QUEUE_MAX = 50           // 最多缓存 50 条，防止内存膨胀
    private const val FLUSH_INTERVAL_MS = 30_000L // 每 30 秒尝试批量上报
    private const val MAX_PROP_LENGTH = 200

    private var initialized = false
    private var analyticsEnabled = false
    private var distinctId: String? = null
    private val eventQueue = ConcurrentLinkedQueue<JSONObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    /** 初始化统计。enabled=false 时跳过。 */
    fun init(context: Context, enabled: Boolean) {
        if (initialized) return
        analyticsEnabled = enabled
        initialized = true
        if (!enabled) return
        distinctId = loadDistinctId(context)
        flushJob = scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushQueue()
            }
        }
        track("app_launch")
    }

    /** 发送事件。所有事件自动带 app=han1meviewer 标签。 */
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!initialized || !analyticsEnabled) return
        val id = distinctId ?: return
        try {
            val props = JSONObject()
            props.put("app", APP_NAME)
            props.put("app_version", BuildConfig.VERSION_NAME)
            props.put("os", "Android")
            props.put("os_version", Build.VERSION.RELEASE)
            properties.forEach { (k, v) ->
                val s = v?.toString()
                if (s != null) {
                    props.put(k, if (s.length > MAX_PROP_LENGTH) s.take(MAX_PROP_LENGTH) + "…" else s)
                }
            }
            val item = JSONObject()
                .put("event", event)
                .put("properties", props)
                .put("timestamp", System.currentTimeMillis())
                .put("distinct_id", id)
            eventQueue.add(item)
            // 队列满时立即尝试 flush，防止丢事件
            if (eventQueue.size >= QUEUE_MAX) flushQueue()
        } catch (_: Exception) {
            // 静默失败
        }
    }

    /** 用户拒绝统计 → 清空队列并停止上报。 */
    fun disable() {
        if (!initialized) return
        analyticsEnabled = false
        eventQueue.clear()
        flushJob?.cancel()
        flushJob = null
    }

    /** 检查是否已禁用（供 UI 状态用）。 */
    fun isDisabled(): Boolean = !initialized || !analyticsEnabled

    // --- 内部实现 ---

    private fun loadDistinctId(context: Context): String {
        val prefs = context.getSharedPreferences("analytics", Context.MODE_PRIVATE)
        val existing = prefs.getString("distinct_id", null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("distinct_id", id).apply()
        return id
    }

    private fun flushQueue() {
        if (eventQueue.isEmpty()) return
        val id = distinctId ?: return
        // 取出当前队列快照（并发安全）
        val batch = ArrayList<JSONObject>(eventQueue.size)
        while (true) {
            val item = eventQueue.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        try {
            val payload = JSONObject()
                .put("api_key", POSTHOG_KEY)
                .put("historical_migration", false)
                .put("batch", JSONArray(batch))

            withContext(Dispatchers.IO) {
                val conn = URL("$POSTHOG_HOST/batch/").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        // 上报失败：把事件放回队列尾部（最多保留 QUEUE_MAX 条）
                        eventQueue.addAll(batch.take(QUEUE_MAX))
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (_: Exception) {
            // 断网/超时：事件放回队列尾部，下次 flush 再试
            eventQueue.addAll(batch.take(QUEUE_MAX))
        }
    }
}
