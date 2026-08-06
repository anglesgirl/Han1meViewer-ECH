package com.yenaly.han1meviewer.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.util.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志查看页：显示 DiagnosticsLog 的完整内容（含播放/视频链接/ECH 事件）。
 * 由设置页"关于"连点三下开启入口后进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("加载中…") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        logText = withContext(Dispatchers.IO) { DiagnosticsLog.readLog() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refreshKey++ } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    Text(
                        text = "导出",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable(onClick = onExport),
                    )
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val lines = logText.lines()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
