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
                installAssets(dataDir)
                val launcher = LauncherHost(
                    dataDir = dataDir,
                    port = PORT,
                    frontDir = File(dataDir, "core/front"),
                    pluginLoader = DexPluginLoader(applicationContext)
                )
                host = launcher
                launcher.start(File(dataDir, "core/back/core.jar"))
                updateNotification("Running on port $PORT")
            } catch (t: Throwable) {
                updateNotification("Failed: ${t.message}")
            }
        }
    }

    override fun onDestroy() {
        host?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun installAssets(dataDir: File) {
        copyAsset("plugins/core.jar", File(dataDir, "core/back/core.jar"))
        copyAsset("plugins/cah.jar", File(dataDir, "games-modules/cah/back/cah.jar"))
        copyAssetTree("front", File(dataDir, "core/front"))
        copyAssetTree("modules/cah/front", File(dataDir, "games-modules/cah/front"))
    }

    private fun copyAsset(assetPath: String, target: File) {
        if (target.isFile && target.length() > 0) return
        target.parentFile?.mkdirs()
        assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
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
