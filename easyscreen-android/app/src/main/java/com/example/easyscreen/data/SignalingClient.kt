package com.example.easyscreen.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val gson: Gson = Gson()
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _messages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SignalingMessage> = _messages.asSharedFlow()

    fun connect(serverUrl: String) {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(serverUrl).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val msg = gson.fromJson(text, SignalingMessage::class.java)

                    // 自动处理 room_ready 提取 roomId
                    if (msg.type == MessageType.ROOM_READY && !msg.roomId.isNullOrEmpty()) {
                        _roomId.value = msg.roomId
                    }

                    // 自动回复 Pong
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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }

    fun register() {
        send(SignalingMessage(type = MessageType.REGISTER))
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

    fun send(message: SignalingMessage) {
        val json = gson.toJson(message)
        Log.d(TAG, "Sending: $json")
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send message, WebSocket may be closed")
        }
    }

    fun disconnect() {
        try {
            send(SignalingMessage(type = MessageType.DISCONNECT))
        } catch (_: Exception) {
        }
        webSocket?.close(1000, null)
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _roomId.value = ""
    }
}
