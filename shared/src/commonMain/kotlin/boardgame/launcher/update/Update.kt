package boardgame.launcher.update

/** Network boundary; implemented by the platform app (JVM / Android). */
interface Http {
    suspend fun getText(url: String): String?
    suspend fun getBytes(url: String): ByteArray?
}

/** Optional [Http] capability: report download progress as (received bytes, total bytes or -1). */
interface ProgressHttp {
    suspend fun getBytes(url: String, onProgress: (received: Long, total: Long) -> Unit): ByteArray?
}

/** File-system + archive boundary; implemented by [boardgame.launcher.update.JvmFileOps] on both targets. */
interface FileOps {
    fun exists(path: String): Boolean
    fun ensureDir(path: String)
    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun deleteRecursively(path: String)
    fun listDirs(path: String): List<String>
    fun unzip(bytes: ByteArray, targetDir: String)
    fun move(from: String, to: String)
    fun sha256(bytes: ByteArray): String
}

data class ReleaseAsset(
    val name: String,
    val url: String,
    val sha256: String?
)

data class Release(
    val version: String,
    val assets: List<ReleaseAsset>
) {
    /** The first asset, mirroring the "assets[0]" rule of the old launcher. */
    fun defaultAsset(): ReleaseAsset? = assets.firstOrNull()
}

interface ReleaseApi {
    suspend fun latest(homepage: String): Release?
}

data class InstalledRelease(
    val version: String,
    val path: String
)
