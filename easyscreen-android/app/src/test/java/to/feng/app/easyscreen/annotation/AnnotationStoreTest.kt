package to.feng.app.easyscreen.annotation

import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStoreTest {

    private fun circleBegin(id: String, t: Long) = DrawPayload(
        id = id, op = DrawOp.BEGIN, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f, ts = t,
    )

    @Test
    fun activeAnnotation_isFullyVisible() {
        val store = AnnotationStore(holdMs = 5000, fadeMs = 800)
        store.apply(circleBegin("c1", 0), nowMs = 0)
        val snap = store.snapshot(nowMs = 100)
        assertEquals(1, snap.size)
        assertEquals(1f, snap[0].alpha, 0.001f)
    }

    @Test
    fun penAppendsPoints() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "p", op = DrawOp.BEGIN, tool = DrawTool.PEN, x = 0.1f, y = 0.1f), 0)
        store.apply(DrawPayload(id = "p", op = DrawOp.POINT, tool = DrawTool.PEN, x = 0.2f, y = 0.2f), 10)
        store.apply(DrawPayload(id = "p", op = DrawOp.POINT, tool = DrawTool.PEN, x = 0.3f, y = 0.3f), 20)
        val snap = store.snapshot(30)
        assertEquals(3, snap[0].points.size)
    }

    @Test
    fun finishedAnnotation_holdsThenFadesThenExpires() {
        val store = AnnotationStore(holdMs = 1000, fadeMs = 500)
        store.apply(DrawPayload(id = "c", op = DrawOp.BEGIN, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f), 0)
        store.apply(DrawPayload(id = "c", op = DrawOp.END, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f, x2 = 0.6f, y2 = 0.5f), 0)

        assertEquals(1f, store.snapshot(500)[0].alpha, 0.001f)
        assertEquals(0.5f, store.snapshot(1250)[0].alpha, 0.05f)
        assertTrue(store.snapshot(1600).isEmpty())
    }

    @Test
    fun circleEnd_storesCenterAndEdge() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "c", op = DrawOp.BEGIN, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f), 0)
        store.apply(DrawPayload(id = "c", op = DrawOp.END, tool = DrawTool.CIRCLE, x = 0.5f, y = 0.5f, x2 = 0.7f, y2 = 0.5f), 0)
        val pts = store.snapshot(10)[0].points
        assertEquals(2, pts.size)
        assertEquals(0.5f, pts[0].x, 0.001f)
        assertEquals(0.7f, pts[1].x, 0.001f)
    }

    @Test
    fun rippleTap_isFinishedImmediately() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "r", op = DrawOp.TAP, tool = DrawTool.RIPPLE, x = 0.3f, y = 0.4f), 0)
        val snap = store.snapshot(50)
        assertEquals(1, snap.size)
        assertEquals(1, snap[0].points.size)
        assertTrue(snap[0].ageMs >= 0)
    }

    @Test
    fun ripple_expiresAfterShortAnimation() {
        val store = AnnotationStore()
        store.apply(DrawPayload(id = "r", op = DrawOp.TAP, tool = DrawTool.RIPPLE, x = 0.3f, y = 0.4f), 0)
        assertTrue(store.snapshot(1000).isEmpty())
    }

    @Test
    fun clear_removesEverything() {
        val store = AnnotationStore()
        store.apply(circleBegin("c1", 0), 0)
        store.apply(circleBegin("c2", 0), 0)
        store.apply(DrawPayload(op = DrawOp.CLEAR), 5)
        assertTrue(store.snapshot(10).isEmpty())
    }

    @Test
    fun laserEnd_fadesQuickly() {
        val store = AnnotationStore(holdMs = 5000, fadeMs = 800)
        store.apply(DrawPayload(id = "l", op = DrawOp.BEGIN, tool = DrawTool.LASER, x = 0.5f, y = 0.5f), 0)
        store.apply(DrawPayload(id = "l", op = DrawOp.END, tool = DrawTool.LASER, x = 0.5f, y = 0.5f), 0)
        assertTrue(store.snapshot(500).isEmpty())
    }
}
