@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package to.feng.app.easyscreen.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    /** 远端源画面尺寸 + 旋转角度，UI 用来决定是否要 90° 旋转视频容器 */
    val sourceVideoSize: StateFlow<WebRTCManager.SourceSize> = webRTCManager.sourceVideoSize

    var surfaceViewRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: org.webrtc.VideoTrack? = null

    private var hasJoined = false

    init {
        webRTCManager.initialize(appContext, appContext)
        setupWebRTCCallbacks()
        // 自动回填上次输入的连接码
        val saved = GuestPrefs.getLastCode(appContext)
        if (saved.length == 6) _inputCode.value = saved

        // 关键：信令状态/消息的 collector 只跑一次，避免多次点击"开始连接"造成重复 join
        viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.CONNECTED -> {
                        if (hasJoined) signalingClient.join(_inputCode.value)
                    }
                    ConnectionState.CONNECTING -> {}
                    ConnectionState.DISCONNECTED -> {
                        if (_isConnected.value) _statusMessage.value = "连接已断开"
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
        if (hasJoined) {
            // 已经在尝试中，避免重复 join；如 WS 已连上，会立刻发送 join
            if (_connectionState.value == ConnectionState.CONNECTED) {
                signalingClient.join(code)
            }
            return
        }
        hasJoined = true
        _statusMessage.value = "正在连接..."
        GuestPrefs.setLastCode(appContext, code)
        signalingClient.connect(serverUrl)
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
        // 关键：清掉 ViewModel 本地对 renderer 的引用，避免下一次 createRemoteView 复用已 release 的对象
        surfaceViewRenderer = null
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
        // release() 把 WebRTCManager 单例的所有回调清空了，这里重新装回去
        // 否则下次 joinRoom 时本地 SDP / candidate 不会发往服务端 → 永远卡在"建立视频连接"
        setupWebRTCCallbacks()
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
        // ========== 输入阶段：正常应用 safeDrawing inset ==========
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
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

                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current

                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { viewModel.updateInputCode(it) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = LocalTextStyle.current.copy(
                        textAlign = TextAlign.Center,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                    ),
                    placeholder = {
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
                    trailingIcon = if (inputCode.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = {
                                    viewModel.updateInputCode("")
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Text(
                                    text = "×",
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else null,
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
        val sourceSize by viewModel.sourceVideoSize.collectAsState()
        FullscreenVideoView(
            remoteViewReady = remoteViewReady,
            statusMessage = statusMessage,
            createRenderer = { viewModel.createRemoteView() },
            sourceSize = sourceSize,
            onLeaveToInput = { viewModel.leaveSession() },
        )
    }
}

@Composable
private fun FullscreenVideoView(
    remoteViewReady: Boolean,
    statusMessage: String,
    createRenderer: () -> org.webrtc.SurfaceViewRenderer?,
    sourceSize: WebRTCManager.SourceSize,
    onLeaveToInput: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // contain / cover 切换（持久化）
    var fillMode by remember { mutableStateOf(GuestPrefs.getFillMode(context)) }
    LaunchedEffect(fillMode) {
        GuestPrefs.setFillMode(context, fillMode)
        WebRTCManager.getInstance().setRendererFillMode(fillMode)
    }

    // 缩放 / 平移状态
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var stageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val minScale = 1f
    val maxScale = 6f

    // 缩放后保持画面在 viewport 内的边界裁剪
    fun clampOffset(s: Float, o: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset {
        if (stageSize.width <= 0 || stageSize.height <= 0) return o
        val w = stageSize.width.toFloat()
        val h = stageSize.height.toFloat()
        // 当 scale=1 时，offset 应为 0（画面铺满）；scale>1 时，允许在四周边界内拖
        val maxX = (w * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (h * (s - 1f) / 2f).coerceAtLeast(0f)
        return androidx.compose.ui.geometry.Offset(
            x = o.x.coerceIn(-maxX, maxX),
            y = o.y.coerceIn(-maxY, maxY),
        )
    }
    fun resetView() {
        scale = 1f
        offset = androidx.compose.ui.geometry.Offset.Zero
    }

    // ① 全屏：使用 IMMERSIVE_STICKY 模式（MIUI 上 transient-bar 行为不稳定）
    // 同时设置 decorFitsSystemWindows=false 让内容真正延伸到系统栏下方
    var systemBarsVisible by remember { mutableStateOf(false) }

    fun applyImmersive(hide: Boolean) {
        val window = activity?.window ?: return
        val view = window.decorView
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // 老接口的 immersive sticky 标志：MIUI / 一些 OEM 比 WindowInsetsControllerCompat 更可靠
        @Suppress("DEPRECATION")
        view.systemUiVisibility = if (hide) {
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        // 强力开关：让窗口完全无视系统给的 inset 限制（覆盖到状态栏 / 挖孔区域）
        // 这是 MIUI / 一些 OEM 仍把 Activity 下推 status bar 高度的根因修复
        var prevCutoutMode: Int? = null
        if (window != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val attrs = window.attributes
                prevCutoutMode = attrs.layoutInDisplayCutoutMode
                attrs.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                window.attributes = attrs
            }
        }
        applyImmersive(hide = true)
        onDispose {
            val view = window?.decorView
            if (window != null && view != null) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && prevCutoutMode != null) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = prevCutoutMode
                    window.attributes = attrs
                }
                @Suppress("DEPRECATION")
                view.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(systemBarsVisible) {
        applyImmersive(hide = !systemBarsVisible)
    }

    // ② 共享中保持屏幕常亮
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 源方向变化时把缩放/平移重置，避免画面被裁切到屏幕外
    val srcEff = sourceSize.effectiveSize()
    val srcLandscape = srcEff.first > 0 && srcEff.first > srcEff.second
    LaunchedEffect(srcLandscape) {
        scale = 1f
        offset = androidx.compose.ui.geometry.Offset.Zero
    }

    // 让 Activity 跟随源画面方向旋转（仅在方向真正变化时触发，避免同方向不同分辨率时多次设置）
    val sourceOrientationKey: Int = run {
        val eff = sourceSize.effectiveSize()
        when {
            eff.first == 0 || eff.second == 0 -> 0   // 未知
            eff.first > eff.second -> 1              // 横向
            else -> 2                                // 竖向
        }
    }
    LaunchedEffect(sourceOrientationKey) {
        if (sourceOrientationKey == 0) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        act.requestedOrientation = if (sourceOrientationKey == 1) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    // 离开观看页时恢复未锁定方向
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

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

    // 用 WindowMetrics 拿屏幕真实尺寸（含系统栏区域），跟 configuration 重读
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenBoundsDp = remember(configuration, activity) {
        val act = activity
        if (act != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = act.windowManager.currentWindowMetrics.bounds
            with(density) {
                androidx.compose.ui.unit.DpSize(
                    bounds.width().toDp(),
                    bounds.height().toDp(),
                )
            }
        } else {
            val dm = act?.resources?.displayMetrics
            if (dm != null) with(density) {
                androidx.compose.ui.unit.DpSize(dm.widthPixels.toDp(), dm.heightPixels.toDp())
            } else androidx.compose.ui.unit.DpSize(0.dp, 0.dp)
        }
    }
    android.util.Log.d("GuestVM", "screenBoundsDp=$screenBoundsDp config=${configuration.orientation}")

    Box(
        modifier = Modifier
            // 强制使用屏幕真实尺寸，绕开任何 system bar 引起的 inset / shrink
            .let {
                if (screenBoundsDp.width > 0.dp && screenBoundsDp.height > 0.dp) {
                    it.requiredSize(screenBoundsDp)
                } else it.fillMaxSize()
            }
            .background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { stageSize = it }
            // 手势放在外层 Box 上，确保不被 SurfaceView 偷走
            .pointerInput(Unit) {
                detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    var newOffset = offset
                    if (newScale != scale) {
                        // 以 centroid 为焦点缩放：让该点在屏幕坐标系保持不变
                        val ratio = newScale / scale
                        newOffset = androidx.compose.ui.geometry.Offset(
                            x = centroid.x - (centroid.x - offset.x) * ratio,
                            y = centroid.y - (centroid.y - offset.y) * ratio,
                        )
                        scale = newScale
                    }
                    if (scale > 1f) {
                        newOffset = androidx.compose.ui.geometry.Offset(
                            x = newOffset.x + pan.x,
                            y = newOffset.y + pan.y,
                        )
                    }
                    offset = clampOffset(scale, newOffset)
                    // scale=1 强制 offset 归零（避免缩到底后画面漂出去）
                    if (scale <= 1.001f) offset = androidx.compose.ui.geometry.Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // 单击切换系统栏显示
                        systemBarsVisible = !systemBarsVisible
                    },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            resetView()
                        } else {
                            // 以双击点为焦点放大到 2x
                            val newScale = 2f
                            val ratio = newScale / scale
                            val newOffset = androidx.compose.ui.geometry.Offset(
                                x = tap.x - (tap.x - offset.x) * ratio,
                                y = tap.y - (tap.y - offset.y) * ratio,
                            )
                            scale = newScale
                            offset = clampOffset(scale, newOffset)
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

        // 顶部按钮使用 alpha 动画（而非 AnimatedVisibility）+ statusBarsIgnoringVisibility
        // 原因：① AnimatedVisibility 进退场会牵动 layout；② statusBarsPadding 会在系统栏
        // 显隐瞬间改变 inset 值（沉浸态 -> 0），导致按钮"塌缩+位移+淡出"叠加。
        // statusBarsIgnoringVisibility 始终返回 status bar 占位高度，按钮位置保持稳定。
        val controlsAlpha by animateFloatAsState(
            targetValue = if (systemBarsVisible) 1f else 0f,
            label = "controlsAlpha",
        )

        // 左上：返回按钮
        androidx.compose.material3.IconButton(
            onClick = askLeave,
            enabled = systemBarsVisible,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(start = 12.dp, top = 12.dp)
                .size(44.dp)
                .alpha(controlsAlpha)
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

        // 右上：充满/适应切换按钮
        androidx.compose.material3.IconButton(
            onClick = { fillMode = !fillMode },
            enabled = systemBarsVisible,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(end = 12.dp, top = 12.dp)
                .size(44.dp)
                .alpha(controlsAlpha)
                .background(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        ) {
            Text(
                text = if (fillMode) "▣" else "▢",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // 右下角缩放百分比 + 重置
        if (scale > 1.01f) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()             // 让浮窗避开手势条
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
                    onClick = { resetView() },
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
