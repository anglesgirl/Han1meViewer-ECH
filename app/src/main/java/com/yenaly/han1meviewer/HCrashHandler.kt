package com.yenaly.han1meviewer

import com.yenaly.yenaly_libs.ActivityManager
import com.yenaly.han1meviewer.util.DiagnosticsLog
import com.yenaly.han1meviewer.util.PostHogManager

object HCrashHandler : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        DiagnosticsLog.event("CRASH", "HCrashHandler on ${t.name}", e)
        // 崩溃上报（PostHog 统计；崩溃场景也能远程看到）。
        // 注意：R8 混淆后 PostHog 可能抛 Error（NoClassDefFoundError 等），
        // 而 track() 内部只 catch Exception——这里必须再包一层 runCatching
        // 兜住 Error，否则崩溃处理本身会崩掉，崩溃文件永远写不出来。
        runCatching {
            PostHogManager.track("app_crash", mapOf(
                "exception" to (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200),
                "thread" to (t.name ?: ""),
            ))
        }
        // 写崩溃报告到 Downloads 方便直接取文件
        runCatching { com.yenaly.han1meviewer.HanimeApplication.appContext?.let { DiagnosticsLog.writeCrashReportToDownloads(it, e) } }
        e.printStackTrace()
        ActivityManager.restart(killProcess = true)
    }
}