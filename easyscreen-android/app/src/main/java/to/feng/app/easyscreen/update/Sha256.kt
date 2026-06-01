package to.feng.app.easyscreen.update

import java.io.File
import java.security.MessageDigest

object Sha256 {
    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
