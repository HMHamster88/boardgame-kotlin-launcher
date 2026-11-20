package boardgame.launcher.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import boardgame.launcher.LauncherHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hosts the Ktor server, the core plugin and the game modules inside the app process.
 * Everything is extracted from APK assets on first run, then loaded with [DexPluginLoader].
 */
class ServerService : Service() {

    companion object {
        const val PORT = 8080
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var host: LauncherHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat("Starting server…")
        scope.launch {
            try {
                val dataDir = File(filesDir, "boardgame-kotlin").apply { mkdirs() }
                val coreVersion = installAssets(dataDir)
                val launcher = LauncherHost(
                    dataDir = dataDir,
                    port = PORT,
                    frontDir = File(dataDir, "core/front"),
                    pluginLoader = DexPluginLoader(applicationContext),
                    checkForUpdates = true,
                    console = AppConsole
                )
                host = launcher
                AppConsole.println("[service] starting core $coreVersion")
                launcher.start(File(dataDir, "core/$coreVersion/back/core.jar"))
                AppConsole.commandHandler = { command, callback ->
                    scope.launch {
                        val result = runCatching { launcher.execute(command) }
                            .getOrElse { "error: ${it.message}" }
                        callback(result)
                    }
                }
                val lanEndpoints = runCatching { launcher.lanEndpoints() }.getOrDefault(emptyList())
                AppConsole.println("[service] running on http://127.0.0.1:$PORT")
                if (lanEndpoints.isEmpty()) {
                    AppConsole.println("[service] no external IPv4 interfaces found")
                } else {
                    lanEndpoints.forEach { endpoint ->
                        AppConsole.println("[service] open ${endpoint.url} (${endpoint.interfaceName})")
                    }
                }
                val lanUrl = lanEndpoints.firstOrNull()?.url
                updateNotification(if (lanUrl != null) "Running on $lanUrl" else "Running on port $PORT")
            } catch (t: Throwable) {
                AppConsole.println("[service] failed: ${t.message}")
                updateNotification("Failed: ${t.message}")
            }
        }
    }

    override fun onDestroy() {
        AppConsole.commandHandler = null
        host?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun installAssets(dataDir: File): String {
        val coreVersion = bundledVersion("plugins/core.jar") ?: "0.0.0"
        val coreDir = File(dataDir, "core/$coreVersion")
        copyAsset("plugins/core.jar", File(coreDir, "back/core.jar"))
        File(coreDir, "version.properties").writeText("version=$coreVersion\n")
        copyAssetTree("front", File(dataDir, "core/front"))

        val cahVersion = bundledVersion("plugins/cah.jar") ?: "0.0.0"
        val cahDir = File(dataDir, "games-modules/cah/$cahVersion")
        copyAsset("plugins/cah.jar", File(cahDir, "back/cah.jar"))
        copyAssetTree("modules/cah/front", File(cahDir, "front"))
        return coreVersion
    }

    /** Reads `version.properties` from a bundled jar without extracting it. */
    private fun bundledVersion(assetPath: String): String? {
        assets.open(assetPath).use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "version.properties") {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        return text.lineSequence()
                            .firstOrNull { it.startsWith("version=") }
                            ?.substringAfter('=')
                    }
                }
            }
        }
        return null
    }

    private fun copyAsset(assetPath: String, target: File) {
        val bytes = assets.open(assetPath).use { it.readBytes() }
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
            return
        }
        target.parentFile?.mkdirs()
        // Plugin jars are marked read-only before loading; a replaced APK asset must be writable.
        if (target.exists() && !target.canWrite()) {
            check(target.setWritable(true)) { "Could not make ${target.name} writable" }
        }
        target.writeBytes(bytes)
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            copyAsset(assetPath, target)
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Server", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Boardgames")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
}
