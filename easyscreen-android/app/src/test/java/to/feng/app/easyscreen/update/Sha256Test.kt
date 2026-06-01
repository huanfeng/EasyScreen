package to.feng.app.easyscreen.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class Sha256Test {
    @Test fun knownVectorBytes() {
        // sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.ofBytes("abc".toByteArray()),
        )
    }

    @Test fun fileMatchesBytes() {
        val tmp = File.createTempFile("sha", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes("hello-world".toByteArray())
        assertEquals(Sha256.ofBytes("hello-world".toByteArray()), Sha256.ofFile(tmp))
    }
}
