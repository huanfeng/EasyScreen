package to.feng.app.easyscreen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import to.feng.app.easyscreen.data.*
import to.feng.app.easyscreen.ui.theme.ConnectedGreen
import to.feng.app.easyscreen.ui.theme.ErrorRed
import to.feng.app.easyscreen.ui.theme.WaitingAmber
import to.feng.app.easyscreen.webrtc.WebRTCManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

class GuestViewModel(private val serverUrl: String, private val appContext: android.content.Context) : ViewModel() {
    private val signalingClient = SignalingClient(context = appContext)
    private val gson = Gson()
    private val webRTCManager = WebRTCManager.getInstance()

    private val _inputCode = MutableStateFlow("")
    val inputCode: StateFlow<String> = _inputCode.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _remoteViewReady = MutableStateFlow(false)
    val remoteViewReady: StateFlow<Boolean> = _remoteViewReady.asStateFlow()

    var surfaceViewRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: org.webrtc.VideoTrack? = null

    private var hasJoined = false

    init {
        webRTCManager.initialize(appContext, appContext)
        setupWebRTCCallbacks()
        // 自动回填上次输入的连接码
        val saved = GuestPrefs.getLastCode(appContext)
        if (saved.length == 6) _inputCode.value = saved
    }

    private fun setupWebRTCCallbacks() {
        // Guest 既会 createOffer 也会接 Answer 后做 setRemote；onLocalDescription
        // 只在 createOffer 路径触发，应当按 SDP 类型派发
        webRTCManager.onLocalDescription = { sdp ->
            val payload = SdpPayload(sdp.description, sdp.type.canonicalForm())
            if (sdp.type == org.webrtc.SessionDescription.Type.OFFER) {
                signalingClient.sendOffer(payload)
            } else {
                signalingClient.sendAnswer(payload)
            }
        }

        webRTCManager.onLocalCandidate = { candidate ->
            val params = webRTCManager.getIceCandidateParams(candidate)
            signalingClient.sendCandidate(
                IceCandidatePayload(params.candidate, params.sdpMid, params.sdpMLineIndex)
            )
        }

        // 关键：远端视频 track 到达 → 挂到 renderer 并切到"视频就绪"状态
        webRTCManager.onVideoTrack = { track ->
            android.util.Log.d("GuestVM", "onVideoTrack: $track")
            if (track != null) {
                remoteVideoTrack = track
                // renderer 可能 UI 端还没创建，先记下 track；UI 端 createRemoteView() 时再 addSink
                surfaceViewRenderer?.let { rdr ->
                    try { track.addSink(rdr) } catch (_: Exception) {}
                }
                _remoteViewReady.value = true
            } else {
                _remoteViewReady.value = false
            }
        }

        webRTCManager.onIceConnectionChange = { state ->
            when (state) {
                org.webrtc.PeerConnection.IceConnectionState.CONNECTED -> {
                    _statusMessage.value = "已连接，视频通话中"
                }
                org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED -> {
                    _statusMessage.value = "连接不稳定…"
                }
                org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                    _statusMessage.value = "连接失败，请重试"
                    cleanupResources()
                }
                else -> {}
            }
        }

        webRTCManager.onDisconnected = {
            _statusMessage.value = "对方已断开连接"
            _remoteViewReady.value = false
        }
    }

    /**
     * UI 调用以创建/取出 renderer。如果 track 已经到了，立即挂上去。
     */
    fun createRemoteView(): SurfaceViewRenderer? {
        if (surfaceViewRenderer == null) {
            surfaceViewRenderer = webRTCManager.createSurfaceViewRenderer()
        }
        remoteVideoTrack?.let { track ->
            surfaceViewRenderer?.let { rdr ->
                try { track.addSink(rdr) } catch (_: Exception) {}
            }
        }
        return surfaceViewRenderer
    }

    fun updateInputCode(code: String) {
        _inputCode.value = code.filter { it.isDigit() }.take(6)
    }

    fun joinRoom() {
        val code = _inputCode.value
        if (code.length != 6) {
            _statusMessage.value = "请输入6位连接码"
            return
        }
        hasJoined = true
        _statusMessage.value = "正在连接..."
        // 记下本次输入的连接码，下次自动回填
        GuestPrefs.setLastCode(appContext, code)
        signalingClient.connect(serverUrl)

        viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.CONNECTED -> {
                        signalingClient.join(_inputCode.value)
                    }
                    ConnectionState.CONNECTING -> {
                        // 等待中
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (_isConnected.value) {
                            _statusMessage.value = "连接已断开"
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            signalingClient.messages.collect { message ->
                handleSignalingMessage(message)
            }
        }
    }

    private fun handleSignalingMessage(message: SignalingMessage) {
        when (message.type) {
            MessageType.ROOM_READY -> {
                _isConnected.value = true
                _statusMessage.value = "正在建立视频连接..."

                // 创建 PeerConnection 并创建 Offer
                webRTCManager.createPeerConnection(isHost = false)
                webRTCManager.createOffer()
            }

            MessageType.ERROR -> {
                try {
                    val errorData = gson.fromJson(gson.toJson(message.data), MessageData::class.java)
                    _statusMessage.value = errorData.message
                } catch (_: Exception) {
                    _statusMessage.value = "连接失败"
                }
            }

            MessageType.ANSWER -> {
                try {
                    val payload = gson.fromJson(gson.toJson(message.payload), SdpPayload::class.java)
                    webRTCManager.setRemoteDescription(payload.type, payload.sdp) { success ->
                        if (success) {
                            _statusMessage.value = "已建立连接，等待视频..."
                        }
                    }
                } catch (_: Exception) {}
            }

            MessageType.CANDIDATE -> {
                try {
                    val payload = gson.fromJson(gson.toJson(message.payload), IceCandidatePayload::class.java)
                    webRTCManager.addRemoteIceCandidate(
                        payload.candidate,
                        payload.sdpMid,
                        payload.sdpMLineIndex
                    )
                } catch (_: Exception) {}
            }

            MessageType.DISCONNECT -> {
                _isConnected.value = false
                cleanupResources()
                _statusMessage.value = "对方已断开连接"
            }
        }
    }

    private fun cleanupResources() {
        _remoteViewReady.value = false
        webRTCManager.releaseRenderer()
    }

    fun disconnect() {
        cleanupResources()
        webRTCManager.release()
        signalingClient.disconnect()
        hasJoined = false
        _isConnected.value = false
    }

    /**
     * "软退出"：仅断开当前观看会话，UI 回到输入码界面（保留上次的码）。
     * 用于观看页"返回 → 确认"后跳回输入页（而非退出整个 Guest 路由）。
     */
    fun leaveSession() {
        cleanupResources()
        webRTCManager.release()
        signalingClient.disconnect()
        remoteVideoTrack = null
        hasJoined = false
        _isConnected.value = false
        _statusMessage.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

class GuestViewModelFactory(private val serverUrl: String, private val appContext: android.content.Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GuestViewModel(serverUrl, appContext) as T
    }
}

@Composable
fun GuestScreen(
    onBack: () -> Unit,
    serverUrl: String
) {
    val scrollState = rememberScrollState()
    val viewModel: GuestViewModel = viewModel(factory = GuestViewModelFactory(serverUrl, LocalContext.current.applicationContext))
    val inputCode by viewModel.inputCode.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val remoteViewReady by viewModel.remoteViewReady.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                viewModel.disconnect()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!isConnected) {
        // ========== 输入阶段 ==========
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部返回栏（与 Host 一致的紧凑布局）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    viewModel.disconnect()
                    onBack()
                }) { Text("< 返回") }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "输入6位连接码",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { viewModel.updateInputCode(it) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        textAlign = TextAlign.Center,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                    ),
                    placeholder = {
                        // 关键：用 Box 撑满 + 显式居中，否则 placeholder 文本会贴左
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "------",
                                fontSize = 36.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 6.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isConnected,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { viewModel.joinRoom() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = inputCode.length == 6 && connectionState != ConnectionState.CONNECTING && !isConnected,
                ) {
                    if (connectionState == ConnectionState.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(text = "开始连接", style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(10.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> if (isConnected) ConnectedGreen else WaitingAmber
                                ConnectionState.CONNECTING -> WaitingAmber
                                ConnectionState.DISCONNECTED -> if (isConnected) ConnectedGreen else ErrorRed
                            },
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else {
        // ========== 全屏视频观看阶段 ==========
        FullscreenVideoView(
            remoteViewReady = remoteViewReady,
            statusMessage = statusMessage,
            createRenderer = { viewModel.createRemoteView() },
            onLeaveToInput = { viewModel.leaveSession() },
        )
    }
}

@Composable
private fun FullscreenVideoView(
    remoteViewReady: Boolean,
    statusMessage: String,
    createRenderer: () -> org.webrtc.SurfaceViewRenderer?,
    onLeaveToInput: () -> Unit,
) {
    // 缩放 / 平移状态
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val minScale = 1f
    val maxScale = 6f

    // 返回二次确认
    var showLeaveConfirm by remember { mutableStateOf(false) }
    val askLeave: () -> Unit = { showLeaveConfirm = true }
    androidx.activity.compose.BackHandler { askLeave() }
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("停止观看？") },
            text = { Text("将断开当前画面，回到输入连接码界面。") },
            confirmButton = {
                Button(onClick = {
                    showLeaveConfirm = false
                    onLeaveToInput()
                }) { Text("停止观看") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLeaveConfirm = false }) { Text("继续观看") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            // 手势放在外层 Box 上，确保不被 SurfaceView 偷走
            .pointerInput(Unit) {
                detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    if (newScale != scale) {
                        val ratio = newScale / scale
                        offset = androidx.compose.ui.geometry.Offset(
                            x = centroid.x - (centroid.x - offset.x) * ratio,
                            y = centroid.y - (centroid.y - offset.y) * ratio,
                        )
                        scale = newScale
                    }
                    if (scale > 1f) {
                        offset = androidx.compose.ui.geometry.Offset(
                            x = offset.x + pan.x,
                            y = offset.y + pan.y,
                        )
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            scale = 2f
                            offset = androidx.compose.ui.geometry.Offset(
                                x = tap.x * (1 - 2f),
                                y = tap.y * (1 - 2f),
                            )
                        }
                    },
                )
            },
    ) {
        if (remoteViewReady) {
            val renderer = createRenderer()
            if (renderer != null) {
                AndroidView(
                    factory = { renderer },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
        } else {
            // 加载中
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusMessage.ifEmpty { "等待视频连接..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }

        // 左上角浮动返回（半透明圆形按钮）—— 弹确认对话框
        androidx.compose.material3.IconButton(
            onClick = askLeave,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
                .size(44.dp)
                .background(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        ) {
            Text(
                text = "<",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
        }

        // 右下角缩放百分比 + 重置
        if (scale > 1.01f) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${(scale * 100).toInt()}%",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text(
                        "重置",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
