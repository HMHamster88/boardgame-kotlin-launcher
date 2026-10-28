package spike.android

import android.content.Context
import android.util.Log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import spike.api.CoreModule
import spike.api.GameBackModule
import spike.api.GameCore
import spike.api.HostServices
import spike.api.Peer
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device proof of the two risky assumptions of the migration:
 *  1. one dual-format plugin artefact loads on Android through DexClassLoader;
 *  2. Ktor CIO can serve HTTP + WebSocket on 127.0.0.1 inside the app process.
 */
object SpikeChecks {
    private const val TAG = "BgSpike"
    private const val ASSET = "spike-module.zip"

    fun run(context: Context): List<String> {
        val results = mutableListOf<String>()

        fun step(name: String, block: () -> Unit) {
            try {
                block()
                results += "OK   $name"
                Log.i(TAG, "OK $name")
            } catch (t: Throwable) {
                results += "FAIL $name -> ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "FAIL $name", t)
            }
        }

        val pluginFile = File(context.filesDir, "plugins/$ASSET")

        step("asset extracted to app files dir") {
            pluginFile.parentFile?.mkdirs()
            context.assets.open(ASSET).use { input ->
                pluginFile.outputStream().use { output -> input.copyTo(output) }
            }
            check(pluginFile.length() > 0) { "empty plugin file" }
        }

        step("downloaded plugin marked read-only (Android 14+ requirement)") {
            check(pluginFile.setReadOnly()) { "setReadOnly() returned false" }
            check(!pluginFile.canWrite()) { "file is still writable" }
        }

        val dexLoader = try {
            dalvik.system.DexClassLoader(
                pluginFile.absolutePath,
                context.codeCacheDir.absolutePath,
                null,
                context.classLoader
            )
        } catch (t: Throwable) {
            results += "FAIL DexClassLoader construction -> ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "FAIL DexClassLoader construction", t)
            return results
        }

        step("game plugin loads through DexClassLoader") {
            val module = dexLoader.loadClass("spike.plugin.SpikeGameModule")
                .getDeclaredConstructor().newInstance() as GameBackModule
            check(module.createService().hello() == "game-plugin-ok") { "unexpected hello()" }
        }

        step("core plugin resolves through ServiceLoader on a DexClassLoader") {
            val coreModule = ServiceLoader.load(CoreModule::class.java, dexLoader).firstOrNull()
                ?: error("CoreModule service not found")
            check(coreModule.version == "1.0.0-spike") { "unexpected version" }
        }

        step("core plugin runs over the SPI (parent-classloader types)") {
            val coreModule = ServiceLoader.load(CoreModule::class.java, dexLoader).first()
            val core: GameCore = coreModule.start(AndroidHost(context))
            val peer = CapturingPeer()
            core.attach(peer)
            check(peer.sent == listOf("hello:android-spike")) { "unexpected attach messages: ${peer.sent}" }
            peer.receive("ping")
            check(peer.sent == listOf("hello:android-spike", "PING")) { "unexpected echo: ${peer.sent}" }
            core.detach(peer)
        }

        step("Ktor CIO serves HTTP + WebSocket on 127.0.0.1") {
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(WebSockets)
                routing {
                    get("/health") { call.respondText("ok") }
                    webSocket("/ws") {
                        for (frame in incoming) {
                            if (frame is Frame.Text) send(Frame.Text(frame.readText().uppercase()))
                        }
                    }
                }
            }
            server.start(wait = false)
            try {
                val port = runBlocking { server.engine.resolvedConnectors().first().port }
                Log.i(TAG, "ktor listening on 127.0.0.1:$port")

                val client = OkHttpClient()
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/health").build())
                    .execute().use { response ->
                        check(response.code == 200) { "http ${response.code}" }
                        check(response.body?.string() == "ok") { "unexpected /health body" }
                    }

                val latch = CountDownLatch(1)
                var received: String? = null
                val socket = client.newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:$port/ws").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            received = text
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (received == null) {
                                Log.e(TAG, "ws failure", t)
                                latch.countDown()
                            } else {
                                // Expected: the server is stopped while the socket is still open.
                                Log.d(TAG, "ws closed after reply: ${t.javaClass.simpleName}")
                            }
                        }
                    }
                )
                socket.send("ping")
                check(latch.await(10, TimeUnit.SECONDS)) { "no websocket response in 10s" }
                socket.close(1000, null)
                check(received == "PING") { "unexpected ws response: $received" }
            } finally {
                server.stop(0, 2000)
            }
        }

        val failures = results.count { it.startsWith("FAIL") }
        results += if (failures == 0) "ANDROID SPIKE: PASS" else "ANDROID SPIKE: FAIL ($failures)"
        return results
    }

    private class AndroidHost(private val context: Context) : HostServices {
        override val appName: String = "android-spike"
        override val dataDir: File = context.filesDir
        override fun log(message: String) {
            Log.i(TAG, message)
        }
    }

    private class CapturingPeer : Peer {
        override val userId: String = "peer-1"
        val sent = mutableListOf<String>()
        private var handler: ((String) -> Unit)? = null

        override fun send(text: String) {
            sent += text
        }

        override fun onMessage(handler: (String) -> Unit) {
            this.handler = handler
        }

        override fun onClose(handler: () -> Unit) = Unit

        fun receive(message: String) {
            val registered = handler ?: error("plugin did not register a message handler")
            registered(message)
        }
    }
}
