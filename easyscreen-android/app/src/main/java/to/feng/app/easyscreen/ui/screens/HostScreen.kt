package to.feng.app.easyscreen.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import to.feng.app.easyscreen.webrtc.DiagnosticsCollector
import to.feng.app.easyscreen.webrtc.PeerDiagnostics
import to.feng.app.easyscreen.webrtc.WebRTCManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI 用的单个 Guest 状态 */
data class GuestUiState(
    val guestId: String,
    val iceState: org.webrtc.PeerConnection.IceConnectionState? = null,
    val joinedAtMs: Long = System.currentTimeMillis(),
)

class HostViewModel(private val serverUrl: String, private val appContext: android.content.Context) : ViewModel() {
    private val signalingClient = SignalingClient(context = appContext)
    private val gson = Gson()
    private val webRTCManager = WebRTCManager.getInstance()

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("正在连接服务器...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** 已连接的观众列表（按加入顺序） */
    private val _guests = MutableStateFlow<List<GuestUiState>>(emptyList())
    val guests: StateFlow<List<GuestUiState>> = _guests.asStateFlow()

    /** 至少有一个 Guest 在等待/连接中 —— 用于触发 MediaProjection 授权 */
    private val _guestConnected = MutableStateFlow(false)
    val guestConnected: StateFlow<Boolean> = _guestConnected.asStateFlow()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private var roomInitialized = false
    private var pendingRecoveryRoomId: String? = null
    /** 用户主动停止时设为 true，后续 DISCONNECTED 状态不再当作"网络异常"提示 */
    private var userStopping = false

    // 诊断信息收集（每 Guest 一个采样任务）
    private val diagnosticsCollector = DiagnosticsCollector(viewModelScope)
    val diagnostics: StateFlow<Map<String, PeerDiagnostics>> = diagnosticsCollector.diagnostics

    /** UI 通过这个状态触发"重新授权"动作 —— 每次值变化代表一次新的请求 */
    val mediaProjectionStopped: StateFlow<Int> = webRTCManager.mediaProjectionStopped

    // 提早到达的 Offer / Candidate（capture 还没就绪时缓冲）
    private val pendingOffers = java.util.concurrent.ConcurrentHashMap<String, SdpPayload>()
    private val pendingCandidates =
        java.util.concurrent.ConcurrentHashMap<String, MutableList<IceCandidatePayload>>()

    init {
        webRTCManager.initialize(appContext, appContext)
        setupWebRTCCallbacks()
        setupSignalingCallbacks()
        signalingClient.connect(serverUrl)

        // 当本地采集就绪 → 把缓存的所有 Guest 创建 PC 并处理
        viewModelScope.launch {
            webRTCManager.captureReady.collect { ready ->
                if (ready) processAllPendingGuests()
            }
        }
    }

    private fun setupWebRTCCallbacks() {
        // 多 Guest 模式：所有回调按 guestId 路由
        webRTCManager.onGuestLocalDescription = { guestId, sdp ->
            val payload = SdpPayload(
                sdp = sdp.description,
                type = sdp.type.canonicalForm(),
                guestId = guestId,
            )
            if (sdp.type == org.webrtc.SessionDescription.Type.ANSWER) {
                signalingClient.sendAnswer(payload)
            } else {
                signalingClient.sendOffer(payload)
            }
        }

        webRTCManager.onGuestLocalCandidate = { guestId, candidate ->
            val params = webRTCManager.getIceCandidateParams(candidate)
            signalingClient.sendCandidate(
                IceCandidatePayload(
                    candidate = params.candidate,
                    sdpMid = params.sdpMid,
                    sdpMLineIndex = params.sdpMLineIndex,
                    guestId = guestId,
                )
            )
        }

        webRTCManager.onGuestIceConnectionChange = { guestId, state ->
            // 更新 UI 状态
            val current = _guests.value.toMutableList()
            val idx = current.indexOfFirst { it.guestId == guestId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(iceState = state)
                _guests.value = current
            }
            // FAILED 时该 Guest 留在列表里，UI 显示红点；用户可手动停止整体共享
            // 整体连接状态文案根据 active count 实时更新
            _statusMessage.value = summarizeGuestStates()
        }
    }

    private fun setupSignalingCallbacks() {
        viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.CONNECTED -> {
                        // 每次 WS 连上都重新 register（含首次和断后重连）
                        pendingRecoveryRoomId = _roomId.value.takeIf { it.isNotEmpty() }
                        _statusMessage.value =
                            if (roomInitialized) "网络已恢复，正在恢复会话..."
                            else "已连接，正在注册..."
                        roomInitialized = false
                        _roomId.value = ""
                        _guestConnected.value = false
                        // 旧观众列表清空：服务端会用 guest_join 重新告诉我们有谁
                        _guests.value = emptyList()
                        signalingClient.register(
                            HostTokenPrefs.getOrCreate(appContext),
                            maxGuests = 5,
                        )
                    }
                    ConnectionState.CONNECTING -> {
                        if (userStopping) return@collect
                        _statusMessage.value =
                            if (roomInitialized) "网络已断开，正在自动重连..."
                            else "正在连接服务器..."
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (userStopping) return@collect
                        if (_roomId.value.isNotEmpty()) {
                            _statusMessage.value = "网络已断开，正在自动重连..."
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
                    val newId = message.roomId
                    if (!newId.isNullOrEmpty()) {
                        val wasReconnect = pendingRecoveryRoomId != null
                        val sameRoom = pendingRecoveryRoomId == newId
                        val wasSharing = _isSharing.value
                        pendingRecoveryRoomId = null

                        _roomId.value = newId
                        roomInitialized = true

                        if (wasReconnect && !sameRoom && wasSharing) {
                            webRTCManager.release()
                            _isSharing.value = false
                            _guests.value = emptyList()
                            ScreenCaptureService.stop(appContext)
                            _statusMessage.value = "服务器已重置会话，请用新连接码重新共享"
                        } else if (sameRoom) {
                            _statusMessage.value = "已恢复原连接码，可继续使用"
                        } else {
                            _statusMessage.value = "等待对方输入连接码..."
                        }
                    }
                }
                // 旧版本协议中 Host 会收到第二次 room_ready 作为"Guest 加入"信号；
                // 新协议改用 GUEST_JOIN，本分支不再处理 guest 加入
            }

            MessageType.GUEST_JOIN -> {
                val gid = parseGuestId(message.payload) ?: return
                // 把 guest 加到 UI 列表（去重）
                if (_guests.value.none { it.guestId == gid }) {
                    _guests.value = _guests.value + GuestUiState(gid)
                }
                _guestConnected.value = true
                if (webRTCManager.hasActiveSession()) {
                    if (webRTCManager.getGuestPeerConnection(gid) == null) {
                        webRTCManager.addGuestPeerConnection(gid)
                        diagnosticsCollector.track(gid) { webRTCManager.getGuestPeerConnection(gid) }
                    }
                    _statusMessage.value = summarizeGuestStates()
                } else {
                    _statusMessage.value = "新观众加入，请授权屏幕共享..."
                }
            }

            MessageType.GUEST_LEAVE -> {
                val gid = parseGuestId(message.payload) ?: return
                _guests.value = _guests.value.filterNot { it.guestId == gid }
                webRTCManager.removeGuestPeerConnection(gid)
                diagnosticsCollector.stopTrack(gid)
                pendingOffers.remove(gid)
                pendingCandidates.remove(gid)
                if (_guests.value.isEmpty()) {
                    _guestConnected.value = false
                    // 观众全部离开 → 清除老人端浮窗残留标注
                    to.feng.app.easyscreen.annotation.AnnotationOverlayManager.hide()
                }
                _statusMessage.value = summarizeGuestStates()
            }

            MessageType.OFFER -> {
                val payload = try {
                    gson.fromJson(gson.toJson(message.payload), SdpPayload::class.java)
                } catch (e: Exception) { return }
                val gid = payload.guestId
                if (gid.isEmpty()) return  // 协议要求带 guest_id

                if (!webRTCManager.hasActiveSession()) {
                    // 缓冲到本地，等 captureReady 后再处理
                    pendingOffers[gid] = payload
                    return
                }
                handleGuestOffer(gid, payload)
            }

            MessageType.CANDIDATE -> {
                val payload = try {
                    gson.fromJson(gson.toJson(message.payload), IceCandidatePayload::class.java)
                } catch (e: Exception) { return }
                val gid = payload.guestId
                if (gid.isEmpty()) return

                val pc = webRTCManager.getGuestPeerConnection(gid)
                if (pc != null) {
                    webRTCManager.addGuestRemoteIceCandidate(
                        gid, payload.candidate, payload.sdpMid, payload.sdpMLineIndex,
                    )
                } else {
                    // PC 还没建，先缓冲
                    pendingCandidates.getOrPut(gid) { mutableListOf() }.add(payload)
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

            MessageType.DRAW_COMMAND -> {
                val payload = try {
                    gson.fromJson(gson.toJson(message.payload), DrawPayload::class.java)
                } catch (e: Exception) { return }
                val mgr = to.feng.app.easyscreen.annotation.AnnotationOverlayManager
                when (payload.op) {
                    DrawOp.BOUNDS_ON -> mgr.setDebugBounds(appContext, true)
                    DrawOp.BOUNDS_OFF -> mgr.setDebugBounds(appContext, false)
                    // 浮窗只在共享进行中有意义
                    else -> if (_isSharing.value) mgr.submit(appContext, payload)
                }
            }

            MessageType.DISCONNECT -> {
                _guestConnected.value = false
                cleanupResources()
                _statusMessage.value = "已断开"
            }
        }
    }

    private fun parseGuestId(payload: Any?): String? {
        if (payload == null) return null
        return try {
            val p = gson.fromJson(gson.toJson(payload), GuestEventPayload::class.java)
            p.guestId.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** capture 就绪：处理所有缓冲的 Guest 信令 */
    private fun processAllPendingGuests() {
        android.util.Log.d("HostVM", "processAllPendingGuests: guests=${_guests.value.size} pendingOffers=${pendingOffers.size} pendingCandidates=${pendingCandidates.size}")
        // 1. 已知的 Guest，但还没有 PC → 建 PC + 启动诊断采样
        for (g in _guests.value) {
            if (webRTCManager.getGuestPeerConnection(g.guestId) == null) {
                webRTCManager.addGuestPeerConnection(g.guestId)
                diagnosticsCollector.track(g.guestId) {
                    webRTCManager.getGuestPeerConnection(g.guestId)
                }
            }
        }
        // 2. Flush 缓冲的 Offer
        val pendingOffersSnap = pendingOffers.toMap()
        pendingOffers.clear()
        for ((gid, offer) in pendingOffersSnap) {
            handleGuestOffer(gid, offer)
        }
        // 3. Flush 缓冲的 Candidate
        val pendingCandSnap = pendingCandidates.toMap()
        pendingCandidates.clear()
        for ((gid, list) in pendingCandSnap) {
            for (cand in list) {
                webRTCManager.addGuestRemoteIceCandidate(
                    gid, cand.candidate, cand.sdpMid, cand.sdpMLineIndex,
                )
            }
        }
        _statusMessage.value = summarizeGuestStates()
    }

    private fun handleGuestOffer(guestId: String, payload: SdpPayload) {
        android.util.Log.d("HostVM", "handleGuestOffer gid=${guestId.take(8)} type=${payload.type} sdpLen=${payload.sdp.length}")
        if (webRTCManager.getGuestPeerConnection(guestId) == null) {
            webRTCManager.addGuestPeerConnection(guestId)
            diagnosticsCollector.track(guestId) {
                webRTCManager.getGuestPeerConnection(guestId)
            }
        }
        webRTCManager.setGuestRemoteDescription(guestId, payload.type, payload.sdp) { success ->
            android.util.Log.d("HostVM", "setRemoteDescription callback success=$success gid=${guestId.take(8)}")
            if (success) {
                webRTCManager.createGuestAnswer(guestId)
                pendingCandidates.remove(guestId)?.forEach { c ->
                    webRTCManager.addGuestRemoteIceCandidate(
                        guestId, c.candidate, c.sdpMid, c.sdpMLineIndex,
                    )
                }
            } else {
                _statusMessage.value = "为 ${guestId.take(8)} 重协商失败"
            }
        }
    }

    private fun processAllPendingGuestsLogged() = processAllPendingGuests()

    private fun summarizeGuestStates(): String {
        val list = _guests.value
        if (list.isEmpty()) return "等待对方输入连接码..."
        val connected = list.count {
            it.iceState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED ||
            it.iceState == org.webrtc.PeerConnection.IceConnectionState.COMPLETED
        }
        return "正在共享屏幕（$connected / ${list.size} 位观众在线）"
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            _isSharing.value = true
            _statusMessage.value = "正在启动屏幕共享..."

            // 启动前台服务；屏幕采集和 createAnswer 由 Service 在 startForeground 之后执行
            // （Android 14+ 强制要求 MediaProjection 必须在前台服务真正进入 foreground 之后获取）
            val q = QualityPrefs.get(appContext)
            ScreenCaptureService.start(
                appContext, resultCode, data,
                q.width, q.height, q.fps, q.maxBitrateBps, q.minBitrateBps,
            )
        } else {
            _statusMessage.value = "屏幕共享授权被拒绝"
            _guestConnected.value = false
        }
    }

    private fun cleanupResources() {
        _isSharing.value = false
        ScreenCaptureService.stop(appContext)
        to.feng.app.easyscreen.annotation.AnnotationOverlayManager.hide()
    }

    fun stopSharing() {
        userStopping = true
        cleanupResources()
        diagnosticsCollector.stopAll()
        webRTCManager.release()
        signalingClient.disconnect()
    }

    /** 房主主动踢出某个观众 */
    fun kickGuest(guestId: String) {
        signalingClient.kickGuest(guestId)
        // 本地立刻清理（服务端也会因为 ws 关闭推 guest_leave 上来双重确认）
        webRTCManager.removeGuestPeerConnection(guestId)
        diagnosticsCollector.stopTrack(guestId)
        _guests.value = _guests.value.filterNot { it.guestId == guestId }
        if (_guests.value.isEmpty()) _guestConnected.value = false
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
    val guests by viewModel.guests.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()

    // 返回二次确认（仅当正在共享时弹）
    var showExitConfirm by remember { mutableStateOf(false) }
    val safeBack: () -> Unit = {
        if (isSharing) showExitConfirm = true
        else { viewModel.stopSharing(); onBack() }
    }
    androidx.activity.compose.BackHandler(enabled = isSharing) {
        showExitConfirm = true
    }
    if (showExitConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("停止屏幕共享？") },
            text = { Text("当前正在共享屏幕，退出会断开对方的连接。是否退出？") },
            confirmButton = {
                Button(onClick = {
                    showExitConfirm = false
                    viewModel.stopSharing()
                    onBack()
                }) { Text("退出") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitConfirm = false }) { Text("继续共享") }
            }
        )
    }

    // MediaProjection 授权
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    // 监听 guestConnected，触发授权
    LaunchedEffect(guestConnected) {
        if (guestConnected && !WebRTCManager.getInstance().hasActiveSession()) {
            val projectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                    as android.media.projection.MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    // 监听 MediaProjection 被系统停止 → 自动重新弹授权（仅当仍有观众在线）
    val projectionStoppedTick by viewModel.mediaProjectionStopped.collectAsState()
    LaunchedEffect(projectionStoppedTick) {
        if (projectionStoppedTick > 0 && guestConnected) {
            android.util.Log.d("HostScreen", "MediaProjection 被停止，尝试重新申请授权")
            val projectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                    as android.media.projection.MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    // 共享期间常亮屏：避免系统息屏触发 MediaProjection 停止 / Doze
    DisposableEffect(isSharing) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        if (isSharing && window != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部返回栏（紧凑，不再额外预留 24dp 空白）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = safeBack) {
                Text("< 返回")
            }
        }

        // 中间可滚动区域
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

        // 浮窗权限提示：未授权时显示一键跳转入口（子女语音指导老人点这里）
        val ctxForOverlay = LocalContext.current
        var overlayGranted by remember {
            mutableStateOf(android.provider.Settings.canDrawOverlays(ctxForOverlay))
        }
        // 从系统设置页返回时复检
        val lifecycleForOverlay = LocalLifecycleOwner.current
        DisposableEffect(lifecycleForOverlay) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayGranted = android.provider.Settings.canDrawOverlays(ctxForOverlay)
                }
            }
            lifecycleForOverlay.lifecycle.addObserver(obs)
            onDispose { lifecycleForOverlay.lifecycle.removeObserver(obs) }
        }
        if (!overlayGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = WaitingAmber.copy(alpha = 0.15f)
                ),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "开启「悬浮窗」后，对方就能在你的屏幕上画圈指导你操作",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctxForOverlay.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctxForOverlay.startActivity(intent) } catch (_: Exception) {}
                    }) { Text("去开启悬浮窗") }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // UI 按"对端是否在看"区分，而不是"屏幕采集是否在跑"
        if (!guestConnected) {
            Text(
                text = when {
                    roomId.isEmpty() -> "正在获取连接码..."
                    isSharing -> "请用新号码重新连接"
                    else -> "将此号码告诉对方"
                },
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
                        // 6 位连号，靠字间距增强可读性；不再用空格分组，避免视觉割裂
                        Text(
                            text = roomId,
                            fontSize = 56.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            letterSpacing = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false
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
            // 共享中状态：显示观众列表 + 诊断面板
            Text(
                text = "正在共享屏幕",
                style = MaterialTheme.typography.headlineMedium,
                color = ConnectedGreen
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 房间号小字提示（共享中也能看到，让用户能告诉新观众）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("连接码", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = roomId,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 观众列表（含每人连接状态指示灯 + 踢出按钮）
            GuestListCard(
                guests = guests,
                diagnostics = diagnostics,
                onKick = { gid -> viewModel.kickGuest(gid) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 诊断面板（可折叠）
            var diagOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { diagOpen = !diagOpen }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("诊断信息", style = MaterialTheme.typography.titleSmall)
                        Text(if (diagOpen) "收起 ▲" else "展开 ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (diagOpen) {
                        DiagnosticsPanel(diagnostics = diagnostics)
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(16.dp))
        }   // 关闭中间滚动区

        // 底部固定区：状态行 + 停止共享按钮
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        isSharing -> ConnectedGreen
                        connectionState == ConnectionState.CONNECTED -> if (guestConnected) ConnectedGreen else WaitingAmber
                        connectionState == ConnectionState.CONNECTING -> WaitingAmber
                        else -> ErrorRed
                    },
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Button(
                onClick = {
                    viewModel.stopSharing()
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed,
                    contentColor = Color.White,
                ),
            ) {
                Text(text = "停止共享", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// =============== 观众列表 + 诊断面板 ===============

@androidx.compose.runtime.Composable
private fun GuestListCard(
    guests: List<GuestUiState>,
    diagnostics: Map<String, PeerDiagnostics>,
    onKick: (String) -> Unit = {},
) {
    var kickConfirmGid by remember { mutableStateOf<String?>(null) }
    kickConfirmGid?.let { gid ->
        AlertDialog(
            onDismissRequest = { kickConfirmGid = null },
            title = { Text("断开该观众？") },
            text = { Text("观众将立即失去画面，可重新输入连接码再次加入。") },
            confirmButton = {
                Button(onClick = { onKick(gid); kickConfirmGid = null }) { Text("断开") }
            },
            dismissButton = {
                OutlinedButton(onClick = { kickConfirmGid = null }) { Text("取消") }
            }
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ConnectedGreen.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            val connected = guests.count {
                it.iceState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED ||
                it.iceState == org.webrtc.PeerConnection.IceConnectionState.COMPLETED
            }
            Text(
                text = "观众 $connected / ${guests.size}",
                style = MaterialTheme.typography.titleSmall,
                color = ConnectedGreen,
            )
            if (guests.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("暂无观众", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                guests.forEachIndexed { idx, g ->
                    val diag = diagnostics[g.guestId]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val (color, label) = when (g.iceState) {
                            org.webrtc.PeerConnection.IceConnectionState.CONNECTED,
                            org.webrtc.PeerConnection.IceConnectionState.COMPLETED ->
                                ConnectedGreen to "已连接"
                            org.webrtc.PeerConnection.IceConnectionState.CHECKING,
                            org.webrtc.PeerConnection.IceConnectionState.NEW ->
                                WaitingAmber to "连接中"
                            org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED ->
                                WaitingAmber to "网络抖动"
                            org.webrtc.PeerConnection.IceConnectionState.FAILED ->
                                ErrorRed to "失败"
                            else -> MaterialTheme.colorScheme.onSurfaceVariant to "—"
                        }
                        Surface(
                            modifier = Modifier.size(10.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            color = color,
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "观众 ${idx + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = label + (diag?.connectionMode?.let { "  $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        OutlinedButton(
                            onClick = { kickConfirmGid = g.guestId },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ErrorRed,
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp, color = ErrorRed,
                            ),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "断开",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DiagnosticsPanel(diagnostics: Map<String, PeerDiagnostics>) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (diagnostics.isEmpty()) {
            Text(
                text = "暂无可显示的连接数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        diagnostics.values.forEachIndexed { idx, d ->
            if (idx > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = "观众 ${idx + 1}（${d.guestId.take(8)}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            DiagRow("连接模式", d.connectionMode + "  · " + d.pathType)
            DiagRow("编码", d.codec)
            DiagRow("分辨率 / 帧率", "${d.resolution}  @ ${"%.1f".format(d.framesPerSecond)}fps")
            DiagRow("发送码率", "%.0f kbps".format(d.bitrateKbps))
            DiagRow("RTT", d.rttMs?.let { "%.0f ms".format(it) } ?: "—")
            DiagRow("丢包 / 已发", "${d.packetsLost} / ${d.packetsSent}")
            DiagRow("PLI / FIR / NACK", "${d.pliCount} / ${d.firCount} / ${d.nackCount}")
        }
    }
}

@androidx.compose.runtime.Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
