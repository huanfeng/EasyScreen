package to.feng.app.easyscreen.annotation

import to.feng.app.easyscreen.data.DrawOp
import to.feng.app.easyscreen.data.DrawPayload
import to.feng.app.easyscreen.data.DrawTool

/**
 * 标注状态机：应用 DrawPayload，按时间淡出/过期，输出渲染快照。
 *
 * 纯逻辑——时间由调用方传入（nowMs），便于单测，也便于渲染循环驱动。
 * 线程约束：apply / snapshot 应在同一线程（渲染线程）调用。
 *
 * @param holdMs 留痕类标注结束后的保持时长
 * @param fadeMs 保持后的淡出时长
 */
class AnnotationStore(
    private val holdMs: Long = 5000L,
    private val fadeMs: Long = 800L,
) {
    private companion object {
        const val LASER_FADE_MS = 400L   // 指针松手后快速消失
        const val RIPPLE_LIFE_MS = 600L  // 波纹动画总时长
    }

    private class Item(
        val id: String,
        val tool: String,
        val color: String,
        val points: MutableList<NPoint>,
        var finishedAt: Long?,   // null=仍在绘制
    )

    private val items = LinkedHashMap<String, Item>()

    fun apply(p: DrawPayload, nowMs: Long) {
        when (p.op) {
            DrawOp.CLEAR -> items.clear()

            DrawOp.TAP -> {
                items[p.id.ifEmpty { "tap-$nowMs" }] = Item(
                    id = p.id, tool = p.tool, color = p.color,
                    points = mutableListOf(NPoint(p.x, p.y)),
                    finishedAt = nowMs,
                )
            }

            DrawOp.BEGIN -> {
                items[p.id] = Item(
                    id = p.id, tool = p.tool, color = p.color,
                    points = mutableListOf(NPoint(p.x, p.y)),
                    finishedAt = null,
                )
            }

            DrawOp.POINT -> {
                val it = items[p.id] ?: return
                when (p.tool) {
                    DrawTool.PEN -> it.points.add(NPoint(p.x, p.y))
                    DrawTool.LASER -> {
                        it.points.clear()
                        it.points.add(NPoint(p.x, p.y))
                    }
                    DrawTool.CIRCLE, DrawTool.ARROW -> {
                        if (it.points.size < 2) it.points.add(NPoint(p.x, p.y))
                        else it.points[1] = NPoint(p.x, p.y)
                    }
                }
            }

            DrawOp.END -> {
                val it = items[p.id] ?: return
                if (p.tool == DrawTool.CIRCLE || p.tool == DrawTool.ARROW) {
                    val edge = NPoint(p.x2, p.y2).takeIf { p.x2 != 0f || p.y2 != 0f }
                        ?: NPoint(p.x, p.y)
                    if (it.points.size < 2) it.points.add(edge) else it.points[1] = edge
                }
                it.finishedAt = nowMs
            }
        }
    }

    fun clear() = items.clear()

    fun isEmpty(): Boolean = items.isEmpty()

    /** 返回当前可见标注，并就地剔除已过期项。 */
    fun snapshot(nowMs: Long): List<RenderAnnotation> {
        val out = ArrayList<RenderAnnotation>(items.size)
        val expired = ArrayList<String>()
        for ((key, it) in items) {
            val finishedAt = it.finishedAt
            if (finishedAt == null) {
                out.add(RenderAnnotation(it.id, it.tool, it.color, it.points.toList(), 1f, 0L))
                continue
            }
            val age = nowMs - finishedAt
            val (life, alpha) = lifeAndAlpha(it.tool, age)
            if (age >= life) { expired.add(key); continue }
            out.add(RenderAnnotation(it.id, it.tool, it.color, it.points.toList(), alpha, age))
        }
        for (k in expired) items.remove(k)
        return out
    }

    /** 返回 (总寿命, 当前 alpha)；age>=总寿命表示过期。 */
    private fun lifeAndAlpha(tool: String, age: Long): Pair<Long, Float> {
        return when (tool) {
            DrawTool.LASER -> LASER_FADE_MS to (1f - age.toFloat() / LASER_FADE_MS).coerceIn(0f, 1f)
            DrawTool.RIPPLE -> RIPPLE_LIFE_MS to (1f - age.toFloat() / RIPPLE_LIFE_MS).coerceIn(0f, 1f)
            else -> {
                val total = holdMs + fadeMs
                val alpha = if (age <= holdMs) 1f
                else (1f - (age - holdMs).toFloat() / fadeMs).coerceIn(0f, 1f)
                total to alpha
            }
        }
    }
}
