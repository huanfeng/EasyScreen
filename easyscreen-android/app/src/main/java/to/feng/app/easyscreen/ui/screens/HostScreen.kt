package to.feng.app.easyscreen.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import to.feng.app.easyscreen.data.*
import to.feng.app.easyscreen.service.ScreenCaptureService
import to.feng.app.easyscreen.ui.theme.ConnectedGreen
import to.feng.app.easyscreen.ui.theme.ErrorRed
import to.feng.app.easyscreen.ui.theme.WaitingAmber
import to.feng.app.easyscreen.webrtc.WebRTCManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HostViewModel(private val serverUrl: String, private val appContext: android.content.Context) : ViewModel() {
    private val signalingClient = SignalingClient()
    private val gson = Gson()
    private val webRTCManager = WebRTCManager.getInstance()

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("正在连接服务器...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _guestConnected = MutableStateFlow(false)
    val guestConnected: StateFlow<Boolean> = _guestConnected.asStateFlow()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private var roomInitialized = false

    init {
        // 初始化 WebRTC
        webRTCManager.initialize(
            appContext,
            appContext
        )

        setupWebRTCCallbacks()
        setupSignalingCallbacks()

        signalingClient.connect(serverUrl)
    }

    private fun setupWebRTCCallbacks() {
        webRTCManager.onLocalDescription = { sdp ->
            val payload = SdpPayload(sdp.description, sdp.type.canonicalForm())
            signalingClient.sendOffer(payload)
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
                    // 清理资源
                    cleanupResources()
                }
                else -> {}
            }
        }

        // 监听断开连接
        webRTCManager.onDisconnected = {
            _statusMessage.value = "对方已断开连接"
            _guestConnected.value = false
            _isSharing.value = false
        }
    }

    private fun setupSignalingCallbacks() {
        viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.CONNECTED -> {
                        _statusMessage.value = "已连接，正在注册..."
                        signalingClient.register()
                    }
                    ConnectionState.CONNECTING -> {
                        _statusMessage.value = "正在连接服务器..."
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (_roomId.value.isNotEmpty()) {
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
                if (!roomInitialized) {
                    if (!message.roomId.isNullOrEmpty()) {
                        _roomId.value = message.roomId
                        roomInitialized = true
                        _statusMessage.value = "等待对方输入连接码..."
                    }
                } else {
                    _guestConnected.value = true
                    _statusMessage.value = "对方已连接，准备共享屏幕..."
                }
            }

            MessageType.ERROR -> {
                try {
                    val errorData = gson.fromJson(gson.toJson(message.data), MessageData::class.java)
                    _statusMessage.value = "错误: ${errorData.message}"
                } catch (_: Exception) {
                    _statusMessage.value = "发生错误"
                }
            }

            MessageType.OFFER -> {
                // 收到控制端 Offer
                _statusMessage.value = "收到连接请求，准备共享屏幕..."
                try {
                    val payload = gson.fromJson(gson.toJson(message.payload), SdpPayload::class.java)
                    webRTCManager.setRemoteDescription(payload.type, payload.sdp) { success ->
                        if (success) {
                            // 创建 PeerConnection
                            webRTCManager.createPeerConnection(isHost = true)
                            // 触发 MediaProjection 授权
                            _statusMessage.value = "请授权屏幕共享..."
                        }
                    }
                } catch (e: Exception) {
                    _statusMessage.value = "处理连接请求失败"
                }
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
                _guestConnected.value = false
                cleanupResources()
                _statusMessage.value = "对方已断开，等待重新连接..."
            }
        }
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            _isSharing.value = true
            _statusMessage.value = "正在启动屏幕共享..."

            // 启动前台服务
            ScreenCaptureService.start(appContext, resultCode, data)

            // 初始化屏幕采集
            webRTCManager.startScreenCapture(resultCode, data)

            // 创建 Offer
            webRTCManager.createOffer()
        } else {
            _statusMessage.value = "屏幕共享授权被拒绝"
            _guestConnected.value = false
        }
    }

    private fun cleanupResources() {
        _isSharing.value = false
        ScreenCaptureService.stop(appContext)
    }

    fun stopSharing() {
        cleanupResources()
        webRTCManager.release()
        signalingClient.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        stopSharing()
    }
}

class HostViewModelFactory(private val serverUrl: String, private val appContext: android.content.Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HostViewModel(serverUrl, appContext) as T
    }
}

@Composable
fun HostScreen(
    onBack: () -> Unit,
    serverUrl: String
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel: HostViewModel = viewModel(factory = HostViewModelFactory(serverUrl, applicationContext))
    val roomId by viewModel.roomId.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val guestConnected by viewModel.guestConnected.collectAsState()
    val isSharing by viewModel.isSharing.collectAsState()

    // MediaProjection 授权
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    // 监听 guestConnected，触发授权
    LaunchedEffect(guestConnected, isSharing) {
        if (guestConnected && !isSharing) {
            // 触发 MediaProjection 授权
            val projectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                    as android.media.projection.MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                viewModel.stopSharing()
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
                viewModel.stopSharing()
                onBack()
            }) {
                Text("< 返回")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isSharing) {
            // 等待连接状态
            Text(
                text = if (roomId.isEmpty()) "正在获取连接码..." else "将此号码告诉对方",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 6 位连接码
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (roomId.isNotEmpty()) {
                        Text(
                            text = roomId.chunked(1).joinToString(" "),
                            style = MaterialTheme.typography.displayLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        } else {
            // 共享中状态
            Text(
                text = "正在共享屏幕",
                style = MaterialTheme.typography.headlineMedium,
                color = ConnectedGreen
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = ConnectedGreen.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = ConnectedGreen
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "对方正在查看你的屏幕",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ConnectedGreen
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 状态指示
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = when {
                    isSharing -> ConnectedGreen
                    connectionState == ConnectionState.CONNECTED -> if (guestConnected) ConnectedGreen else WaitingAmber
                    connectionState == ConnectionState.CONNECTING -> WaitingAmber
                    else -> ErrorRed
                }
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 停止共享按钮
        Button(
            onClick = {
                viewModel.stopSharing()
                onBack()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ErrorRed,
                contentColor = Color.White
            )
        ) {
            Text(text = "停止共享", style = MaterialTheme.typography.titleLarge)
        }
    }
}
