package com.example.easyscreen.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
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

        // 国内常用的 STUN 服务器
        private val STUN_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.jiyun.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cn:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
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
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var videoSource: VideoSource? = null
    private var appContext: Context? = null

    private var isHost = false
    private var isReleased = false

    // 回调接口
    var onLocalDescription: ((SessionDescription) -> Unit)? = null
    var onLocalCandidate: ((IceCandidate) -> Unit)? = null
    var onRemoteDescription: ((SessionDescription) -> Unit)? = null
    var onRemoteCandidate: ((IceCandidate) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onVideoTrack: ((VideoTrack?) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

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

                    // 监听断开和失败状态
                    when (it) {
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            Log.d(TAG, "ICE disconnected")
                            onDisconnected?.invoke()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "ICE failed")
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
     * 初始化被控端屏幕采集（弱网优化版）
     * @param resultCode MediaProjection 授权结果码
     * @param data MediaProjection 授权结果 Intent
     */
    fun startScreenCapture(resultCode: Int, data: Intent) {
        if (factory == null || eglBase == null) {
            Log.e(TAG, "WebRTC not initialized")
            return
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        videoCapturer = ScreenCapturerAndroid(data, null)

        // 创建视频源，标记为屏幕采集
        videoSource = factory?.createVideoSource(true)
        videoCapturer?.initialize(surfaceTextureHelper, appContext!!, videoSource?.capturerObserver)

        // 弱网优化：720p 15fps 降低码率
        videoCapturer?.startCapture(1280, 720, 15)

        // 创建 VideoTrack
        localVideoTrack = factory?.createVideoTrack("screen_track", videoSource)
        localVideoTrack?.setEnabled(true)

        // 添加到 PeerConnection
        peerConnection?.addTrack(localVideoTrack, listOf("screen_stream"))

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

        Log.d(TAG, "Screen capture started (720p 15fps)")
    }

    /**
     * 控制端：设置远端视频轨道到渲染器
     */
    fun setRemoteVideoTrack(track: VideoTrack?) {
        track?.addSink(surfaceViewRenderer)
    }

    /**
     * 创建 Offer（被控端收到控制端请求后调用）
     */
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
     * 释放所有资源
     */
    fun release() {
        if (isReleased) {
            return
        }
        isReleased = true

        try {
            // 停止屏幕采集
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            // 释放视频源
            videoSource?.dispose()
            videoSource = null

            // 释放轨道
            localVideoTrack?.setEnabled(false)
            localVideoTrack?.dispose()
            localVideoTrack = null

            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null

            // 关闭 PeerConnection
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            // 释放工厂
            factory?.dispose()
            factory = null

            // 释放 EGL
            eglBase?.release()
            eglBase = null

            // 释放渲染器
            surfaceViewRenderer?.release()
            surfaceViewRenderer = null

            // 重置单例
            instance = null

            Log.d(TAG, "WebRTC released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC", e)
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
