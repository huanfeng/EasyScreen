package to.feng.app.easyscreen.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class SignalingClient(
    private val gson: Gson = Gson(),
    private val context: Context? = null,
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null

    // 更短的 ping 间隔以便更快检测连接死亡
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // 监听网络变化：Wi-Fi → 蜂窝切换时立即强制重连，不等 TCP 超时
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _messages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SignalingMessage> = _messages.asSharedFlow()

    // 自动重连：在 user 主动 disconnect 之前，WS 一旦断开就指数退避重试
    private var serverUrl: String? = null
    private var userClosed = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    fun connect(serverUrl: String) {
        this.serverUrl = serverUrl
        userClosed = false
        reconnectAttempt = 0
        registerNetworkCallback()
        doConnect()
    }

    /**
     * 监听网络变化：Wi-Fi <-> 蜂窝切换时立即关闭旧 WS 并发起重连，避免等 TCP keepalive 超时
     */
    private fun registerNetworkCallback() {
        if (context == null || networkCallback != null) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lastNetwork: Network? = null

            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                val prev = lastNetwork
                lastNetwork = network
                // 网络从无到有 / 从一个网络切到另一个 → 立刻强制重连
                if (prev == null || prev != network) {
                    forceImmediateReconnect("network changed to $network")
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                if (lastNetwork == network) lastNetwork = null
                // 当前网络丢失：让现有 WS 立即失败，后续 onAvailable 会触发重连
                try { webSocket?.cancel() } catch (_: Exception) {}
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            Log.d(TAG, "NetworkCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try { connectivityManager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }

    /** 立刻关掉当前 WS 让 onClosed/onFailure 触发重连（重置退避计数） */
    private fun forceImmediateReconnect(reason: String) {
        if (userClosed || serverUrl == null) return
        Log.d(TAG, "forceImmediateReconnect: $reason")
        reconnectAttempt = 0
        reconnectJob?.cancel()
        // 关闭当前 ws；onClosed 会触发 scheduleReconnect（首次延迟 1s）
        try { webSocket?.cancel() } catch (_: Exception) {}
        // 主动也调度一次：万一 cancel 不触发 onClosed
        scope.launch {
            delay(200)
            if (!userClosed &&
                _connectionState.value != ConnectionState.CONNECTED &&
                _connectionState.value != ConnectionState.CONNECTING) {
                doConnect()
            }
        }
    }

    private fun doConnect() {
        val url = serverUrl ?: return
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "WebSocket connected")
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val msg = gson.fromJson(text, SignalingMessage::class.java)

                    if (msg.type == MessageType.ROOM_READY && !msg.roomId.isNullOrEmpty()) {
                        _roomId.value = msg.roomId
                    }

                    if (msg.type == MessageType.PING) {
                        send(SignalingMessage(type = MessageType.PONG))
                        return
                    }

                    scope.launch { _messages.emit(msg) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (userClosed || serverUrl == null) return
        reconnectJob?.cancel()
        // 退避：1s, 2s, 4s, 8s, 16s, 30s 上限
        val delayMs = (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempt++
        Log.d(TAG, "Reconnect attempt #$reconnectAttempt in ${delayMs}ms")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userClosed) doConnect()
        }
    }

    fun register(token: String? = null, maxGuests: Int = 5) {
        val payload = RegisterPayload(token = token ?: "", maxGuests = maxGuests)
        send(SignalingMessage(type = MessageType.REGISTER, payload = payload))
    }

    fun join(roomId: String) {
        send(SignalingMessage(type = MessageType.JOIN, roomId = roomId))
    }

    fun sendOffer(sdpPayload: SdpPayload) {
        send(SignalingMessage(type = MessageType.OFFER, payload = sdpPayload))
    }

    fun sendAnswer(sdpPayload: SdpPayload) {
        send(SignalingMessage(type = MessageType.ANSWER, payload = sdpPayload))
    }

    fun sendCandidate(candidatePayload: IceCandidatePayload) {
        send(SignalingMessage(type = MessageType.CANDIDATE, payload = candidatePayload))
    }

    fun kickGuest(guestId: String) {
        send(SignalingMessage(
            type = MessageType.KICK_GUEST,
            payload = GuestEventPayload(guestId = guestId),
        ))
    }

    fun send(message: SignalingMessage) {
        val json = gson.toJson(message)
        Log.d(TAG, "Sending: $json")
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send message, WebSocket may be closed")
        }
    }

    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        unregisterNetworkCallback()
        try {
            send(SignalingMessage(type = MessageType.DISCONNECT))
        } catch (_: Exception) {
        }
        webSocket?.close(1000, null)
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _roomId.value = ""
        serverUrl = null
    }
}
