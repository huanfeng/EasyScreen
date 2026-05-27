package to.feng.app.easyscreen.data

import android.content.Context

/**
 * 记忆 Guest 端最后一次成功输入的 6 位连接码，下次打开自动回填。
 */
object GuestPrefs {
    private const val PREFS_NAME = "easyscreen_prefs"
    private const val KEY_LAST_CODE = "guest_last_code"

    fun getLastCode(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_LAST_CODE, "") ?: ""
    }

    fun setLastCode(context: Context, code: String) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_LAST_CODE, code).apply()
    }
}
