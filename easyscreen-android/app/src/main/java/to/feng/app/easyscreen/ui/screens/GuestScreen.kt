package to.feng.app.easyscreen.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    private val signalingClient = SignalingClient()
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

    private var hasJoined = false

    init {
        // 初始化 WebRTC
        webRTCManager.initialize(
            appContext,
            appContext
        )

        setupWebRTCCallbacks()
    }

    private fun setupWebRTCCallbacks() {
        webRTCManager.onLocalDescription = { sdp ->
            val payload = SdpPayload(sdp.description, sdp.type.canonicalForm())
            signalingClient.sendAnswer(payload)
        }

        webRTCManager.onLocalCandidate = { candidate ->
            val params = webRTCManager.getIceCandidateParams(candidate)
            signalingClient.sendCandidate(
                IceCandidatePayload(params.candidate, params.sdpMid, params.sdpMLineIndex)
            )
        }

        webRTCManager.onIceConnectionChange = { state ->
            when (state) {
                org.webrtc.PeerConnection.IceConnectionState.CONNECTED -> {
                    _statusMessage.value = "已连接，视频通话中"
                }
                org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED -> {
                    _statusMessage.value = "连接已断开"
                }
                org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                    _statusMessage.value = "连接失败，请重试"
                    cleanupResources()
                }
                else -> {}
            }
        }

        // 监听断开连接
        webRTCManager.onDisconnected = {
            _statusMessage.value = "对方已断开连接"
            _remoteViewReady.value = false
        }
    }

    fun createRemoteView(): SurfaceViewRenderer? {
        surfaceViewRenderer = webRTCManager.createSurfaceViewRenderer()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 返回按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = {
                viewModel.disconnect()
                onBack()
            }) {
                Text("< 返回")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isConnected) {
            // 输入界面
            Text(
                text = "输入6位连接码",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = inputCode,
                onValueChange = { viewModel.updateInputCode(it) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Center,
                    fontSize = 36.sp,
                    fontFamily = FontFamily.Monospace
                ),
                placeholder = {
                    Text(
                        text = "_ _ _ _ _ _",
                        textAlign = TextAlign.Center,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                singleLine = true,
                enabled = !isConnected
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.joinRoom() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = inputCode.length == 6 && connectionState != ConnectionState.CONNECTING && !isConnected
            ) {
                if (connectionState == ConnectionState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(text = "开始连接", style = MaterialTheme.typography.titleLarge)
                }
            }

            // 状态信息
            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> if (isConnected) ConnectedGreen else WaitingAmber
                            ConnectionState.CONNECTING -> WaitingAmber
                            ConnectionState.DISCONNECTED -> if (isConnected) ConnectedGreen else ErrorRed
                        }
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 视频观看界面
            Text(
                text = "正在观看对方屏幕",
                style = MaterialTheme.typography.headlineMedium,
                color = ConnectedGreen
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 视频区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (remoteViewReady) {
                        val renderer = viewModel.createRemoteView()
                        if (renderer != null) {
                            AndroidView(
                                factory = { renderer },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "视频加载失败",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ErrorRed
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = statusMessage.ifEmpty { "等待视频连接..." },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 状态指示
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (remoteViewReady) ConnectedGreen else WaitingAmber
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (remoteViewReady) "视频连接正常" else "等待视频...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 断开连接按钮
            OutlinedButton(
                onClick = {
                    viewModel.disconnect()
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ErrorRed
                )
            ) {
                Text(text = "断开连接", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
