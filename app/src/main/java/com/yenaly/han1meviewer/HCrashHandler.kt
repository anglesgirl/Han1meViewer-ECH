package com.yenaly.han1meviewer

import com.yenaly.yenaly_libs.ActivityManager
import com.yenaly.han1meviewer.util.DiagnosticsLog

object HCrashHandler : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        DiagnosticsLog.event("CRASH", "HCrashHandler on ${t.name}", e)
        // 尽量把崩溃报告写到公共 Downloads 目录，应用起不来也能取证。
        runCatching { com.yenaly.han1meviewer.HanimeApplication.appContext?.let { DiagnosticsLog.writeCrashReportToDownloads(it, e) } }
        e.printStackTrace()
        ActivityManager.restart(killProcess = true)
    }
}