package boardgame.launcher.desktop

import boardgame.launcher.LauncherHost
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Console-first desktop launcher.
 *
 * Env:
 *  - DATA_DIR   data directory (default ~/boardgame-kotlin)
 *  - PORT       HTTP/WS port (default 8000)
 *  - CORE_JAR   core plugin jar (default <DATA_DIR>/core/back/core.jar)
 *  - FRONT_DIR  front assets (default <DATA_DIR>/core/front)
 */
fun main() {
    val dataDir = File(System.getenv("DATA_DIR") ?: File(System.getProperty("user.home"), "boardgame-kotlin").path)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val coreJar = System.getenv("CORE_JAR")?.let(::File) ?: File(dataDir, "core/back/core.jar")
    val frontDir = System.getenv("FRONT_DIR")?.let(::File) ?: File(dataDir, "core/front")

    if (!coreJar.isFile) {
        System.err.println("Core jar not found: ${coreJar.absolutePath}")
        System.err.println("Set CORE_JAR or install a core release into <DATA_DIR>/core/.")
        return
    }

    val host = LauncherHost(dataDir, port, frontDir)
    host.start(coreJar)

    println("boardgame-kotlin launcher")
    println("  data: $dataDir")
    println("  core: ${coreJar.absolutePath}")
    println("  open: http://127.0.0.1:$port")

    if (System.getenv("NO_CLI") == "1") {
        // Used by headless/e2e environments: keep the host alive without an interactive stdin.
        Thread.currentThread().join()
        return
    }

    while (true) {
        print("> ")
        System.out.flush()
        val line = readlnOrNull() ?: break
        when (line.trim()) {
            "" -> Unit
            "help" -> println("commands: help, exit")
            "exit", "quit" -> break
            else -> println("unknown command: ${line.trim()}")
        }
    }

    runBlocking { host.stop() }
}
