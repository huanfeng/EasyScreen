package com.example.easyscreen.data

import com.google.gson.annotations.SerializedName

/**
 * 信令消息结构，与 Go 服务端 WSMessage 对应
 */
data class SignalingMessage(
    @SerializedName("type") val type: String,
    @SerializedName("room_id") val roomId: String? = null,
    @SerializedName("data") val data: Any? = null,
    @SerializedName("payload") val payload: Any? = null
)

data class MessageData(
    @SerializedName("message") val message: String = ""
)

data class SdpPayload(
    @SerializedName("sdp") val sdp: String = "",
    @SerializedName("type") val type: String = ""
)

data class IceCandidatePayload(
    @SerializedName("candidate") val candidate: String = "",
    @SerializedName("sdpMid") val sdpMid: String = "",
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int = 0
)

object MessageType {
    const val REGISTER = "register"
    const val JOIN = "join"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val CANDIDATE = "candidate"
    const val ERROR = "error"
    const val ROOM_READY = "room_ready"
    const val DISCONNECT = "disconnect"
    const val PING = "ping"
    const val PONG = "pong"
}
