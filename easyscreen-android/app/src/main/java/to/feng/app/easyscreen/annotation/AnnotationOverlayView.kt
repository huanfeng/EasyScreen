package to.feng.app.easyscreen.annotation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 老人端系统浮窗的绘制 View。维护 AnnotationStore，按动画帧重绘并淡出。
 * 触摸穿透由 WindowManager 的 FLAG_NOT_TOUCHABLE 保证，本 View 不处理触摸。
 */
@SuppressLint("ViewConstructor")
class AnnotationOverlayView(context: Context) : View(context) {

    private val store = AnnotationStore()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    private val locOnScreen = IntArray(2)
    private val realMetrics = DisplayMetrics()
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(200, 0, 230, 0)
    }

    companion object {
        /** 画出浮窗实际可绘制边框（默认关，由 Guest 端远程控制开关，用于诊断覆盖范围）。 */
        var DEBUG_BOUNDS = false
    }

    /** 由浮窗管理器在收到 draw_command 时调用。 */
    fun submit(payload: DrawPayload) {
        store.apply(payload, SystemClock.uptimeMillis())
        scheduleFrame()
    }

    fun clearAll() {
        store.clear()
        invalidate()
    }

    private fun scheduleFrame() {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (DEBUG_BOUNDS) {
            // 画出浮窗实际可绘制边框：在老人端被采集回传后，可直观看出它够不到状态栏/导航栏
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, debugPaint)
        }
        val now = SystemClock.uptimeMillis()
        val annotations = store.snapshot(now)
        for (a in annotations) {
            val color = parseColor(a.color)
            when (a.tool) {
                DrawTool.PEN, DrawTool.LASER -> drawPenOrLaser(canvas, a, color)
                DrawTool.CIRCLE -> drawCircle(canvas, a, color)
                DrawTool.RECT -> drawRect(canvas, a, color)
                DrawTool.ARROW -> drawArrow(canvas, a, color)
                DrawTool.RIPPLE -> drawRipple(canvas, a, color)
            }
        }
        if (annotations.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 归一化 [0,1] → 本浮窗内像素。
     *
     * 关键修正：[0,1] 相对的是"全屏采集画面"，但本系统浮窗画不到状态栏/导航栏之上，
     * 其可绘制区域比采集画面小、且被系统下移内缩。若直接按浮窗自身尺寸等比映射，
     * 标注会被整体收缩（点顶偏下、点底偏上）。
     * 正确做法：映射到真实全屏尺寸得到屏幕坐标，再减去浮窗在屏幕上的偏移
     * （getLocationOnScreen），换算为 View 内坐标；落在状态栏区域的会被自然裁掉。
     */
    private fun px(nx: Float, ny: Float): Pair<Float, Float> {
        val d = display
        if (d != null) {
            @Suppress("DEPRECATION")
            d.getRealMetrics(realMetrics)
            val fw = realMetrics.widthPixels
            val fh = realMetrics.heightPixels
            if (fw > 0 && fh > 0) {
                getLocationOnScreen(locOnScreen)
                return (nx * fw - locOnScreen[0]) to (ny * fh - locOnScreen[1])
            }
        }
        // 兜底：display 不可用时退回旧映射
        return CoordinateMapping.normalizedToPixel(nx, ny, width, height)
    }

    private fun drawPenOrLaser(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        if (a.tool == DrawTool.LASER) {
            val (cx, cy) = px(a.points.last().x, a.points.last().y)
            fillPaint.color = withAlpha(color, a.alpha * 0.25f)
            canvas.drawCircle(cx, cy, dp(22f), fillPaint)
            fillPaint.color = withAlpha(color, a.alpha)
            canvas.drawCircle(cx, cy, dp(9f), fillPaint)
            return
        }
        path.reset()
        val first = px(a.points[0].x, a.points[0].y)
        path.moveTo(first.first, first.second)
        for (i in 1 until a.points.size) {
            val (x, y) = px(a.points[i].x, a.points[i].y)
            path.lineTo(x, y)
        }
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(5f)
        canvas.drawPath(path, strokePaint)
    }

    private fun drawCircle(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        val (cx, cy) = px(a.points[0].x, a.points[0].y)
        val r = if (a.points.size >= 2) {
            val (ex, ey) = px(a.points[1].x, a.points[1].y)
            hypot((ex - cx).toDouble(), (ey - cy).toDouble()).toFloat()
        } else dp(6f)   // 未拖动时默认最小，避免先出大圈再跳变
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(3f)
        canvas.drawCircle(cx, cy, r.coerceAtLeast(dp(3f)), strokePaint)
    }

    private fun drawRect(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        val (sx, sy) = px(a.points[0].x, a.points[0].y)
        val (ex, ey) = if (a.points.size >= 2) px(a.points[1].x, a.points[1].y)
                       else (sx + dp(6f)) to (sy + dp(6f))
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(3f)
        canvas.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), strokePaint)
    }

    private fun drawArrow(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.size < 2) return
        val (sx, sy) = px(a.points[0].x, a.points[0].y)
        val (ex, ey) = px(a.points[1].x, a.points[1].y)
        strokePaint.color = withAlpha(color, a.alpha)
        strokePaint.strokeWidth = dp(5f)
        canvas.drawLine(sx, sy, ex, ey, strokePaint)
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val head = dp(22f)
        val spread = Math.toRadians(28.0)
        for (s in intArrayOf(-1, 1)) {
            val a2 = angle + s * spread
            canvas.drawLine(
                ex, ey,
                ex - (head * cos(a2)).toFloat(),
                ey - (head * sin(a2)).toFloat(),
                strokePaint,
            )
        }
    }

    private fun drawRipple(canvas: Canvas, a: RenderAnnotation, color: Int) {
        if (a.points.isEmpty()) return
        val (cx, cy) = px(a.points[0].x, a.points[0].y)
        val t = (a.ageMs.toFloat() / 600f).coerceIn(0f, 1f)
        val r = dp(12f) + dp(48f) * t
        strokePaint.color = withAlpha(color, (1f - t))
        strokePaint.strokeWidth = dp(4f)
        canvas.drawCircle(cx, cy, r, strokePaint)
        fillPaint.color = withAlpha(color, (1f - t))
        canvas.drawCircle(cx, cy, dp(8f), fillPaint)
    }

    private fun parseColor(s: String): Int = try {
        Color.parseColor(s)
    } catch (e: Exception) {
        Log.w("AnnotationOverlay", "bad color $s")
        Color.RED
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
