package to.feng.app.easyscreen.data

import android.content.Context
import to.feng.app.easyscreen.BuildConfig

/**
 * 持久化服务器地址。SharedPreferences 简单封装。
 *
 * 默认值来源（不入仓库）：
 *   1. 环境变量 EASYSCREEN_DEFAULT_SERVER_URL（构建时注入到 BuildConfig）
 *   2. 项目根 local.properties 里的 easyscreen.defaultServerUrl
 *   3. 兜底占位符 ws://example.com:8081/ws —— 用户必须在设置里改成真实地址
 */
object ServerPrefs {
    private const val PREFS_NAME = "easyscreen_prefs"
    private const val KEY_SERVER_URL = "server_url"

    /** 默认地址 + 可选预设；自定义请在设置页输入框直接修改 */
    val PRESETS: List<Pair<String, String>>
        get() = listOf(BuildConfig.DEFAULT_SERVER_URL to "默认服务器")

    val DEFAULT_URL: String get() = BuildConfig.DEFAULT_SERVER_URL

    fun get(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun set(context: Context, url: String) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_SERVER_URL, url).apply()
    }
}
