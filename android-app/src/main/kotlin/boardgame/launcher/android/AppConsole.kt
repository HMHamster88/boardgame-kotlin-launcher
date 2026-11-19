package boardgame.launcher.android

import android.util.Log
import boardgame.api.HostConsole
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app console for the Android launcher: captures host/core output and runs launcher commands.
 * [ServerService] installs the command handler once the host is running; the activity observes the
 * buffered lines.
 */
object AppConsole : HostConsole {
    private const val TAG = "boardgame.launcher"
    private const val LIMIT = 2000

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var commandHandler: ((String, (String) -> Unit) -> Unit)? = null

    override fun println(message: String) {
        Log.i(TAG, message)
        synchronized(lines) {
            lines.addLast(message)
            while (lines.size > LIMIT) lines.removeFirst()
        }
        listeners.forEach { listener -> runCatching { listener() } }
    }

    /** The launcher has no interactive stdin on Android; commands come from the console view. */
    override suspend fun readLine(prompt: String): String? = null

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        listeners.forEach { listener -> runCatching { listener() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun runCommand(command: String, onResult: (String) -> Unit) {
        val handler = commandHandler
        if (handler == null) {
            onResult("launcher is not ready")
            return
        }
        handler(command, onResult)
    }
}
