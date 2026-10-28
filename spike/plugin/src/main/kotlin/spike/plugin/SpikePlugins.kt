package spike.plugin

import spike.api.CoreModule
import spike.api.GameBackModule
import spike.api.GameBackService
import spike.api.GameCore
import spike.api.HostServices
import spike.api.Peer

class SpikeGame : GameBackService {
    override val type: String = "SPIKE"
    override fun hello(): String = "game-plugin-ok"
}

class SpikeGameModule : GameBackModule {
    override fun createService(): GameBackService = SpikeGame()
}

class SpikeCore : CoreModule {
    override val version: String = "1.0.0-spike"

    override fun start(host: HostServices): GameCore {
        host.log("spike core started, app=${host.appName}, dataDir=${host.dataDir}")
        return object : GameCore {
            override fun attach(peer: Peer) {
                peer.send("hello:${host.appName}")
                peer.onMessage { message -> peer.send(message.uppercase()) }
            }

            override fun detach(peer: Peer) {
                host.log("peer detached: ${peer.userId}")
            }

            override suspend fun shutdown() {
                host.log("spike core stopped")
            }
        }
    }
}
