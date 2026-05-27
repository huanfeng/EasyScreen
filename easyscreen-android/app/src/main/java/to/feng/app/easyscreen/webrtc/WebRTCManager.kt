package to.feng.app.easyscreen.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC 管理器（单例）
 * 封装 PeerConnectionFactory、PeerConnection，以及屏幕采集和视频渲染
 */
class WebRTCManager private constructor() {

    companion object {
        private const val TAG = "WebRTCManager"

        // 默认 ICE 服务器：STUN + 公共 TURN（openrelay.metered.ca 免费但无 SLA）
        // 对称 NAT / 移动数据网络下 P2P 打洞失败时，会自动回退到 TURN 中继
        private val STUN_SERVERS = listOf(
            // STUN
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.jiyun.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer(),
            // 公共 TURN（多端口 + UDP/TCP/TLS 同时尝试）
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject")
                .createIceServer(),
        )

        @Volatile
        private var instance: WebRTCManager? = null

        fun getInstance(): WebRTCManager {
            return instance ?: synchronized(this) {
                instance ?: WebRTCManager().also { instance = it }
            }
        }
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null  // Guest（观看者）单 PC 模型用
    private var videoCapturer: VideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var videoSource: VideoSource? = null
    private var appContext: Context? = null

    private var isHost = false
    private var isReleased = false

    // 当前 Host 的画质配置（addGuestPeerConnection 时给新 sender 设码率上下限）
    private var hostQualityMaxBitrateBps: Int = 2_000_000
    private var hostQualityMinBitrateBps: Int = 600_000
    private var hostQualityFps: Int = 20
    // 用户选择的"基础尺寸"（不考虑方向），动态切换时按方向重新对调
    private var hostBaseLong: Int = 1280
    private var hostBaseShort: Int = 720
    // 当前实际采集尺寸（防抖：旋转事件密集时跳过相同的值）
    private var currentCapW: Int = 0
    private var currentCapH: Int = 0

    // 监听屏幕方向变化：旋转后调 changeCaptureFormat，无需重建 PC / 重申请 MediaProjection
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    // Host 模式的多 Guest PC 管理（key=guestId）
    private val guestPCs = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()
    // 记下每个 PC 的 video / audio sender，便于"重新采集"时直接 setTrack 替换源
    // —— 避免 addTrack 重复创建 sender 引起 SDP 二义性
    private val guestVideoSenders = java.util.concurrent.ConcurrentHashMap<String, RtpSender>()
    private val guestAudioSenders = java.util.concurrent.ConcurrentHashMap<String, RtpSender>()

    // 回调接口（单 PC，Guest/Android 观看者用）
    var onLocalDescription: ((SessionDescription) -> Unit)? = null
    var onLocalCandidate: ((IceCandidate) -> Unit)? = null
    var onRemoteDescription: ((SessionDescription) -> Unit)? = null
    var onRemoteCandidate: ((IceCandidate) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onVideoTrack: ((VideoTrack?) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // 多 Guest PC 回调（Host 用）
    var onGuestLocalDescription: ((guestId: String, sdp: SessionDescription) -> Unit)? = null
    var onGuestLocalCandidate: ((guestId: String, candidate: IceCandidate) -> Unit)? = null
    var onGuestIceConnectionChange: ((guestId: String, state: PeerConnection.IceConnectionState) -> Unit)? = null

    // 屏幕采集就绪状态：true 表示本地 video/audio track 已创建可挂到 Guest PC
    private val _captureReady = MutableStateFlow(false)
    val captureReady: StateFlow<Boolean> = _captureReady.asStateFlow()

    // MediaProjection 被外部停止（系统息屏 / 用户在通知栏停止）—— ViewModel 监听并发起重新授权
    private val _mediaProjectionStopped = MutableStateFlow(0)  // 自增计数器，每次 +1 触发一次
    val mediaProjectionStopped: StateFlow<Int> = _mediaProjectionStopped.asStateFlow()

    /**
     * 初始化 WebRTC 工厂（必须先调用）
     */
    fun initialize(context: Context, applicationContext: Context) {
        if (factory != null) {
            Log.w(TAG, "WebRTC already initialized")
            return
        }

        isReleased = false
        appContext = applicationContext
        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(applicationContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // 创建音频模块
        val audioDeviceModule: AudioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            .createAudioDeviceModule()

        // 创建视频硬件加速工厂
        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext, true, true
        )
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized")
    }

    /**
     * 是否已有进行中的会话（本地视频 track 已经创建）。
     * 多 Guest 场景下：tracks 在 startScreenCapture 时创建，独立于具体 PC。
     */
    fun hasActiveSession(): Boolean = localVideoTrack != null

    /**
     * 当前已连接的 Guest 数量
     */
    fun guestCount(): Int = guestPCs.size

    /**
     * 已连接的 Guest ID 列表（快照）
     */
    fun activeGuestIds(): List<String> = guestPCs.keys.toList()

    /**
     * 获取 EGL 上下文，供 SurfaceViewRenderer 使用
     */
    fun getEglBaseContext(): EglBase? = eglBase

    /**
     * 创建用于渲染远端视频的 SurfaceViewRenderer
     */
    fun createSurfaceViewRenderer(): SurfaceViewRenderer? {
        if (eglBase == null || appContext == null) {
            Log.e(TAG, "EglBase not initialized, call initialize() first")
            return null
        }
        surfaceViewRenderer = SurfaceViewRenderer(appContext!!)
        surfaceViewRenderer?.init(eglBase?.eglBaseContext, null)
        surfaceViewRenderer?.setMirror(false)
        surfaceViewRenderer?.setEnableHardwareScaler(true)
        return surfaceViewRenderer
    }

    /**
     * 释放渲染器资源
     */
    fun releaseRenderer() {
        try {
            surfaceViewRenderer?.release()
            surfaceViewRenderer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing renderer", e)
        }
    }

    /**
     * 创建 PeerConnection
     * @param isHost true=被控端（发送屏幕）false=控制端（接收屏幕）
     */
    fun createPeerConnection(
        isHost: Boolean,
        turnServers: List<PeerConnection.IceServer> = emptyList()
    ) {
        if (factory == null) {
            Log.e(TAG, "Factory not initialized")
            return
        }

        this.isHost = isHost

        val rtcConfig = PeerConnection.RTCConfiguration(turnServers.ifEmpty { STUN_SERVERS }).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionChange: $state")
                state?.let {
                    onIceConnectionChange?.invoke(it)

                    when (it) {
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            // 网络抖动属于"瞬态"，WebRTC 自带超时后才会进 FAILED
                            // 不主动拆除会话，等待自愈或最终 FAILED
                            Log.d(TAG, "ICE disconnected (transient — waiting for auto-recover)")
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "ICE failed, attempting ICE restart")
                            // 尝试 ICE 重启：不需要重新协商完整 SDP，
                            // 浏览器对端会自动响应新一轮 candidate
                            try { peerConnection?.restartIce() } catch (e: Exception) {
                                Log.e(TAG, "restartIce failed", e)
                            }
                            onDisconnected?.invoke()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            Log.d(TAG, "ICE closed")
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "IceGatheringChange: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Local ICE candidate: ${it.sdp}")
                    onLocalCandidate?.invoke(it)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "AddStream: ${stream?.videoTracks?.size}")
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    Log.d(TAG, "Remote video track received")
                    onVideoTrack?.invoke(track)
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "RemoveStream")
                onVideoTrack?.invoke(null)
            }

            override fun onDataChannel(channel: DataChannel?) {}

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "onAddTrack")
                streams?.firstOrNull()?.videoTracks?.firstOrNull()?.let { track ->
                    onVideoTrack?.invoke(track)
                }
            }
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, observer)
        Log.d(TAG, "PeerConnection created (isHost=$isHost)")
    }

    /**
     * 初始化被控端屏幕采集
     * @param resultCode MediaProjection 授权结果码
     * @param data MediaProjection 授权结果 Intent
     * @param width / height / fps / maxBitrateBps / minBitrateBps 画质参数（来自 QualityPrefs）
     */
    fun startScreenCapture(
        resultCode: Int,
        data: Intent,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 20,
        maxBitrateBps: Int = 2_000_000,
        minBitrateBps: Int = 600_000,
    ) {
        if (factory == null || eglBase == null) {
            Log.e(TAG, "WebRTC not initialized")
            return
        }

        // 检测是否是"重新采集"（之前的 MediaProjection 被系统停掉后用户重新授权）
        val isRecapture = videoCapturer != null || localVideoTrack != null
        val oldVideoTrack = localVideoTrack
        val oldAudioTrack = localAudioTrack
        val oldVideoSource = videoSource
        val oldVideoCapturer = videoCapturer

        if (isRecapture) {
            Log.d(TAG, "Re-capture detected, will replace tracks on existing guest PCs")
            try { oldVideoCapturer?.stopCapture() } catch (_: Exception) {}
            // 旧 capturer/source/track 在新对象创建并 setTrack 完成后再 dispose
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        // 新版 webrtc-sdk 强制要求非空 MediaProjection.Callback
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection.onStop —— 屏幕可能熄屏/被系统回收")
                // 关闭本地采集相关资源（防止 GL Surface 残留崩溃）；但保留 PC 和 Guest 状态
                try { videoCapturer?.stopCapture() } catch (_: Exception) {}
                _captureReady.value = false
                // 通知 ViewModel：需要触发一次重新授权
                _mediaProjectionStopped.value = _mediaProjectionStopped.value + 1
            }
        }
        videoCapturer = ScreenCapturerAndroid(data, projectionCallback)

        // 创建视频源，标记为屏幕采集
        videoSource = factory?.createVideoSource(true)
        videoCapturer?.initialize(surfaceTextureHelper, appContext!!, videoSource?.capturerObserver)

        // 保存基础尺寸供方向切换重算
        hostBaseLong = maxOf(width, height)
        hostBaseShort = minOf(width, height)

        // 按设备实际方向自动对调 W/H，避免横向画布塞竖向源造成大面积黑边
        val (capW, capH) = computeCaptureSize()
        currentCapW = capW; currentCapH = capH
        videoCapturer?.startCapture(capW, capH, fps)
        Log.d(TAG, "Screen capture started ${capW}x${capH} @${fps}fps, bitrate ${minBitrateBps}-${maxBitrateBps}")

        // 注册 DisplayListener：旋转时动态 changeCaptureFormat
        registerDisplayListener()

        // 创建本地 VideoTrack / AudioTrack —— 不在此处绑到任何 PC，留给 addGuestPeerConnection 时按 Guest 挂
        localVideoTrack = factory?.createVideoTrack("screen_track", videoSource)
        localVideoTrack?.setEnabled(true)

        // 记下画质参数，每个新 Guest PC 创建时按此应用到 RtpSender
        hostQualityMaxBitrateBps = maxBitrateBps
        hostQualityMinBitrateBps = minBitrateBps
        hostQualityFps = fps

        // 单 PC 的旧 Host 兼容
        peerConnection?.let { pc ->
            val sender = pc.addTrack(localVideoTrack, listOf("screen_stream"))
            applyHostEncoding(sender)
        }

        // 给已经创建的 Guest PC 挂 / 替换 video track
        for ((gid, pc) in guestPCs) {
            val existing = guestVideoSenders[gid]
            if (existing != null) {
                // 重新采集：原地替换 track（不重协商，对端无感知）
                runCatching { existing.setTrack(localVideoTrack, false) }
                    .onFailure { Log.e(TAG, "[guest=$gid] video setTrack failed: ${it.message}") }
                applyHostEncoding(existing)
            } else {
                val sender = pc.addTrack(localVideoTrack, listOf("screen_stream"))
                applyHostEncoding(sender)
                sender?.let { guestVideoSenders[gid] = it }
            }
        }

        // 采集音频（可选）
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("audio_track", audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("audio_stream"))
        for ((gid, pc) in guestPCs) {
            val existing = guestAudioSenders[gid]
            if (existing != null) {
                runCatching { existing.setTrack(localAudioTrack, false) }
                    .onFailure { Log.e(TAG, "[guest=$gid] audio setTrack failed: ${it.message}") }
            } else {
                val sender = pc.addTrack(localAudioTrack, listOf("audio_stream"))
                sender?.let { guestAudioSenders[gid] = it }
            }
        }

        // 重新采集场景：原 track / source / capturer 已被新对象替换，安全 dispose
        if (isRecapture) {
            try { oldVideoTrack?.dispose() } catch (_: Exception) {}
            try { oldAudioTrack?.dispose() } catch (_: Exception) {}
            try { oldVideoSource?.dispose() } catch (_: Exception) {}
            try { oldVideoCapturer?.dispose() } catch (_: Exception) {}
        }

        _captureReady.value = true
    }

    /** 根据当前屏幕方向算出"长边 vs 短边"应该填给 startCapture 的 W/H */
    private fun computeCaptureSize(): Pair<Int, Int> {
        val ctx = appContext ?: return hostBaseLong to hostBaseShort
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val isPortrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        return if (isPortrait) hostBaseShort to hostBaseLong  // 竖屏：短边为宽，长边为高
               else hostBaseLong to hostBaseShort              // 横屏：长边为宽
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val ctx = appContext ?: return
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = dm
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val (newW, newH) = computeCaptureSize()
                if (newW == currentCapW && newH == currentCapH) return
                Log.d(TAG, "Display rotated → changeCaptureFormat ${currentCapW}x${currentCapH} → ${newW}x${newH}")
                try {
                    videoCapturer?.changeCaptureFormat(newW, newH, hostQualityFps)
                    currentCapW = newW; currentCapH = newH
                } catch (e: Exception) {
                    Log.e(TAG, "changeCaptureFormat failed", e)
                }
            }
        }
        try {
            dm.registerDisplayListener(listener, null)
            displayListener = listener
            Log.d(TAG, "DisplayListener registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerDisplayListener failed", e)
        }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        try { displayManager?.unregisterDisplayListener(l) } catch (_: Exception) {}
        displayListener = null
    }

    /** 给 sender 应用 Host 端码率上下限 */
    private fun applyHostEncoding(sender: org.webrtc.RtpSender?) {
        sender?.let {
            val params = it.parameters
            if (params != null && params.encodings.isNotEmpty()) {
                params.encodings.forEach { enc ->
                    enc.maxBitrateBps = hostQualityMaxBitrateBps
                    enc.minBitrateBps = hostQualityMinBitrateBps
                    enc.maxFramerate = hostQualityFps
                }
                it.parameters = params
            }
        }
    }

    /**
     * 控制端：设置远端视频轨道到渲染器
     */
    fun setRemoteVideoTrack(track: VideoTrack?) {
        track?.addSink(surfaceViewRenderer)
    }

    // ==================== Host 多 Guest PC 管理 ====================

    /**
     * 为某个 Guest 创建一个新的 PeerConnection，并把本地 audio/video track 挂上。
     * 不发起 SDP；需要随后调 setGuestRemoteDescription 设 Guest 的 Offer，再 createGuestAnswer。
     */
    fun addGuestPeerConnection(
        guestId: String,
        turnServers: List<PeerConnection.IceServer> = emptyList(),
    ): PeerConnection? {
        val f = factory ?: run { Log.e(TAG, "Factory not initialized"); return null }
        if (guestPCs.containsKey(guestId)) {
            Log.w(TAG, "Guest PC already exists: $guestId")
            return guestPCs[guestId]
        }

        val rtcConfig = PeerConnection.RTCConfiguration(turnServers.ifEmpty { STUN_SERVERS }).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "[guest=$guestId] ICE: $state")
                state?.let { onGuestIceConnectionChange?.invoke(guestId, it) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onGuestLocalCandidate?.invoke(guestId, it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = f.createPeerConnection(rtcConfig, observer)
        if (pc == null) {
            Log.e(TAG, "Failed to create PC for guest $guestId")
            return null
        }

        // 挂上本地 audio/video track（如果已就绪）
        localVideoTrack?.let {
            val sender = pc.addTrack(it, listOf("screen_stream"))
            applyHostEncoding(sender)
            sender?.let { s -> guestVideoSenders[guestId] = s }
        }
        localAudioTrack?.let {
            val sender = pc.addTrack(it, listOf("audio_stream"))
            sender?.let { s -> guestAudioSenders[guestId] = s }
        }

        guestPCs[guestId] = pc
        Log.d(TAG, "Guest PC created: $guestId (total=${guestPCs.size})")
        return pc
    }

    /** 为指定 Guest 设置远端 SDP（Offer） */
    fun setGuestRemoteDescription(
        guestId: String,
        type: String,
        sdp: String,
        callback: (Boolean) -> Unit,
    ) {
        val pc = guestPCs[guestId]
        if (pc == null) {
            Log.e(TAG, "[guest=$guestId] setRemote: PC missing")
            callback(false); return
        }
        val sdpType = when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> {
                Log.e(TAG, "[guest=$guestId] setRemote: unknown sdp type '$type'")
                callback(false); return
            }
        }
        Log.d(TAG, "[guest=$guestId] setRemote begin type=$sdpType len=${sdp.length}")
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.w(TAG, "[guest=$guestId] setRemote.onCreateSuccess (unexpected)")
            }
            override fun onSetSuccess() {
                Log.d(TAG, "[guest=$guestId] setRemote success")
                callback(true)
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[guest=$guestId] setRemote failed: $error")
                callback(false)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[guest=$guestId] setRemote.onCreateFailure: $error (unexpected)")
            }
        }, SessionDescription(sdpType, sdp))
    }

    /** 为指定 Guest 创建 Answer，触发 onGuestLocalDescription */
    fun createGuestAnswer(guestId: String) {
        val pc = guestPCs[guestId] ?: run {
            Log.e(TAG, "[guest=$guestId] createAnswer: PC missing"); return
        }
        Log.d(TAG, "[guest=$guestId] createAnswer begin")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                Log.d(TAG, "[guest=$guestId] createAnswer.onCreateSuccess sdp=${sdp != null} len=${sdp?.description?.length ?: 0}")
                if (sdp == null) {
                    Log.e(TAG, "[guest=$guestId] createAnswer.onCreateSuccess null sdp!")
                    return
                }
                Log.d(TAG, "[guest=$guestId] setLocal begin")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "[guest=$guestId] setLocal success → invoke onGuestLocalDescription")
                        onGuestLocalDescription?.invoke(guestId, sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[guest=$guestId] setLocal failed: $error")
                    }
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "[guest=$guestId] setLocal.onCreateFailure (unexpected): $error")
                    }
                }, sdp)
            }
            override fun onSetSuccess() {
                Log.w(TAG, "[guest=$guestId] createAnswer.onSetSuccess (unexpected)")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[guest=$guestId] createAnswer.onSetFailure: $error")
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[guest=$guestId] createAnswer.onCreateFailure: $error")
            }
        }, constraints)
    }

    fun addGuestRemoteIceCandidate(
        guestId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int,
    ) {
        guestPCs[guestId]?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    /** 关闭单个 Guest 的 PC（Guest 离开） */
    fun removeGuestPeerConnection(guestId: String) {
        val pc = guestPCs.remove(guestId) ?: return
        guestVideoSenders.remove(guestId)
        guestAudioSenders.remove(guestId)
        try { pc.close() } catch (_: Exception) {}
        try { pc.dispose() } catch (_: Exception) {}
        Log.d(TAG, "Guest PC removed: $guestId (remaining=${guestPCs.size})")
    }

    /** 拿到指定 Guest 的 PC（供诊断使用） */
    fun getGuestPeerConnection(guestId: String): PeerConnection? = guestPCs[guestId]

    /**
     * 创建 Offer（被控端收到控制端请求后调用）
     */
    fun createOffer() {
        // Guest 端调用：纯接收方，需声明要接收视频/音频，否则协商出的 SDP 不带 m= 行
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            onLocalDescription?.invoke(it)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create local description failed: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }
        }, constraints)
    }

    /**
     * 创建 Answer（控制端收到被控端 Offer 后调用）
     */
    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local answer description set")
                            onLocalDescription?.invoke(it)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set answer description failed: $error")
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create answer description failed: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }
        }, constraints)
    }

    /**
     * 设置远端 SDP（Offer 或 Answer）
     */
    fun setRemoteDescription(type: String, sdp: String, callback: (Boolean) -> Unit) {
        val sdpType = when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> {
                Log.e(TAG, "Unknown SDP type: $type")
                callback(false)
                return
            }
        }

        val remoteSdp = SessionDescription(sdpType, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set: $sdpType")
                onRemoteDescription?.invoke(remoteSdp)
                callback(true)
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set remote description failed: $error")
                callback(false)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create remote description failed: $error")
            }
        }, remoteSdp)
    }

    /**
     * 添加远端 ICE Candidate
     */
    fun addRemoteIceCandidate(
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int
    ) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "Remote ICE candidate added")
    }

    /**
     * 获取本地 ICE Candidate 的 SDP 信息
     */
    fun getIceCandidateParams(candidate: IceCandidate): IceCandidateParams {
        return IceCandidateParams(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
    }

    /**
     * 是否已释放
     */
    fun isReleased(): Boolean = isReleased

    /**
     * 释放会话相关资源（PeerConnection、Track、Capturer、Renderer）。
     * 注意：PeerConnectionFactory、EglBase、NetworkMonitor 是进程单例，
     * 一旦 dispose 后续 native 回调（如 ConnectivityManager 网络变化）会写入已释放内存 → SIGBUS。
     * 所以仅清理可重建的会话对象，保留底层运行时。
     */
    fun release() {
        try {
            unregisterDisplayListener()
            // 关闭所有 Guest PC
            for ((_, pc) in guestPCs) {
                try { pc.close() } catch (_: Exception) {}
                try { pc.dispose() } catch (_: Exception) {}
            }
            guestPCs.clear()
            guestVideoSenders.clear()
            guestAudioSenders.clear()

            // 停止屏幕采集
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            videoCapturer?.dispose()
            videoCapturer = null

            videoSource?.dispose()
            videoSource = null

            localVideoTrack?.setEnabled(false)
            localVideoTrack?.dispose()
            localVideoTrack = null

            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null

            _captureReady.value = false

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            // 渲染器可释放：下次连接 createSurfaceViewRenderer 会重建
            try { surfaceViewRenderer?.release() } catch (_: Exception) {}
            surfaceViewRenderer = null

            // 清除回调引用以避免外部对象（ViewModel 等）随单例长期持有
            onLocalDescription = null
            onLocalCandidate = null
            onRemoteDescription = null
            onRemoteCandidate = null
            onIceConnectionChange = null
            onVideoTrack = null
            onDisconnected = null
            onGuestLocalDescription = null
            onGuestLocalCandidate = null
            onGuestIceConnectionChange = null

            // 保留 factory / eglBase（进程级单例）
            Log.d(TAG, "WebRTC session released (factory kept alive)")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC session", e)
        }
    }
}

/**
 * ICE Candidate 参数
 */
data class IceCandidateParams(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
)
