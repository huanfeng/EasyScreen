package to.feng.app.easyscreen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.feng.app.easyscreen.BuildConfig
import to.feng.app.easyscreen.data.HostTokenPrefs
import to.feng.app.easyscreen.data.QualityPrefs
import to.feng.app.easyscreen.data.ServerPrefs

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var serverUrl by rememberSaveable { mutableStateOf(ServerPrefs.get(context)) }
    var qualityId by rememberSaveable { mutableStateOf(QualityPrefs.get(context).id) }

    LaunchedEffect(serverUrl) { ServerPrefs.set(context, serverUrl) }
    LaunchedEffect(qualityId) {
        QualityPrefs.PRESETS.firstOrNull { it.id == qualityId }?.let {
            QualityPrefs.set(context, it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ========== 服务器 ==========
            SectionCard(title = "信令服务器") {
                ServerUrlPicker(
                    current = serverUrl,
                    onChange = { serverUrl = it },
                )
            }

            // ========== 画质 ==========
            SectionCard(title = "画质") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityPrefs.PRESETS.forEach { preset ->
                        val selected = preset.id == qualityId
                        QualityRow(
                            label = preset.label,
                            detail = "${preset.width}×${preset.height}  ${preset.fps}fps  最大 ${preset.maxBitrateBps / 1000}kbps",
                            selected = selected,
                            onClick = { qualityId = preset.id }
                        )
                    }
                }
            }

            // ========== 安全 ==========
            SectionCard(title = "安全") {
                var showResetConfirm by remember { mutableStateOf(false) }
                var resetDone by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("重置连接码", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (resetDone) "✓ 已重置，下次共享会分配新号码"
                            else "清除设备身份，下次共享会分配全新连接码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { showResetConfirm = true }) {
                        Text("重置")
                    }
                }
                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("确认重置连接码？") },
                        text = {
                            Text("旧的 6 位连接码将立即失效，已经知道旧号码的对方将无法重新连接。下次开始共享会获得一个全新的号码。")
                        },
                        confirmButton = {
                            Button(onClick = {
                                HostTokenPrefs.reset(context)
                                resetDone = true
                                showResetConfirm = false
                            }) { Text("确认重置") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showResetConfirm = false }) { Text("取消") }
                        }
                    )
                }
            }

            // ========== 关于 ==========
            SectionCard(title = "关于") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("版本", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("构建", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = BuildConfig.GIT_COMMIT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Divider()
                Text(
                    text = "远程看屏",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "极简远程看屏：父母端共享屏幕，子女端在手机或浏览器查看，输入 6 位连接码即可使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun ServerUrlPicker(current: String, onChange: (String) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    var customText by rememberSaveable(current) { mutableStateOf(current) }
    val presetMatch = ServerPrefs.PRESETS.firstOrNull { it.first == current }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 下拉选择预设
        Box {
            OutlinedButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = presetMatch?.second ?: "自定义",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "展开")
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                ServerPrefs.PRESETS.forEach { (url, label) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label)
                                Text(
                                    url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            customText = url
                            onChange(url)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        // 自定义输入框：值变化时实时持久化
        OutlinedTextField(
            value = customText,
            onValueChange = {
                customText = it
                onChange(it)
            },
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
}

@Composable
private fun QualityRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Button(onClick = onClick, modifier = Modifier.size(width = 72.dp, height = 36.dp), contentPadding = PaddingValues(0.dp)) {
                Text("已选", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            OutlinedButton(onClick = onClick, modifier = Modifier.size(width = 72.dp, height = 36.dp), contentPadding = PaddingValues(0.dp)) {
                Text("选择", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
