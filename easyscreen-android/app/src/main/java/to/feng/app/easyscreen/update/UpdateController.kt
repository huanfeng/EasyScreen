package to.feng.app.easyscreen.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** UI 可观察的更新状态。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: AppVersionInfo, val kind: UpdateKind) : UpdateState
    data class Downloading(val info: AppVersionInfo, val kind: UpdateKind, val progress: Int) : UpdateState
    data class ReadyToInstall(val info: AppVersionInfo, val kind: UpdateKind, val apk: File) : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 协调检查→下载→安装的状态机。UI 观察 [state]。
 */
class UpdateController(
    private val repository: UpdateRepository = UpdateRepository(),
    private val downloader: ApkDownloader = ApkDownloader(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /**
     * 检查更新。
     * @param silent 启动静默检查：已是最新或已跳过该版本时不改变为打扰态。
     */
    fun check(scope: CoroutineScope, context: Context, wsServerUrl: String, currentCode: Int, silent: Boolean) {
        if (!silent) _state.value = UpdateState.Checking
        scope.launch {
            val result = repository.fetchLatest(wsServerUrl)
            val info = result.getOrElse {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed(it.message ?: "检查失败")
                return@launch
            }
            when (decideUpdate(currentCode, info)) {
                UpdateKind.NONE -> _state.value = if (silent) UpdateState.Idle else UpdateState.UpToDate
                UpdateKind.OPTIONAL -> {
                    if (silent && UpdatePrefs.getSkippedVersion(context) == info.versionCode) {
                        _state.value = UpdateState.Idle
                    } else {
                        _state.value = UpdateState.Available(info, UpdateKind.OPTIONAL)
                    }
                }
                UpdateKind.FORCED -> _state.value = UpdateState.Available(info, UpdateKind.FORCED)
            }
        }
    }

    fun startDownload(scope: CoroutineScope, context: Context, wsServerUrl: String) {
        val current = _state.value
        val (info, kind) = when (current) {
            is UpdateState.Available -> current.info to current.kind
            else -> return
        }
        val url = repository.resolveDownloadUrl(wsServerUrl, info) ?: run {
            _state.value = UpdateState.Failed("无法解析下载地址")
            return
        }
        _state.value = UpdateState.Downloading(info, kind, 0)
        scope.launch {
            val result = downloader.download(context, url, info.sha256) { p ->
                val s = _state.value
                if (s is UpdateState.Downloading) {
                    _state.value = s.copy(progress = p.coerceAtLeast(0))
                }
            }
            result.fold(
                onSuccess = { _state.value = UpdateState.ReadyToInstall(info, kind, it) },
                onFailure = { _state.value = UpdateState.Failed(it.message ?: "下载失败") },
            )
        }
    }

    fun skip(context: Context) {
        val s = _state.value
        if (s is UpdateState.Available && s.kind == UpdateKind.OPTIONAL) {
            UpdatePrefs.setSkippedVersion(context, s.info.versionCode)
        }
        _state.value = UpdateState.Idle
    }

    fun dismiss() { _state.value = UpdateState.Idle }
}
