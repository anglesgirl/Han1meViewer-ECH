package com.yenaly.han1meviewer.ui.activity

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import com.yenaly.han1meviewer.util.DiagnosticsLog

/** Standalone launcher entry for exporting diagnostics when MainActivity cannot
 * reach the settings screen (for example, a startup/home-page crash loop). */
class DiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLog.event("DIAGNOSTICS", "standalone diagnostics activity opened")
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Han1meViewer 诊断日志\n\n即使主页打不开，也可以从这里导出日志。"
            textSize = 18f
        })
        root.addView(Button(this).apply {
            text = "导出诊断日志"
            setOnClickListener { DiagnosticsLog.export(this@DiagnosticsActivity) }
        })
        root.addView(TextView(this).apply {
            text = "\nECH 状态：${EchProxyManager.status()}"
            textSize = 12f
        })
        setContentView(root)
    }
}
