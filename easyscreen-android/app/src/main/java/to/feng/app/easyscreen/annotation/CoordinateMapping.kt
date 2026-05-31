package to.feng.app.easyscreen.annotation

/**
 * 归一化坐标换算（纯函数，无 Android 依赖）。
 *
 * 归一化坐标 [0,1] 相对"共享画面内容"本身——与 WebRTC 实际编码分辨率无关。
 */
object CoordinateMapping {

    /**
     * Guest 端：把舞台（视频容器）内的触点换算为归一化 [0,1]。
     *
     * @param fillCover false=contain（留黑边，越界返回 null）；true=cover（裁切，clamp 到 [0,1]）
     * @return (nx, ny)；contain 模式下触到黑边或源无效时返回 null。
     */
    fun touchToNormalized(
        touchX: Float, touchY: Float,
        stageW: Float, stageH: Float,
        srcW: Int, srcH: Int,
        fillCover: Boolean,
    ): Pair<Float, Float>? {
        if (srcW <= 0 || srcH <= 0 || stageW <= 0f || stageH <= 0f) return null

        val scale = if (fillCover) {
            maxOf(stageW / srcW, stageH / srcH)
        } else {
            minOf(stageW / srcW, stageH / srcH)
        }
        val contentW = srcW * scale
        val contentH = srcH * scale
        val offsetX = (stageW - contentW) / 2f
        val offsetY = (stageH - contentH) / 2f

        val nx = (touchX - offsetX) / contentW
        val ny = (touchY - offsetY) / contentH

        if (!fillCover) {
            if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
            return nx to ny
        }
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    /** Host 端：归一化 [0,1] → 浮窗像素坐标（浮窗全屏覆盖）。 */
    fun normalizedToPixel(nx: Float, ny: Float, viewW: Int, viewH: Int): Pair<Float, Float> {
        return (nx * viewW) to (ny * viewH)
    }
}
