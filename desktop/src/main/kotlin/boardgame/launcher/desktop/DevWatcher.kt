package boardgame.launcher.desktop

import java.io.File
import java.util.zip.ZipFile

/**
 * Development watcher: mirrors game-module build outputs (jars from back/build/libs and assets from
 * front/dist) into DATA_DIR/games-modules/<name>/ and asks the launcher to restart the core
 * whenever a jar changes. The source jars are never loaded directly, so rebuilding is safe while
 * the launcher runs.
 */
class DevWatcher(
    private val coreJar: File,
    private val modules: List<DevModule>,
    private val dataDir: File,
    private val log: (String) -> Unit,
    private val onReload: (File) -> Boolean
) {
    private val moduleRoot = File(dataDir, "games-modules")
    private val coreMirrorDir = File(dataDir, "dev/core")

    /** The core jar the launcher should load: a private copy, so Gradle can rebuild the original. */
    var currentCoreJar: File = coreJar
        private set

    /** Initial mirror so the first core load already sees the dev builds. */
    fun sync() {
        syncCore()
        modules.forEach(::syncModule)
    }

    fun start() {
        Thread({ watch() }, "dev-watcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun watch() {
        var snapshot = snapshot()
        while (true) {
            Thread.sleep(POLL_MS)
            val current = snapshot()
            if (current == snapshot) continue

            val stable = awaitStable(current)
            val jarsChanged = stable.core != snapshot.core || stable.jars != snapshot.jars
            val frontChanged = stable.fronts != snapshot.fronts
            val reason = when {
                stable.core != snapshot.core -> "core jar"
                stable.jars != snapshot.jars -> "game module jar"
                else -> null
            }

            try {
                sync()
            } catch (error: Throwable) {
                // Most likely a build is still writing the artifact: keep the old snapshot so the
                // next poll retries.
                log("module sync failed (will retry): ${error.message}")
                Thread.sleep(RETRY_MS)
                continue
            }
            snapshot = stable

            when {
                reason != null -> {
                    log("$reason changed, restarting core")
                    if (onReload(currentCoreJar)) {
                        log("core reloaded (${currentCoreJar.name})")
                        // The previous classloader no longer holds its jar: drop it now.
                        runCatching { sync() }
                    } else {
                        log("core reload failed; fix the build and rebuild to retry")
                    }
                }
                frontChanged -> log("game front updated; reload the browser page to pick it up")
            }
        }
    }

    private fun syncCore() {
        if (!coreJar.isFile) error("core jar not found: $coreJar")
        verifyJar(coreJar)
        val target = File(coreMirrorDir, "core-${coreJar.lastModified()}.jar")
        if (!target.isFile) {
            coreMirrorDir.mkdirs()
            coreJar.copyTo(target)
        }
        currentCoreJar = target
        coreMirrorDir.listFiles { file -> file.isFile && file.extension == "jar" && file.name != target.name }
            ?.forEach { stale -> stale.delete() }
    }

    private fun syncModule(module: DevModule) {
        val jars = buildJars(module)
        if (jars.isNotEmpty()) {
            jars.forEach(::verifyJar)
            val targetBack = File(moduleRoot, "${module.name}/back")
            targetBack.mkdirs()
            val installed = jars.map { jar ->
                // A loaded jar cannot be replaced on Windows, so every build gets its own file.
                val target = File(targetBack, "${module.name}-${jar.nameWithoutExtension}-${jar.lastModified()}.jar")
                if (!target.isFile) jar.copyTo(target)
                target
            }.toSet()
            targetBack.listFiles { file -> file.isFile && file.extension == "jar" && file !in installed }
                ?.forEach { stale -> stale.delete() }
        }

        val frontDir = module.frontDir
        if (frontDir.isDirectory) {
            val targetFront = File(moduleRoot, "${module.name}/front")
            if (targetFront.exists()) targetFront.deleteRecursively()
            frontDir.copyRecursively(targetFront, overwrite = true)
        }
    }

    private fun buildJars(module: DevModule): List<File> {
        val all = module.jarDir.listFiles { file ->
            file.isFile && file.extension == "jar" &&
                !file.name.endsWith("-sources.jar") && !file.name.endsWith("-javadoc.jar")
        }?.toList().orEmpty()
        val primary = all.filter { it.name.startsWith("back-") }
        return (primary.ifEmpty { all }).sortedBy { it.name }
    }

    /** Rejects jars that a build has not finished writing, so a truncated copy is never installed. */
    private fun verifyJar(jar: File) {
        val deadline = System.currentTimeMillis() + JAR_TIMEOUT_MS
        while (true) {
            val valid = runCatching { ZipFile(jar).use { it.size() > 0 } }.getOrDefault(false)
            if (valid) return
            if (System.currentTimeMillis() > deadline) error("${jar.name} is not a readable jar")
            Thread.sleep(200)
        }
    }

    private data class Snapshot(val core: String, val jars: String, val fronts: String)

    private fun snapshot(): Snapshot = Snapshot(
        core = fingerprint(coreJar),
        jars = modules.joinToString("|") { module ->
            "${module.name}:" + tree(module.jarDir) { it.extension == "jar" }
        },
        fronts = modules.joinToString("|") { module ->
            "${module.name}:" + tree(module.frontDir) { true }
        }
    )

    private fun awaitStable(initial: Snapshot): Snapshot {
        var last = initial
        repeat(STABLE_CHECKS) {
            Thread.sleep(STABLE_MS)
            val next = snapshot()
            if (next == last) return next
            last = next
        }
        return last
    }

    private fun fingerprint(file: File): String =
        if (file.isFile) "${file.length()}@${file.lastModified()}" else "missing"

    private fun tree(dir: File, include: (File) -> Boolean): String {
        if (!dir.isDirectory) return "missing"
        return dir.walkTopDown()
            .filter { it.isFile && include(it) }
            .map { "${it.relativeTo(dir).path}:${it.length()}@${it.lastModified()}" }
            .sorted()
            .joinToString(",")
    }

    companion object {
        private const val POLL_MS = 700L
        private const val STABLE_MS = 350L
        private const val STABLE_CHECKS = 4
        private const val RETRY_MS = 2000L
        private const val JAR_TIMEOUT_MS = 5000L
    }
}

/** A game repository in development: its Gradle jar output and its built front. */
data class DevModule(val name: String, val root: File) {
    val jarDir: File get() = File(root, "back/build/libs")
    val frontDir: File get() = File(root, "front/dist")
}
