package boardgame.launcher

import boardgame.api.CoreModule
import boardgame.api.GameCore
import boardgame.api.HostConsole
import boardgame.api.HostHttp
import boardgame.api.HostServices
import boardgame.api.Peer
import boardgame.api.PlatformInfo
import boardgame.api.Storage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import boardgame.launcher.update.UrlConnectionHttp
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Platform host: Ktor server, document store, plugin classloading and console.
 * Runs on the desktop launcher and inside the Android app; only the [PluginLoader] differs.
 */
class LauncherHost(
    val dataDir: File,
    val port: Int,
    private val frontDir: File,
    private val pluginLoader: PluginLoader = UrlPluginLoader()
) : HostServices {

    override val platform: PlatformInfo = PlatformInfo(PlatformInfo.DESKTOP, "0.1.0", dataDir)
    override val storage: Storage = DocumentStore(File(dataDir, "db"))
    override val http: HostHttp = SharedHttp()
    override val console: HostConsole = SystemConsole()

    private var server: EmbeddedServer<*, *>? = null
    @Volatile
    lateinit var core: GameCore
        private set

    private var currentCoreJar: File? = null
    private val webSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    fun gameModules() = core.gameModules()

    suspend fun installGameModule(homepage: String) = core.installGameModule(homepage)

    fun start(coreJar: File) {
        startCore(coreJar)
        server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    val userId = call.request.queryParameters["userId"]
                    if (userId.isNullOrBlank()) {
                        close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "userId is required"))
                        return@webSocket
                    }
                    val peer = WsPeer(userId, this)
                    webSockets += this
                    core.attach(peer)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) peer.deliver(frame.readText())
                        }
                    } finally {
                        webSockets -= this
                        peer.closed()
                        core.detach(peer)
                    }
                }

                get("/server-info") {
                    call.respondText(serverInfo().toString(), ContentType.Application.Json)
                }

                staticFiles("/games-modules", File(dataDir, "games-modules"))

                get("{...}") {
                    val relative = call.request.path().trim('/')
                    val requested = File(frontDir, relative)
                    val index = File(frontDir, "index.html")
                    when {
                        relative.isNotEmpty() && requested.isFile &&
                            requested.canonicalPath.startsWith(frontDir.canonicalPath) -> call.respondFile(requested)
                        index.isFile -> call.respondFile(index)
                        else -> call.respondText("front-end is not installed", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)

        log("listening on http://127.0.0.1:$port")
    }

    /**
     * Development hot reload: drops WebSocket clients (the front reconnects automatically), shuts the
     * current core down, and starts a new core from [coreJar]. Falls back to the previous jar when the
     * new one cannot be started. Returns true when the new core is serving.
     */
    fun reloadCore(coreJar: File): Boolean {
        val previousJar = currentCoreJar
        closeWebSockets()
        stopCore()
        return try {
            startCore(coreJar)
            true
        } catch (error: Throwable) {
            log("core reload failed: ${error.message}")
            if (previousJar != null && previousJar.isFile) {
                runCatching { startCore(previousJar) }
                    .onSuccess { log("previous core restored from ${previousJar.name}") }
                    .onFailure { log("previous core could not be restored: ${it.message}") }
            }
            false
        }
    }

    private fun startCore(coreJar: File) {
        val module = loadPlugin(coreJar, CoreModule::class.java)
        core = module.start(this)
        currentCoreJar = coreJar
    }

    private fun stopCore() {
        if (!::core.isInitialized) return
        runBlocking { runCatching { core.shutdown() } }
        pluginLoader.close()
    }

    private fun closeWebSockets() {
        val sessions = webSockets.toList()
        webSockets.clear()
        runBlocking {
            sessions.forEach { session ->
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "core reload"))
                }
            }
        }
    }

    fun stop() {
        closeWebSockets()
        stopCore()
        server?.stop(0, 1500)
    }

    override fun <T : Any> loadPlugin(file: File, service: Class<T>, parent: ClassLoader?): T =
        pluginLoader.load(file, service, parent)

    private fun serverInfo(): JsonObject = buildJsonObject {
        val publicUrl = System.getenv("PUBLIC_URL")?.trim()?.takeIf { it.isNotEmpty() }
        if (publicUrl != null) put("publicUrl", publicUrl) else put("publicUrl", JsonNull)
        putJsonArray("lanUrls") { lanUrls(port).forEach { add(it) } }
    }

    private fun lanUrls(port: Int): List<String> {
        val virtualMarkers = listOf(
            "virtual", "vbox", "vmware", "docker", "wsl", "hyper-v", "vethernet",
            "host-only", "tailscale", "vpn", "tun", "tap"
        )
        val urls = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
            if (!network.isUp || network.isLoopback) return@forEach
            val name = network.name.lowercase()
            if (virtualMarkers.any { name.contains(it) }) return@forEach
            network.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress && isPrivate(address.hostAddress)) {
                    urls += "http://${address.hostAddress}:$port"
                }
            }
        }
        return urls.distinct().sorted()
    }

    private fun isPrivate(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private fun log(message: String) {
        console.println("[host] $message")
    }

    private class WsPeer(
        override val userId: String,
        private val session: DefaultWebSocketServerSession
    ) : Peer {
        private val outbox = Channel<String>(Channel.UNLIMITED)
        private var messageHandler: ((String) -> Unit)? = null
        private var closeHandler: (() -> Unit)? = null

        init {
            CoroutineScope(session.coroutineContext).launch {
                for (text in outbox) {
                    session.send(Frame.Text(text))
                }
            }
        }

        override fun send(text: String) {
            outbox.trySend(text)
        }

        override fun onMessage(handler: (String) -> Unit) {
            messageHandler = handler
        }

        override fun onClose(handler: () -> Unit) {
            closeHandler = handler
        }

        fun deliver(text: String) {
            messageHandler?.invoke(text)
        }

        fun closed() {
            closeHandler?.invoke()
            outbox.close()
        }
    }

    private class SharedHttp : HostHttp {
        private val client = UrlConnectionHttp()

        override suspend fun getText(url: String): String? = client.getText(url)
        override suspend fun getBytes(url: String): ByteArray? = client.getBytes(url)
        override suspend fun getBytes(
            url: String,
            onProgress: (received: Long, total: Long) -> Unit
        ): ByteArray? = client.getBytes(url, onProgress)
    }

    private class SystemConsole : HostConsole {
        override fun println(message: String) = kotlin.io.println(message)

        override suspend fun readLine(prompt: String): String? = withContext(Dispatchers.IO) {
            print(prompt)
            System.out.flush()
            readlnOrNull()
        }
    }
}
