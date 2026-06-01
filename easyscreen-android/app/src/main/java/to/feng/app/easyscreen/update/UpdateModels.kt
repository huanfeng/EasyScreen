package to.feng.app.easyscreen.update

/** 对应信令服务器 /app/version.json 返回体 */
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val fileSize: Long,
    val forceUpdate: Boolean,
    val minSupportedVersionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
)

enum class UpdateKind { NONE, OPTIONAL, FORCED }

/** 比较当前 versionCode 与远端元数据，决定更新类型。 */
fun decideUpdate(current: Int, remote: AppVersionInfo): UpdateKind {
    if (remote.versionCode <= current) return UpdateKind.NONE
    if (remote.forceUpdate || current < remote.minSupportedVersionCode) return UpdateKind.FORCED
    return UpdateKind.OPTIONAL
}
