package to.feng.app.easyscreen.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateMappingTest {

    @Test
    fun touchToNormalized_sameAspect_center() {
        val r = CoordinateMapping.touchToNormalized(
            touchX = 540f, touchY = 1170f,
            stageW = 1080f, stageH = 2340f,
            srcW = 1080, srcH = 2340, fillCover = false,
        )!!
        assertEquals(0.5f, r.first, 0.001f)
        assertEquals(0.5f, r.second, 0.001f)
    }

    @Test
    fun touchToNormalized_containLetterbox_outsideContentReturnsNull() {
        // 源 1000x500（2:1），舞台 1000x1000 → 内容高 500，上下各 250 黑边
        val top = CoordinateMapping.touchToNormalized(
            touchX = 500f, touchY = 100f,
            stageW = 1000f, stageH = 1000f,
            srcW = 1000, srcH = 500, fillCover = false,
        )
        assertNull(top)
    }

    @Test
    fun touchToNormalized_containLetterbox_insideMapsCorrectly() {
        val r = CoordinateMapping.touchToNormalized(
            touchX = 500f, touchY = 500f,
            stageW = 1000f, stageH = 1000f,
            srcW = 1000, srcH = 500, fillCover = false,
        )!!
        assertEquals(0.5f, r.first, 0.001f)
        assertEquals(0.5f, r.second, 0.001f)
    }

    @Test
    fun touchToNormalized_cover_clampsToUnitRange() {
        val r = CoordinateMapping.touchToNormalized(
            touchX = 0f, touchY = 0f,
            stageW = 1000f, stageH = 1000f,
            srcW = 1000, srcH = 500, fillCover = true,
        )!!
        assert(r.first in 0f..1f)
        assert(r.second in 0f..1f)
    }

    @Test
    fun normalizedToPixel_mapsToFullView() {
        val p = CoordinateMapping.normalizedToPixel(0.5f, 0.25f, viewW = 1080, viewH = 2400)
        assertEquals(540f, p.first, 0.001f)
        assertEquals(600f, p.second, 0.001f)
    }

    @Test
    fun touchToNormalized_invalidSource_returnsNull() {
        assertNull(
            CoordinateMapping.touchToNormalized(10f, 10f, 100f, 100f, 0, 0, false)
        )
    }
}
