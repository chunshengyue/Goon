package com.example.goon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.goon.core.WebPreview
import com.example.goon.core.Workspace

@Composable
fun WebMiniAppRuntime(workspace: Workspace, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val projectId = workspace.activeProjectId() ?: return
    val project = remember(projectId) { runCatching { workspace.webStore().load(projectId) }.getOrNull() }
    var failure by remember(projectId) { mutableStateOf<String?>(null) }
    var loading by remember(projectId) { mutableStateOf(true) }
    LaunchedEffect(projectId, loading) {
        if (loading) { kotlinx.coroutines.delay(15000); if (loading) failure = "加载超时，请返回后重新打开。" }
    }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(project?.manifest?.name ?: "小程序不可用", modifier = Modifier.weight(1f).padding(12.dp))
        }
        if (project == null || failure != null) {
            Text(failure ?: "项目文件无法读取，请恢复历史版本。", Modifier.padding(20.dp))
        } else {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val preview = remember(project.revision) { WebPreview(project) }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    preview.create(context, onReady = { loading = false }, onFailure = { failure = it; loading = false })
                        .apply { loadUrl(preview.entryUrl) }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )
        }
    }
}
