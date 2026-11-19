package boardgame.launcher.update

import java.io.File

data class InstalledCore(
    val version: String,
    val directory: File,
    val jar: File
)

/**
 * Resolves the core plugin jar for the launcher:
 *  - uses an installed version under `<dataDir>/core/<version>/`,
 *  - optionally checks the core repository's latest release and installs it first.
 */
class CoreResolver(
    private val dataDir: File,
    private val installer: ModuleInstaller,
    private val coreHomepage: String,
    private val log: (String) -> Unit = {}
) {
    fun installed(): InstalledCore? {
        val coreDir = File(dataDir, "core")
        if (!coreDir.isDirectory) return null

        return coreDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val version = installer.readVersion(dir.path) ?: dir.name
                val jar = File(dir, "back").listFiles()?.firstOrNull { it.extension == "jar" } ?: return@mapNotNull null
                InstalledCore(version, dir, jar)
            }
            ?.maxWithOrNull { a, b -> Version.compare(a.version, b.version) }
    }

    suspend fun resolve(checkForUpdates: Boolean): InstalledCore {
        val installed = installed()
        if (!checkForUpdates && installed != null) {
            log("using installed core ${installed.version}")
            return installed
        }

        log("checking ${coreHomepage.trimEnd('/')} for the latest core release")
        val release = runCatching { installer.latest(coreHomepage) }.getOrNull()
        if (release == null) {
            log("no release found")
            return installed ?: error("No core module installed and no release available for $coreHomepage")
        }

        if (installed != null && !Version.isNewer(release.version, installed.version)) {
            log("core ${installed.version} is up to date")
            return installed
        }

        if (installed == null) {
            log("core ${release.version} is not installed yet")
        } else {
            log("updating core ${installed.version} -> ${release.version}")
        }
        val target = File(dataDir, "core/${release.version}")
        installer.install(release, target.path, log)
        val jar = File(target, "back").listFiles()?.firstOrNull { it.extension == "jar" }
            ?: error("Installed core ${release.version} has no back/*.jar")
        return InstalledCore(release.version, target, jar)
    }
}
