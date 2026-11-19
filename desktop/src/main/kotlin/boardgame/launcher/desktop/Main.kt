package boardgame.launcher.desktop

import boardgame.api.Roles
import boardgame.api.User
import boardgame.api.apiJson
import boardgame.launcher.LauncherHost
import boardgame.launcher.update.CoreResolver
import boardgame.launcher.update.GithubReleases
import boardgame.launcher.update.JvmFileOps
import boardgame.launcher.update.ModuleInstaller
import boardgame.launcher.update.UrlConnectionHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
 *  - CHECK_FOR_UPDATES "true" to look for a newer core release
 *  - FRONT_DIR         front assets (default <core dir>/front, or ../back/public in dev)
 *  - DEV               "true" watches core/game jars and reloads them on rebuild
 *  - DEV_MODULES       game repo roots for DEV, separated by ';' (default: sibling repos)
 *  - NO_CLI            "1" to run without the interactive console
 */
fun main() {
    val dataDir = File(System.getenv("DATA_DIR") ?: File(System.getProperty("user.home"), "boardgame-kotlin").path)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val coreHomepage = System.getenv("CORE_HOMEPAGE")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CORE_HOMEPAGE
    val checkForUpdates = System.getenv("CHECK_FOR_UPDATES").equals("true", ignoreCase = true)
    val dev = System.getenv("DEV").equals("true", ignoreCase = true)

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
        System.err.println("  Set CORE_JAR, or run with CHECK_FOR_UPDATES=true to install from $coreHomepage.")
        return
    }

    val frontDir = System.getenv("FRONT_DIR")?.let(::File)
        ?: resolved?.let { File(it.directory, "front") }?.takeIf { it.isDirectory }
        ?: (if (dev) devFrontDir(coreJar) else null)
        ?: File(dataDir, "core/front")

    val host = LauncherHost(dataDir, port, frontDir)

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
        val parts = line.trim().split(Regex("\\s+"))

        when {
            parts[0] == "exit" || parts[0] == "quit" -> break
            parts[0] == "help" -> printHelp()

            parts[0] == "game-modules" && parts.getOrNull(1) == "list" -> {
                host.gameModules().forEach { module ->
                    println("${module.type}\t${module.version}\t${module.directory}\t${module.homepage}")
                }
            }

            parts[0] == "game-modules" && parts.getOrNull(1) == "add" && parts.size >= 3 -> {
                runBlocking {
                    runCatching { host.installGameModule(parts[2]) }
                        .onSuccess { println("installed ${it.type} ${it.version} in ${it.directory}") }
                        .onFailure { println("install failed: ${it.message}") }
                }
            }

            parts[0] == "users" && parts.getOrNull(1) == "list" -> {
                host.storage.getAll("users").forEach { println(it) }
            }

            parts[0] == "users" && parts.getOrNull(1) == "roles" && parts.size >= 5 -> {
                val action = parts[2]
                val userId = parts[3]
                val role = parts[4]
                if (role != Roles.ADMIN) {
                    println("unknown role: $role (available: ${Roles.ADMIN})")
                } else {
                    println(updateRole(host, userId, role, add = action == "add"))
                }
            }

            else -> println("unknown command; type 'help'")
        }
    }

    runBlocking { host.stop() }
}

private fun printHelp() {
    println(
        """
        commands:
          game-modules list
          game-modules add <github-homepage>
          users list
          users roles add|remove <userId> <role>
          help
          exit
        """.trimIndent()
    )
}

private fun updateRole(host: LauncherHost, userId: String, role: String, add: Boolean): String {
    val raw = host.storage.get("users", userId) ?: return "no user with id $userId"
    val user = runCatching { apiJson.decodeFromString<User>(raw) }.getOrNull()
        ?: return "invalid user document for $userId"
    val roles = if (add) (user.roles + role).distinct() else user.roles - role
    host.storage.put("users", userId, apiJson.encodeToString(user.copy(roles = roles)))
    return "roles for $userId: ${roles.joinToString(", ").ifEmpty { "(none)" }}"
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
