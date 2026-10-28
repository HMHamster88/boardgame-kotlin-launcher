package spike.api

import java.io.File

/** Services the platform host (desktop launcher / Android app) provides to plugins. */
interface HostServices {
    val appName: String
    val dataDir: File
    fun log(message: String)
}

/** One WebSocket client, owned by the host. */
interface Peer {
    val userId: String
    fun send(text: String)
    fun onMessage(handler: (String) -> Unit)
    fun onClose(handler: () -> Unit)
}

/** Portable core: no HTTP, no SQL, only protocol logic. */
interface GameCore {
    fun attach(peer: Peer)
    fun detach(peer: Peer)
    suspend fun shutdown()
}

/** Entry point of a downloadable core plugin. */
interface CoreModule {
    val version: String
    fun start(host: HostServices): GameCore
}

/** Entry point of a downloadable game plugin. */
interface GameBackService {
    val type: String
    fun hello(): String
}

interface GameBackModule {
    fun createService(): GameBackService
}
