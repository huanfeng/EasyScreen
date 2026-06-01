package to.feng.app.easyscreen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import to.feng.app.easyscreen.BuildConfig
import to.feng.app.easyscreen.data.ServerPrefs
import to.feng.app.easyscreen.ui.update.UpdateDialog
import to.feng.app.easyscreen.update.ApkInstaller
import to.feng.app.easyscreen.update.UpdateController
import to.feng.app.easyscreen.update.UpdateState

@Composable
fun MainScreen(
    onNavigateToHost: (String) -> Unit,
    onNavigateToGuest: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    // 主页只读取设置中已保存的 serverUrl，不在此处再提供编辑入口
    val serverUrl by rememberSaveable { mutableStateOf(ServerPrefs.get(context)) }
    val scope = rememberCoroutineScope()
    val updateController = remember { UpdateController() }
    val updateState by updateController.state.collectAsState()

    // 启动静默检查（每次进入主页触发一次）
    LaunchedEffect(Unit) {
        updateController.check(
            scope = scope,
            context = context,
            wsServerUrl = serverUrl,
            currentCode = BuildConfig.VERSION_CODE,
            silent = true,
        )
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "远程看屏",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "极简 · 一键看屏",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(64.dp))

            // 被控端按钮（父母用）
            Button(
                onClick = { onNavigateToHost(serverUrl) },
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .height(120.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "我要共享屏幕",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "父母用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 控制端按钮（子女用）
            OutlinedButton(
                onClick = { onNavigateToGuest(serverUrl) },
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .height(120.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "我要看别人屏幕",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "子女用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // 右上角设置入口：必须声明在 Column 之后（Z-top），否则会被 Column 的
        // verticalScroll PointerInput 在该区域抢占触摸事件，从设置页返回后表现为难点击。
        // 图标 + 文字整体可点击，并放大触摸区域，方便老人操作。
        TextButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UpdateDialog(
            state = updateState,
            onUpdate = {
                if (ApkInstaller.canInstall(context)) {
                    updateController.startDownload(scope, context, serverUrl)
                } else {
                    ApkInstaller.openInstallPermissionSettings(context)
                }
            },
            onInstall = {
                val s = updateState
                if (s is UpdateState.ReadyToInstall) {
                    if (ApkInstaller.canInstall(context)) ApkInstaller.install(context, s.apk)
                    else ApkInstaller.openInstallPermissionSettings(context)
                }
            },
            onSkip = { updateController.skip(context) },
            onDismiss = { updateController.dismiss() },
        )
    }
}
