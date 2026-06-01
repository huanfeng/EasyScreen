package to.feng.app.easyscreen.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlUtilTest {
    @Test fun wssNoPort() {
        assertEquals("https://a.com", deriveHttpBase("wss://a.com/ws"))
    }
    @Test fun wsWithPort() {
        assertEquals("http://a.com:8081", deriveHttpBase("ws://a.com:8081/ws"))
    }
    @Test fun wssWithPort() {
        assertEquals("https://a.com:443", deriveHttpBase("wss://a.com:443/ws"))
    }
    @Test fun trimsWhitespace() {
        assertEquals("https://a.com", deriveHttpBase("  wss://a.com/ws  "))
    }
    @Test fun invalidReturnsNull() {
        assertNull(deriveHttpBase("not a url"))
        assertNull(deriveHttpBase(""))
    }
}
