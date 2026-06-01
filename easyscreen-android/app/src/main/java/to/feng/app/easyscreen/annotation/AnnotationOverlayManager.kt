package to.feng.app.easyscreen.annotation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload

/**
 * 老人端系统浮窗管理器（单例）。
 *
 * 关键：浮窗用 TYPE_APPLICATION_OVERLAY 覆盖任意 App，FLAG_NOT_TOUCHABLE 触摸穿透，
 * 老人照常操作浮窗下方的真实按钮。所有方法须在主线程调用。
 */
object AnnotationOverlayManager {
    private const val TAG = "AnnotationOverlay"

    private var windowManager: WindowManager? = null
    private var view: AnnotationOverlayView? = null
    private var added = false

    /** 是否已授予系统浮窗权限。 */
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 收到一条标注指令：懒加载浮窗并提交。无权限则忽略（由 UI 负责提示授权）。 */
    fun submit(context: Context, payload: DrawPayload) {
        val appCtx = context.applicationContext
        if (!hasPermission(appCtx)) {
            Log.w(TAG, "无浮窗权限，丢弃 draw_command")
            return
        }
        ensureAdded(appCtx)
        if (payload.op == DrawOp.CLEAR) {
            view?.clearAll()
        } else {
            view?.submit(payload)
        }
    }

    /** Guest 远程切换浮窗可绘制边框（诊断用），无需依赖是否有标注。 */
    fun setDebugBounds(context: Context, on: Boolean) {
        AnnotationOverlayView.DEBUG_BOUNDS = on
        val appCtx = context.applicationContext
        if (on) {
            if (!hasPermission(appCtx)) return
            ensureAdded(appCtx)
        }
        view?.postInvalidate()
    }

    private fun ensureAdded(appCtx: Context) {
        if (added && view != null) return
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val v = AnnotationOverlayView(appCtx)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(v, params)
            windowManager = wm
            view = v
            added = true
            Log.d(TAG, "浮窗已添加")
        } catch (e: Exception) {
            Log.e(TAG, "addView 失败", e)
        }
    }

    /** 停止共享/退出时移除浮窗。 */
    fun hide() {
        val wm = windowManager
        val v = view
        if (added && wm != null && v != null) {
            try { wm.removeView(v) } catch (e: Exception) { Log.w(TAG, "removeView 失败", e) }
        }
        view = null
        added = false
    }
}
