package to.feng.app.easyscreen.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawPayloadSerializationTest {
    private val gson = Gson()

    @Test
    fun drawPayload_serializesWithSnakeCaseGuestId() {
        val p = DrawPayload(
            id = "abc123", op = DrawOp.BEGIN, tool = DrawTool.CIRCLE,
            x = 0.5f, y = 0.25f, x2 = 0.6f, y2 = 0.3f,
            color = "#FF3B30", guestId = "g1", ts = 100L,
        )
        val json = gson.toJson(p)
        assert(json.contains("\"guest_id\":\"g1\"")) { "actual: $json" }
        assert(json.contains("\"tool\":\"circle\"")) { "actual: $json" }
    }

    @Test
    fun signalingMessage_withDrawPayload_roundTrips() {
        val msg = SignalingMessage(
            type = MessageType.DRAW_COMMAND,
            payload = DrawPayload(id = "x", op = DrawOp.TAP, tool = DrawTool.RIPPLE, x = 0.1f, y = 0.2f),
        )
        val json = gson.toJson(msg)
        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals(MessageType.DRAW_COMMAND, parsed.type)
        val payload = gson.fromJson(gson.toJson(parsed.payload), DrawPayload::class.java)
        assertEquals("x", payload.id)
        assertEquals(DrawOp.TAP, payload.op)
        assertEquals(DrawTool.RIPPLE, payload.tool)
        assertEquals(0.1f, payload.x, 0.0001f)
    }
}
