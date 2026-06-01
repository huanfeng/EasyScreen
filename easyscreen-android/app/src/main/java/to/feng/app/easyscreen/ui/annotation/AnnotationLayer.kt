@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package to.feng.app.easyscreen.ui.annotation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import to.feng.app.easyscreen.annotation.AnnotationStore
import to.feng.app.easyscreen.annotation.CoordinateMapping
import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 子女端可选颜色 */
private val PALETTE = listOf("#FF3B30", "#FFCC00", "#34C759", "#0A84FF")

/**
 * 子女端标注层：盖在视频上，捕获触摸→归一化→onSend 发送 + 本地回显。
 *
 * @param srcW/srcH 远端源画面有效尺寸（来自 WebRTCManager.SourceSize.effectiveSize()）
 * @param fillCover renderer 是否 cover（充满裁切）模式
 * @param onSend 发送一条 DrawPayload（→ signalingClient）
 * @param onExit 退出标注模式
 */
@Composable
fun AnnotationLayer(
    srcW: Int,
    srcH: Int,
    fillCover: Boolean,
    onSend: (DrawPayload) -> Unit,
    onExit: () -> Unit,
) {
    var tool by remember { mutableStateOf(DrawTool.CIRCLE) }
    var colorHex by remember { mutableStateOf(PALETTE[0]) }
    var boundsOn by remember { mutableStateOf(false) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val store = remember { AnnotationStore() }
    var frameTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            frameTick = SystemClock.uptimeMillis()
            kotlinx.coroutines.delay(33)
        }
    }

    fun norm(o: Offset): Pair<Float, Float>? = CoordinateMapping.touchToNormalized(
        o.x, o.y, stageSize.width.toFloat(), stageSize.height.toFloat(), srcW, srcH, fillCover,
    )

    fun send(op: String, t: String, nx: Float, ny: Float, id: String, nx2: Float = 0f, ny2: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val p = DrawPayload(
            id = id, op = op, tool = t, x = nx, y = ny, x2 = nx2, y2 = ny2,
            color = colorHex, ts = now,
        )
        store.apply(p, now)   // 本地回显
        onSend(p)             // 发往老人端
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { stageSize = it }
            .pointerInput(tool, colorHex, srcW, srcH, fillCover) {
                if (tool == DrawTool.RIPPLE) {
                    detectTapGestures(onTap = { o ->
                        norm(o)?.let { (nx, ny) ->
                            send(DrawOp.TAP, DrawTool.RIPPLE, nx, ny, UUID.randomUUID().toString().take(8))
                        }
                    })
                } else {
                    var id = ""
                    var lastSent = 0L
                    var startN: Pair<Float, Float>? = null
                    var lastN: Pair<Float, Float>? = null
                    detectDragGestures(
                        onDragStart = { o ->
                            val n = norm(o)
                            if (n != null) {
                                id = UUID.randomUUID().toString().take(8)
                                startN = n
                                send(DrawOp.BEGIN, tool, n.first, n.second, id)
                            }
                        },
                        onDrag = { change, _ ->
                            if (id.isNotEmpty()) {
                                val n = norm(change.position)
                                if (n != null) {
                                    lastN = n
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastSent >= 60) {
                                        lastSent = now
                                        send(DrawOp.POINT, tool, n.first, n.second, id)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (id.isNotEmpty()) {
                                send(
                                    DrawOp.END, tool,
                                    startN?.first ?: 0f, startN?.second ?: 0f, id,
                                    lastN?.first ?: startN?.first ?: 0f,
                                    lastN?.second ?: startN?.second ?: 0f,
                                )
                                id = ""
                            }
                        },
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frameTick
            val now = SystemClock.uptimeMillis()
            for (a in store.snapshot(now)) {
                val col = parseComposeColor(a.color).copy(alpha = a.alpha)
                fun px(nx: Float, ny: Float) = Offset(nx * size.width, ny * size.height)
                when (a.tool) {
                    DrawTool.PEN, DrawTool.LASER -> {
                        if (a.tool == DrawTool.LASER) {
                            a.points.lastOrNull()?.let {
                                drawCircle(col.copy(alpha = a.alpha * 0.25f), 22.dp.toPx(), px(it.x, it.y))
                                drawCircle(col, 9.dp.toPx(), px(it.x, it.y))
                            }
                        } else if (a.points.size >= 2) {
                            for (i in 1 until a.points.size) {
                                drawLine(col, px(a.points[i-1].x, a.points[i-1].y),
                                    px(a.points[i].x, a.points[i].y),
                                    strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                            }
                        }
                    }
                    DrawTool.CIRCLE -> if (a.points.isNotEmpty()) {
                        val c = px(a.points[0].x, a.points[0].y)
                        val r = if (a.points.size >= 2) {
                            val e = px(a.points[1].x, a.points[1].y)
                            hypot((e.x - c.x).toDouble(), (e.y - c.y).toDouble()).toFloat()
                        } else 60.dp.toPx()
                        drawCircle(col, r.coerceAtLeast(8.dp.toPx()), c, style = Stroke(width = 5.dp.toPx()))
                    }
                    DrawTool.ARROW -> if (a.points.size >= 2) {
                        val s = px(a.points[0].x, a.points[0].y)
                        val e = px(a.points[1].x, a.points[1].y)
                        drawLine(col, s, e, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                        val ang = atan2((e.y - s.y).toDouble(), (e.x - s.x).toDouble())
                        val head = 22.dp.toPx(); val spread = Math.toRadians(28.0)
                        for (sgn in intArrayOf(-1, 1)) {
                            val a2 = ang + sgn * spread
                            drawLine(col, e, Offset(e.x - (head*cos(a2)).toFloat(), e.y - (head*sin(a2)).toFloat()),
                                strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    DrawTool.RIPPLE -> if (a.points.isNotEmpty()) {
                        val c = px(a.points[0].x, a.points[0].y)
                        val t = (a.ageMs.toFloat() / 600f).coerceIn(0f, 1f)
                        drawCircle(col.copy(alpha = 1f - t), 12.dp.toPx() + 48.dp.toPx() * t, c,
                            style = Stroke(width = 4.dp.toPx()))
                        drawCircle(col.copy(alpha = 1f - t), 8.dp.toPx(), c)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("○", tool == DrawTool.CIRCLE) { tool = DrawTool.CIRCLE }
            ToolButton("→", tool == DrawTool.ARROW) { tool = DrawTool.ARROW }
            ToolButton("✎", tool == DrawTool.PEN) { tool = DrawTool.PEN }
            ToolButton("·", tool == DrawTool.RIPPLE) { tool = DrawTool.RIPPLE }
            ToolButton("◉", tool == DrawTool.LASER) { tool = DrawTool.LASER }
            Spacer(Modifier.width(8.dp))
            for (hex in PALETTE) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(parseComposeColor(hex))
                        .border(
                            width = if (hex == colorHex) 3.dp else 0.dp,
                            color = Color.White, shape = CircleShape,
                        )
                        .clickable { colorHex = hex },
                )
            }
            Spacer(Modifier.width(8.dp))
            // 边框诊断开关：远程开/关老人端浮窗可绘制边框（与 Web 端一致，用复选符号表示）
            ToolButton(if (boundsOn) "☑框" else "☐框", false) {
                boundsOn = !boundsOn
                onSend(DrawPayload(
                    op = if (boundsOn) DrawOp.BOUNDS_ON else DrawOp.BOUNDS_OFF,
                    color = colorHex, ts = SystemClock.uptimeMillis(),
                ))
            }
            ToolButton("清空", false) {
                store.clear()
                onSend(DrawPayload(op = DrawOp.CLEAR, ts = SystemClock.uptimeMillis()))
            }
            ToolButton("✕", false) { onExit() }
        }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White.copy(alpha = 0.35f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White)
    }
}

private fun parseComposeColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color.Red
}
