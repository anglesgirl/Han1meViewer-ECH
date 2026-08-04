package com.yenaly.han1meviewer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.FILE_PROVIDER_AUTHORITY
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, bounded, on-disk diagnostics buffer. It is intentionally independent
 * from the UI so a broken home page never prevents exporting evidence. */
object DiagnosticsLog {
    private const val TAG = "Diagnostics"
    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()
    private lateinit var file: File
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        synchronized(this) {
            if (::file.isInitialized) return
            file = File(context.filesDir, "diagnostics.log")
            previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                event("CRASH", "uncaught on ${thread.name}: ${error.javaClass.name}: ${error.message}\n${stackTrace(error)}")
                previousCrashHandler?.uncaughtException(thread, error)
            }
        }
        event("APP", "diagnostics initialized; version=${BuildConfig.VERSION_NAME}")
    }

    fun event(area: String, message: String, error: Throwable? = null) {
        if (!::file.isInitialized) return
        val line = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append(" [").append(area).append("] ").append(message)
            if (error != null) append("\n").append(stackTrace(error))
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() + line.toByteArray().size > MAX_BYTES) {
                    val old = file.readText()
                    file.writeText("--- older diagnostics trimmed ---\n" + old.takeLast(MAX_BYTES / 2))
                }
                file.appendText(line)
            }.onFailure { Log.e(TAG, "write diagnostics failed", it) }
        }
        Log.i("Diag/$area", message)
    }

    fun sanitizedUrl(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        "${uri.scheme}://${uri.host}${uri.path ?: "/"}"
    }.getOrDefault("invalid-url")

    /** 崩溃时把日志复制到公共 Downloads 目录，应用起不来也能从文件管理器拿到。 */
    fun writeCrashReportToDownloads(context: Context, throwable: Throwable?) {
        runCatching {
            val name = "Han1meViewer-crash-${System.currentTimeMillis()}.txt"
            val payload = buildString {
                appendLine("Han1meViewer crash report")
                appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("version=${BuildConfig.VERSION_NAME}; sdk=${Build.VERSION.SDK_INT}")
                appendLine("baseUrl=${sanitizedUrl(Preferences.baseUrl)}")
                if (throwable != null) {
                    appendLine("--- crash stack ---")
                    appendLine(stackTrace(throwable))
                }
                appendLine("--- persistent event log ---")
                append(if (::file.isInitialized && file.exists()) file.readText() else "(no events)\n")
                appendLine("--- ECH proxy diagnostics ---")
                appendLine(runCatching { EchProxyManager.diagnostics() }.getOrDefault("unavailable"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                java.io.File(dir, name).writeText(payload)
            }
            Log.i(TAG, "crash report written to Downloads: $name")
        }.onFailure { Log.e(TAG, "write crash report failed", it) }
    }

    fun export(context: Context) {
        event("EXPORT", "user requested diagnostic export")
        val out = File(context.cacheDir, "Han1meViewer-diagnostics-${System.currentTimeMillis()}.txt")
        val header = buildString {
            appendLine("Han1meViewer ECH diagnostics")
            appendLine("version=${BuildConfig.VERSION_NAME}; sdk=${Build.VERSION.SDK_INT}")
            appendLine("baseUrl=${sanitizedUrl(Preferences.baseUrl)}")
            appendLine("--- persistent event log ---")
        }
        runCatching {
            out.writeText(
                header +
                    (if (file.exists()) file.readText() else "(no events yet)\n") +
                    "\n--- ECH proxy diagnostics ---\n" + EchProxyManager.diagnostics()
            )
        }.onFailure { event("EXPORT", "failed", it); return }
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, out)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出 Han1meViewer 诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stackTrace(error: Throwable): String = StringWriter().also { writer ->
        error.printStackTrace(PrintWriter(writer))
    }.toString()
}
