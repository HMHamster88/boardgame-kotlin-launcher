package spike.host

import spike.api.CoreModule
import spike.api.GameBackModule
import spike.api.GameCore
import spike.api.HostServices
import spike.api.Peer
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private var failures = 0

private fun expect(condition: Boolean, message: String) {
    if (!condition) error(message)
}

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        println("OK   $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL $name -> ${t::class.java.name}: ${t.message}")
        t.printStackTrace()
    }
}

private class FakeHost : HostServices {
    override val appName: String = "desktop-spike"
    override val dataDir: File = File(System.getProperty("java.io.tmpdir"), "bg-spike")
    override fun log(message: String) = println("  [host] $message")
}

private class FakePeer : Peer {
    override val userId: String = "peer-1"
    val sent = mutableListOf<String>()
    private var messageHandler: ((String) -> Unit)? = null

    override fun send(text: String) {
        sent += text
    }

    override fun onMessage(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override fun onClose(handler: () -> Unit) = Unit

    fun receive(message: String) {
        val handler = messageHandler ?: error("plugin did not register a message handler")
        handler(message)
    }
}

fun main(args: Array<String>) {
    val zip = File(args.firstOrNull() ?: error("usage: host <plugin.zip>"))
    println("plugin: ${zip.absolutePath} (${zip.length()} bytes)")

    check("zip contains classes.dex") {
        ZipFile(zip).use { archive -> expect(archive.getEntry("classes.dex") != null, "classes.dex missing") }
    }
    check("zip contains .class entries") {
        ZipFile(zip).use { archive ->
            expect(archive.entries().asSequence().any { it.name.endsWith(".class") }, "no .class entries")
        }
    }
    check("zip contains service descriptors") {
        ZipFile(zip).use { archive ->
            expect(archive.getEntry("META-INF/services/spike.api.CoreModule") != null, "CoreModule service missing")
            expect(archive.getEntry("META-INF/services/spike.api.GameBackModule") != null, "GameBackModule service missing")
        }
    }

    val loader = URLClassLoader(arrayOf(zip.toURI().toURL()), HostServices::class.java.classLoader)

    check("game plugin loads by explicit class name through URLClassLoader") {
        val module = loader.loadClass("spike.plugin.SpikeGameModule")
            .getDeclaredConstructor().newInstance() as GameBackModule
        expect(module.createService().hello() == "game-plugin-ok", "unexpected hello() result")
    }

    check("core plugin resolves through ServiceLoader") {
        val coreModule = ServiceLoader.load(CoreModule::class.java, loader).firstOrNull()
            ?: error("CoreModule service not found")
        expect(coreModule.version == "1.0.0-spike", "unexpected version ${coreModule.version}")
    }

    check("core runs: attach, send and echo through the SPI") {
        val coreModule = ServiceLoader.load(CoreModule::class.java, loader).first()
        val core: GameCore = coreModule.start(FakeHost())
        val peer = FakePeer()
        core.attach(peer)
        expect(peer.sent == listOf("hello:desktop-spike"), "unexpected attach messages: ${peer.sent}")
        peer.receive("ping")
        expect(peer.sent == listOf("hello:desktop-spike", "PING"), "unexpected echo messages: ${peer.sent}")
        core.detach(peer)
    }

    println()
    println(if (failures == 0) "DESKTOP SPIKE: PASS" else "DESKTOP SPIKE: FAIL ($failures)")
    exitProcess(if (failures == 0) 0 else 1)
}
