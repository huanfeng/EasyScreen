package to.feng.app.easyscreen.data

import android.content.Context

/**
 * 画质档位：分辨率 / 帧率 / 码率上下限。
 * 通过 SharedPreferences 持久化用户选择。
 */
data class QualityPreset(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** 码率天花板（BWE 不会超过此值） */
    val maxBitrateBps: Int,
    /** 码率地板（BWE 在网络恢复后能稳住的最低码率，防止"糊掉不恢复"） */
    val minBitrateBps: Int,
)

object QualityPrefs {
    private const val PREFS_NAME = "easyscreen_prefs"
    private const val KEY_QUALITY = "quality_id"

    val SMOOTH = QualityPreset(
        id = "smooth", label = "流畅",
        width = 854, height = 480, fps = 15,
        maxBitrateBps = 800_000, minBitrateBps = 250_000,
    )
    val STANDARD = QualityPreset(
        id = "standard", label = "标准",
        width = 1280, height = 720, fps = 20,
        maxBitrateBps = 2_000_000, minBitrateBps = 600_000,
    )
    val HD = QualityPreset(
        id = "hd", label = "高清",
        width = 1920, height = 1080, fps = 25,
        maxBitrateBps = 4_000_000, minBitrateBps = 1_200_000,
    )

    val PRESETS = listOf(SMOOTH, STANDARD, HD)
    val DEFAULT: QualityPreset get() = STANDARD

    fun get(context: Context): QualityPreset {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = sp.getString(KEY_QUALITY, DEFAULT.id) ?: DEFAULT.id
        return PRESETS.firstOrNull { it.id == id } ?: DEFAULT
    }

    fun set(context: Context, preset: QualityPreset) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_QUALITY, preset.id).apply()
    }
}
