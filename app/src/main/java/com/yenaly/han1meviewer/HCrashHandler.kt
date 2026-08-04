package com.yenaly.han1meviewer

import com.yenaly.yenaly_libs.ActivityManager
import com.yenaly.han1meviewer.util.DiagnosticsLog

object HCrashHandler : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        DiagnosticsLog.event("CRASH", "HCrashHandler on ${t.name}", e)
        e.printStackTrace()
        ActivityManager.restart(killProcess = true)
    }
}