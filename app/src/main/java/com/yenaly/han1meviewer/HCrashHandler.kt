package com.yenaly.han1meviewer

import com.yenaly.yenaly_libs.ActivityManager
import com.yenaly.han1meviewer.util.DiagnosticsLog

object HCrashHandler : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        DiagnosticsLog.event("CRASH", "HCrashHandler on ${t.name}", e)
        // 崩溃上报（PostHog 统计；崩溃场景也能远程看到）
        PostHogManager.track("app_crash", mapOf(
            "exception" to (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200),
            "thread" to (t.name ?: ""),
        ))
        // 写崩溃报告到 Downloads 方便直接取文件
        runCatching { com.yenaly.han1meviewer.HanimeApplication.appContext?.let { DiagnosticsLog.writeCrashReportToDownloads(it, e) } }
        e.printStackTrace()
        ActivityManager.restart(killProcess = true)
    }
}