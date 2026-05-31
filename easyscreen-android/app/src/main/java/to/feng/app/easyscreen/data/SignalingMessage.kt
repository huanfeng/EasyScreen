package to.feng.app.easyscreen.data

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
    @SerializedName("type") val type: String = "",
    @SerializedName("guest_id") val guestId: String = "",
)

data class IceCandidatePayload(
    @SerializedName("candidate") val candidate: String = "",
    @SerializedName("sdpMid") val sdpMid: String = "",
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int = 0,
    @SerializedName("guest_id") val guestId: String = "",
)

data class RegisterPayload(
    @SerializedName("token") val token: String = "",
    @SerializedName("max_guests") val maxGuests: Int = 5,
)

data class GuestEventPayload(
    @SerializedName("guest_id") val guestId: String = "",
)

/**
 * 画笔标注指令载荷。坐标均为归一化 [0,1]，相对"共享画面内容"。
 * 与 Go 服务端透传字段保持蛇形命名（guest_id）。
 */
data class DrawPayload(
    @SerializedName("id") val id: String = "",
    @SerializedName("op") val op: String = "",       // 见 DrawOp
    @SerializedName("tool") val tool: String = "",   // 见 DrawTool
    @SerializedName("x") val x: Float = 0f,           // 当前点/圆心/起点
    @SerializedName("y") val y: Float = 0f,
    @SerializedName("x2") val x2: Float = 0f,         // 箭头终点/圈边缘点（可选）
    @SerializedName("y2") val y2: Float = 0f,
    @SerializedName("color") val color: String = "#FF3B30",
    @SerializedName("guest_id") val guestId: String = "",
    @SerializedName("ts") val ts: Long = 0L,
)

/** 画笔操作 */
object DrawOp {
    const val BEGIN = "begin"   // 一笔/一个图形开始
    const val POINT = "point"   // 拖动中的中间点（追加/更新）
    const val END = "end"       // 结束（开始淡出计时）
    const val TAP = "tap"       // 单击（波纹）
    const val CLEAR = "clear"   // 清空全部
}

/** 画笔工具 */
object DrawTool {
    const val LASER = "laser"    // 实时指针，不留痕
    const val PEN = "pen"        // 自由画笔
    const val CIRCLE = "circle"  // 圆圈/圈选
    const val ARROW = "arrow"    // 箭头
    const val RIPPLE = "ripple"  // 点击波纹
}

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
    const val GUEST_JOIN = "guest_join"
    const val GUEST_LEAVE = "guest_leave"
    const val KICK_GUEST = "kick_guest"
    const val DRAW_COMMAND = "draw_command"
}
