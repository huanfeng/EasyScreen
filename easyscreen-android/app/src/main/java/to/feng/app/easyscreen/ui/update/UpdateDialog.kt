package to.feng.app.easyscreen.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.feng.app.easyscreen.update.UpdateKind
import to.feng.app.easyscreen.update.UpdateState

/**
 * 老人友好更新对话框。根据 [state] 渲染：发现新版 / 下载中 / 待安装。
 * 强制更新（FORCED）时不可取消、无「稍后」。
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (info, kind) = when (state) {
        is UpdateState.Available -> state.info to state.kind
        is UpdateState.Downloading -> state.info to state.kind
        is UpdateState.ReadyToInstall -> state.info to state.kind
        else -> return
    }
    val forced = kind == UpdateKind.FORCED
    // 非强制时：发现新版与下载完成都允许「稍后」；下载中不可中断
    val dismissable = !forced && (state is UpdateState.Available || state is UpdateState.ReadyToInstall)

    AlertDialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        title = {
            Text(
                text = "发现新版本 ${info.versionName}",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info.releaseNotes.isNotBlank()) {
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodyLarge)
                }
                when (state) {
                    is UpdateState.Downloading -> {
                        Text("正在下载… ${state.progress}%", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                    is UpdateState.ReadyToInstall ->
                        Text("下载完成，点击安装。", style = MaterialTheme.typography.bodyMedium)
                    else ->
                        if (forced) Text("此版本需要更新后才能继续使用。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available ->
                    Button(onClick = onUpdate) { Text("立即更新", style = MaterialTheme.typography.titleMedium) }
                is UpdateState.Downloading ->
                    Button(onClick = {}, enabled = false) { Text("下载中…") }
                is UpdateState.ReadyToInstall ->
                    Button(onClick = onInstall) { Text("立即安装", style = MaterialTheme.typography.titleMedium) }
                else -> {}
            }
        },
        dismissButton = {
            if (dismissable) {
                TextButton(onClick = onSkip) { Text("稍后") }
            }
        },
    )
}
