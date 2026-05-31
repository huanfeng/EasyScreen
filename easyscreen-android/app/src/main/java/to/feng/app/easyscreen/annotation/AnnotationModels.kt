package to.feng.app.easyscreen.annotation

/** 归一化点 [0,1]（不依赖 android.graphics，便于纯 JVM 测试）。 */
data class NPoint(val x: Float, val y: Float)

/**
 * 可渲染的标注快照。
 * @param points 归一化点：pen=路径；circle=[圆心,边缘]；arrow=[起,止]；ripple/laser=[当前点]
 * @param alpha 当前透明度（淡出用）
 * @param ageMs 自结束起的存活毫秒（ripple 动画/laser 拖尾用；未结束则为 0）
 */
data class RenderAnnotation(
    val id: String,
    val tool: String,
    val color: String,
    val points: List<NPoint>,
    val alpha: Float,
    val ageMs: Long,
)
