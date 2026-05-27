package to.feng.app.easyscreen.data

import android.content.Context
import java.util.UUID

/**
 * 持久化 Host token：用于断后重连时让服务端复用同一 6 位房号。
 * 仅本地存储，用户可通过"重置连接码"清掉以提升安全性（防止他人重连旧房号）。
 */
object HostTokenPrefs {
    private const val PREFS_NAME = "easyscreen_prefs"
    private const val KEY_HOST_TOKEN = "host_token"

    /**
     * 取已有 token；若没有就生成并落盘。
     */
    fun getOrCreate(context: Context): String {
        val sp = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.getString(KEY_HOST_TOKEN, null)?.let { return it }
        val newToken = UUID.randomUUID().toString()
        sp.edit().putString(KEY_HOST_TOKEN, newToken).apply()
        return newToken
    }

    /**
     * 重置 token：下次再 register 时服务端会视为全新设备，分配新房号。
     */
    fun reset(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HOST_TOKEN).apply()
    }
}
