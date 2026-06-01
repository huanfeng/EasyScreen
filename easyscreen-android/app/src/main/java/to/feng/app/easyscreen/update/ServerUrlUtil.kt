package to.feng.app.easyscreen.update

import java.net.URI

/**
 * 把信令 WebSocket 地址（wss://host[:port]/ws）推导为 HTTP base（https://host[:port]）。
 * 更新接口在此 base 下：<base>/app/version.json、<base>/app/download。
 * 解析失败返回 null。
 */
fun deriveHttpBase(wsUrl: String): String? {
    val trimmed = wsUrl.trim()
    if (trimmed.isEmpty()) return null
    val uri = try { URI(trimmed) } catch (e: Exception) { return null }
    val scheme = when (uri.scheme?.lowercase()) {
        "wss", "https" -> "https"
        "ws", "http" -> "http"
        else -> return null
    }
    val host = uri.host ?: return null
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$portPart"
}
