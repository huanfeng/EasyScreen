package to.feng.app.easyscreen.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载 APK 到 externalCacheDir/update/，带进度回调，下载后做 sha256 校验。
 */
class ApkDownloader(
    private val client: OkHttpClient = defaultClient,
) {
    /**
     * @param onProgress 0..100；总长未知时回调 -1
     * @return 成功返回已校验的 APK File；失败返回 Result.failure
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSha256: String,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.externalCacheDir, "update").apply { mkdirs() }
        val target = File(dir, "latest.apk")
        val tmp = File(dir, "latest.apk.tmp")
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("HTTP ${resp.code}"))
                }
                val body = resp.body ?: return@withContext Result.failure(RuntimeException("空响应"))
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(((read * 100) / total).toInt()) else onProgress(-1)
                        }
                    }
                }
            }
            // 校验
            val actual = Sha256.ofFile(tmp)
            if (expectedSha256.isNotEmpty() && !actual.equals(expectedSha256, ignoreCase = true)) {
                tmp.delete()
                return@withContext Result.failure(RuntimeException("文件校验失败，请重试"))
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext Result.failure(RuntimeException("保存失败"))
            }
            onProgress(100)
            Result.success(target)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
