package to.feng.app.easyscreen.update

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 从信令服务器拉取最新版本元数据。
 * @param wsServerUrl 设置里保存的 wss://host/ws 地址
 */
class UpdateRepository(
    private val client: OkHttpClient = defaultClient,
    private val gson: Gson = Gson(),
) {
    suspend fun fetchLatest(wsServerUrl: String): Result<AppVersionInfo> = withContext(Dispatchers.IO) {
        val base = deriveHttpBase(wsServerUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("无法解析服务器地址"))
        val url = "$base/app/version.json"
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("HTTP ${resp.code}"))
                }
                val body = resp.body?.string()
                    ?: return@withContext Result.failure(RuntimeException("空响应"))
                val info = gson.fromJson(body, AppVersionInfo::class.java)
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 把相对 downloadUrl 拼成绝对地址；若已是绝对地址则原样返回。 */
    fun resolveDownloadUrl(wsServerUrl: String, info: AppVersionInfo): String? {
        if (info.downloadUrl.startsWith("http://") || info.downloadUrl.startsWith("https://")) {
            return info.downloadUrl
        }
        val base = deriveHttpBase(wsServerUrl) ?: return null
        return base + info.downloadUrl
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
