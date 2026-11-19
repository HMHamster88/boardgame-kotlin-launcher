package boardgame.launcher.desktop

import boardgame.launcher.LauncherHost
import boardgame.launcher.update.CoreResolver
import boardgame.launcher.update.GithubReleases
import boardgame.launcher.update.JvmFileOps
import boardgame.launcher.update.ModuleInstaller
import boardgame.launcher.update.UrlConnectionHttp
import kotlinx.coroutines.runBlocking
import java.io.File

private const val DEFAULT_CORE_HOMEPAGE = "https://github.com/HMHamster88/boardgame-kotlin-core"

/**
 * Console-first desktop launcher.
 *
 * Env:
 *  - DATA_DIR          data directory (default ~/boardgame-kotlin)
 *  - PORT              HTTP/WS port (default 8000)
 *  - CORE_JAR          explicit core plugin jar (skips the release lookup)
 *  - CORE_HOMEPAGE     core repository (default: boardgame-kotlin-core)
 *  - CHECK_FOR_UPDATES "true"/"false" override; defaults to true unless DEV=true
 *  - FRONT_DIR         front assets (default <core dir>/front, or ../back/public in dev)
 *  - DEV               "true" watches core/game jars and reloads them on rebuild
 *  - DEV_MODULES       game repo roots for DEV, separated by ';' (default: sibling repos)
 *  - NO_CLI            "1" to run without the interactive console
 */
fun main() {
    val dataDir = File(System.getenv("DATA_DIR") ?: File(System.getProperty("user.home"), "boardgame-kotlin").path)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val coreHomepage = System.getenv("CORE_HOMEPAGE")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CORE_HOMEPAGE
    val dev = System.getenv("DEV").equals("true", ignoreCase = true)
    val checkForUpdates = when (System.getenv("CHECK_FOR_UPDATES")?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> !dev
    }

    val http = UrlConnectionHttp()
    val installer = ModuleInstaller(http, JvmFileOps(), GithubReleases(http))
    val resolver = CoreResolver(dataDir, installer, coreHomepage) { println("[core] $it") }

    val explicitCoreJar = System.getenv("CORE_JAR")?.takeIf { it.isNotBlank() }?.let(::File)
    val resolved = when {
        explicitCoreJar != null -> null
        else -> runBlocking {
            runCatching { resolver.resolve(checkForUpdates) }
                .onFailure { System.err.println("Core resolve failed: ${it.message}") }
                .getOrNull()
        }
    }
    val coreJar = explicitCoreJar ?: resolved?.jar

    if (coreJar == null || !coreJar.isFile) {
        System.err.println("No core module found.")
        System.err.println("  Set CORE_JAR, or let the launcher install the core from $coreHomepage.")
        return
    }

    val frontDir = System.getenv("FRONT_DIR")?.let(::File)
        ?: resolved?.let { File(it.directory, "front") }?.takeIf { it.isDirectory }
        ?: (if (dev) devFrontDir(coreJar) else null)
        ?: File(dataDir, "core/front")

    val host = LauncherHost(dataDir, port, frontDir, checkForUpdates = checkForUpdates)

    val devModules = if (dev) resolveDevModules(coreJar, System.getenv("DEV_MODULES")) else emptyList()
    var watcher: DevWatcher? = null
    if (dev) {
        watcher = DevWatcher(coreJar, devModules, dataDir, ::println) { jar -> host.reloadCore(jar) }
        runCatching { watcher.sync() }
            .onFailure { System.err.println("[dev] initial module sync failed: ${it.message}") }
    }

    val loadedCoreJar = watcher?.currentCoreJar ?: coreJar
    host.start(loadedCoreJar)

    println("boardgame-kotlin launcher")
    println("  data: $dataDir")
    println("  core: ${loadedCoreJar.absolutePath}${resolved?.let { " (${it.version})" } ?: ""}")
    println("  open: http://127.0.0.1:$port")

    if (dev) {
        println("  dev: watching core jar and ${devModules.size} game module(s)")
        devModules.forEach { println("       ${it.name} <- ${it.root}") }
        println("       rebuild with: gradle :back:jar (and npm run build-front) in each repo")
        watcher?.start()
    }

    if (System.getenv("NO_CLI") == "1") {
        Thread.currentThread().join()
        return
    }

    while (true) {
        print("> ")
        System.out.flush()
        val line = readlnOrNull() ?: break
        if (line.isBlank()) continue
        val command = line.trim()
        if (command == "exit" || command == "quit") break
        val output = runBlocking { host.execute(command) }
        if (output.isNotBlank()) println(output)
    }

    runBlocking { host.stop() }
}

/** Dev default: serve the SPA straight from the core repo's Vite output. */
private fun devFrontDir(coreJar: File): File? {
    val backDir = coreJar.absoluteFile.parentFile?.parentFile?.parentFile ?: return null
    val public = File(backDir, "public")
    return public.takeIf { File(it, "index.html").isFile }
}

/**
 * Dev modules: explicit `DEV_MODULES` entries, or the sibling repositories of the core repo that
 * look like game modules (a Gradle `back` and a built `front`).
 */
private fun resolveDevModules(coreJar: File, spec: String?): List<DevModule> {
    val entries = spec.orEmpty().split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
    if (entries.isNotEmpty()) {
        return entries.mapNotNull { entry ->
            val root = File(entry).absoluteFile
            if (!root.isDirectory) {
                System.err.println("[dev] ignoring DEV_MODULES entry (not a directory): $entry")
                null
            } else {
                DevModule(root.name, root)
            }
        }
    }

    val repo = coreJar.absoluteFile.parentFile?.parentFile?.parentFile?.parentFile ?: return emptyList()
    val workspace = repo.parentFile ?: return emptyList()
    return workspace.listFiles().orEmpty()
        .filter { it.isDirectory && it.canonicalFile != repo.canonicalFile }
        .filter { File(it, "back/build.gradle.kts").isFile && File(it, "front/package.json").isFile }
        .sortedBy { it.name }
        .map { DevModule(it.name, it) }
}
