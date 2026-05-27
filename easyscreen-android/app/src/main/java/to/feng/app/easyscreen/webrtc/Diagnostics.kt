package to.feng.app.easyscreen.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection

/**
 * 单个 Guest 连接的诊断快照。
 */
data class PeerDiagnostics(
    val guestId: String,
    val iceState: PeerConnection.IceConnectionState? = null,
    /** 当前选中的 candidate-pair 路径类型：local <-> remote */
    val pathType: String = "—",  // 例如 "host ↔ srflx"
    /** 连接模式归纳："局域网直连" / "NAT 穿透" / "中继 (TURN)" / "未知" */
    val connectionMode: String = "未知",
    val codec: String = "—",
    val resolution: String = "—",
    val framesPerSecond: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val rttMs: Double? = null,
    val packetsLost: Long = 0,
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val nackCount: Long = 0,
    val firCount: Long = 0,
    val pliCount: Long = 0,
)

/**
 * 周期性轮询 PeerConnection.getStats() 把指标转成 [PeerDiagnostics]。
 * Host 用：传入 guestId → 每个 Guest PC 一个采样器。
 */
class DiagnosticsCollector(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 1000L,
) {
    private val _diagnostics = MutableStateFlow<Map<String, PeerDiagnostics>>(emptyMap())
    val diagnostics: StateFlow<Map<String, PeerDiagnostics>> = _diagnostics.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    // 上一次采样的累计量，用于算速率
    private data class Cum(val ts: Long, val bytes: Long, val frames: Long)
    private val lastCum = mutableMapOf<String, Cum>()

    fun track(guestId: String, pc: () -> PeerConnection?) {
        stopTrack(guestId)
        jobs[guestId] = scope.launch {
            while (isActive) {
                val current = pc() ?: break
                runCatching { sample(guestId, current) }
                delay(intervalMs)
            }
        }
    }

    fun stopTrack(guestId: String) {
        jobs.remove(guestId)?.cancel()
        lastCum.remove(guestId)
        _diagnostics.value = _diagnostics.value - guestId
    }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        lastCum.clear()
        _diagnostics.value = emptyMap()
    }

    private fun sample(guestId: String, pc: PeerConnection) {
        pc.getStats { report ->
            val statsMap = report.statsMap

            // 找当前选中的 candidate pair
            var selectedPairId: String? = null
            var transportPairId: String? = null
            statsMap.values.forEach { s ->
                if (s.type == "transport") {
                    transportPairId = s.members["selectedCandidatePairId"] as? String
                }
            }
            // 退路：找 nominated=true 且 state=succeeded 的 pair
            statsMap.values.forEach { s ->
                if (s.type == "candidate-pair") {
                    val nominated = s.members["nominated"] as? Boolean
                    val state = s.members["state"] as? String
                    if (nominated == true && state == "succeeded" && selectedPairId == null) {
                        selectedPairId = s.id
                    }
                }
            }
            val pairId = transportPairId ?: selectedPairId
            val pair = pairId?.let { statsMap[it] }

            val localStats = (pair?.members?.get("localCandidateId") as? String)?.let { statsMap[it] }
            val remoteStats = (pair?.members?.get("remoteCandidateId") as? String)?.let { statsMap[it] }
            val localType = (localStats?.members?.get("candidateType") as? String) ?: "?"
            val remoteType = (remoteStats?.members?.get("candidateType") as? String) ?: "?"
            val localAddr = (localStats?.members?.get("address") as? String) ?: "?"
            val remoteAddr = (remoteStats?.members?.get("address") as? String) ?: "?"
            val pathType = "$localType($localAddr) ↔ $remoteType($remoteAddr)"
            val mode = deriveMode(localType, remoteType, localAddr, remoteAddr)
            val rtt = (pair?.members?.get("currentRoundTripTime") as? Double)?.let { it * 1000.0 }

            // outbound-rtp video 累计字节、帧
            var bytesSent = 0L
            var framesSent = 0L
            var packetsSent = 0L
            var nackCount = 0L
            var firCount = 0L
            var pliCount = 0L
            var codec = "—"
            var frameW = 0; var frameH = 0; var fps = 0.0
            statsMap.values.forEach { s ->
                val kind = s.members["kind"] as? String
                if (s.type == "outbound-rtp" && kind == "video") {
                    bytesSent += (s.members["bytesSent"] as? Long) ?: 0L
                    packetsSent += (s.members["packetsSent"] as? Long) ?: 0L
                    framesSent += (s.members["framesSent"] as? Long) ?: 0L
                    nackCount += (s.members["nackCount"] as? Long) ?: 0L
                    firCount += (s.members["firCount"] as? Long) ?: 0L
                    pliCount += (s.members["pliCount"] as? Long) ?: 0L
                    val codecId = s.members["codecId"] as? String
                    if (codecId != null) {
                        codec = (statsMap[codecId]?.members?.get("mimeType") as? String)
                            ?.removePrefix("video/") ?: "—"
                    }
                    frameW = (s.members["frameWidth"] as? Long)?.toInt() ?: frameW
                    frameH = (s.members["frameHeight"] as? Long)?.toInt() ?: frameH
                    fps = (s.members["framesPerSecond"] as? Double) ?: fps
                }
            }

            // remote-inbound-rtp video 拿丢包率
            var packetsLost = 0L
            statsMap.values.forEach { s ->
                if (s.type == "remote-inbound-rtp" && (s.members["kind"] as? String) == "video") {
                    packetsLost += (s.members["packetsLost"] as? Long) ?: 0L
                }
            }

            val now = System.currentTimeMillis()
            val last = lastCum[guestId]
            val bitrateKbps: Double = if (last != null && now > last.ts) {
                ((bytesSent - last.bytes).toDouble() * 8.0) / (now - last.ts)  // bits/ms = kbps
            } else 0.0
            lastCum[guestId] = Cum(now, bytesSent, framesSent)

            val resolution = if (frameW > 0 && frameH > 0) "${frameW}×${frameH}" else "—"

            val snap = PeerDiagnostics(
                guestId = guestId,
                pathType = pathType,
                connectionMode = mode,
                codec = codec,
                resolution = resolution,
                framesPerSecond = fps,
                bitrateKbps = bitrateKbps,
                rttMs = rtt,
                packetsLost = packetsLost,
                packetsSent = packetsSent,
                bytesSent = bytesSent,
                nackCount = nackCount,
                firCount = firCount,
                pliCount = pliCount,
            )
            _diagnostics.value = _diagnostics.value + (guestId to snap)
        }
    }

    private fun deriveMode(localType: String, remoteType: String, localAddr: String, remoteAddr: String): String {
        val l = localType.lowercase()
        val r = remoteType.lowercase()
        return when {
            l == "relay" || r == "relay" -> "中继 (TURN)"
            l == "host" && r == "host" -> {
                if (!isPrivateAddr(localAddr) || !isPrivateAddr(remoteAddr)) "公网直连"
                else "局域网直连"
            }
            (l == "srflx" || l == "prflx") && (r == "srflx" || r == "prflx") -> "NAT 穿透"
            (l == "host" && (r == "srflx" || r == "prflx")) ||
                ((l == "srflx" || l == "prflx") && r == "host") -> "NAT 穿透"
            else -> "未知"
        }
    }

    /** 判断地址是否为私网 / 链路本地 / 回环（不可路由） */
    private fun isPrivateAddr(addr: String): Boolean {
        if (addr.isEmpty() || addr == "?") return false
        return when {
            addr.startsWith("10.") -> true
            addr.startsWith("192.168.") -> true
            addr.startsWith("169.254.") -> true
            addr.startsWith("127.") -> true
            // 172.16.0.0 - 172.31.255.255
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(addr) -> true
            // CGNAT 100.64.0.0/10
            Regex("^100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.").containsMatchIn(addr) -> true
            // IPv6 link-local fe80::, unique local fc00::/7, loopback ::1
            Regex("^fe[89ab][0-9a-f]", RegexOption.IGNORE_CASE).containsMatchIn(addr) -> true
            Regex("^f[cd][0-9a-f]{2}", RegexOption.IGNORE_CASE).containsMatchIn(addr) -> true
            addr == "::1" -> true
            else -> false
        }
    }
}
