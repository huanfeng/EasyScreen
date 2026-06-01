package to.feng.app.easyscreen.update

import android.content.Context

/**
 * 更新相关偏好：记录用户「跳过的版本」，避免普通更新每次启动都弹窗。
 * 复用项目统一的 easyscreen_prefs 文件。
 */
object UpdatePrefs {
    private const val PREFS_NAME = "easyscreen_prefs"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version_code"

    fun getSkippedVersion(context: Context): Int {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_SKIPPED_VERSION, 0)
    }

    fun setSkippedVersion(context: Context, versionCode: Int) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_SKIPPED_VERSION, versionCode).apply()
    }
}
