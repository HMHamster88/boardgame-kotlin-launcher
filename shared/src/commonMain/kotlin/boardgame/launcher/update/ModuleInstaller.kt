package boardgame.launcher.update

/**
 * Downloads a release asset, verifies its checksum (when the API provides one) and installs it into
 * [targetDir] atomically: extract to `<targetDir>.tmp`, then move the directory into place.
 * Shared by the desktop launcher and the Android app.
 */
class ModuleInstaller(
    private val http: Http,
    private val files: FileOps,
    private val releases: ReleaseApi
) {
    suspend fun latest(homepage: String): Release? = releases.latest(homepage)

    suspend fun install(release: Release, targetDir: String, log: ((String) -> Unit)? = null): InstalledRelease {
        val existing = readVersion(targetDir)
        if (existing != null) return InstalledRelease(existing, targetDir)

        val asset = release.defaultAsset() ?: error("release ${release.version} has no assets")
        log?.invoke("downloading ${asset.name} (${release.version})")

        var lastBucket = -1
        val onProgress: (Long, Long) -> Unit = { received, total ->
            if (total > 0) {
                val percent = ((received * 100) / total).toInt()
                val bucket = (percent / 25) * 25
                if (bucket in 25..100 && bucket > lastBucket) {
                    lastBucket = bucket
                    log?.invoke("  $bucket%")
                }
            }
        }
        val bytes = when (http) {
            is ProgressHttp -> http.getBytes(asset.url, onProgress)
            else -> http.getBytes(asset.url)
        } ?: error("failed to download ${asset.url}")
        asset.sha256?.let { expected ->
            val actual = files.sha256(bytes)
            require(actual.equals(expected, ignoreCase = true)) { "checksum mismatch for ${asset.name}" }
        }

        val temp = "$targetDir.tmp"
        if (files.exists(temp)) files.deleteRecursively(temp)
        files.ensureDir(temp)
        files.unzip(bytes, temp)
        if (files.exists(targetDir)) files.deleteRecursively(targetDir)
        files.move(temp, targetDir)
        files.writeText("$targetDir/version.properties", "version=${release.version}\n")
        log?.invoke("installed ${release.version} in $targetDir")
        return InstalledRelease(release.version, targetDir)
    }

    fun readVersion(dir: String): String? {
        val text = files.readText("$dir/version.properties") ?: return null
        return text.lineSequence().firstOrNull { it.startsWith("version=") }?.substringAfter('=')
    }

    fun isInstalled(dir: String): Boolean = readVersion(dir) != null

    fun needsUpdate(installed: String?, release: Release): Boolean =
        installed == null || Version.isNewer(release.version, installed)
}
