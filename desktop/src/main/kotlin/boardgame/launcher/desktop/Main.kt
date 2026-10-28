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
 *  - FRONT_DIR         front assets (default <core dir>/front)
 *  - NO_CLI            "1" to run without the interactive console
 */
fun main() {
    val dataDir = File(System.getenv("DATA_DIR") ?: File(System.getProperty("user.home"), "boardgame-kotlin").path)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val coreHomepage = System.getenv("CORE_HOMEPAGE")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CORE_HOMEPAGE
    val checkForUpdates = System.getenv("CHECK_FOR_UPDATES").equals("true", ignoreCase = true)

    val http = UrlConnectionHttp()
    val installer = ModuleInstaller(http, JvmFileOps(), GithubReleases(http))
    val resolver = CoreResolver(dataDir, installer, coreHomepage)

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
        ?: File(dataDir, "core/front")

    val host = LauncherHost(dataDir, port, frontDir)
    host.start(coreJar)

    println("boardgame-kotlin launcher")
    println("  data: $dataDir")
    println("  core: ${coreJar.absolutePath}${resolved?.let { " (${it.version})" } ?: ""}")
    println("  open: http://127.0.0.1:$port")

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
