package to.feng.app.easyscreen.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateModelsTest {
    private fun info(code: Int, force: Boolean = false, minSupported: Int = 1) =
        AppVersionInfo(
            versionCode = code, versionName = "x", sha256 = "", fileSize = 0,
            forceUpdate = force, minSupportedVersionCode = minSupported,
            releaseNotes = "", downloadUrl = "/app/download",
        )

    @Test fun sameOrOlderIsNone() {
        assertEquals(UpdateKind.NONE, decideUpdate(current = 10, remote = info(10)))
        assertEquals(UpdateKind.NONE, decideUpdate(current = 10, remote = info(9)))
    }

    @Test fun newerIsOptional() {
        assertEquals(UpdateKind.OPTIONAL, decideUpdate(current = 10, remote = info(11)))
    }

    @Test fun forceFlagMakesForced() {
        assertEquals(UpdateKind.FORCED, decideUpdate(current = 10, remote = info(11, force = true)))
    }

    @Test fun belowMinSupportedIsForced() {
        assertEquals(
            UpdateKind.FORCED,
            decideUpdate(current = 10, remote = info(11, minSupported = 11)),
        )
    }
}
