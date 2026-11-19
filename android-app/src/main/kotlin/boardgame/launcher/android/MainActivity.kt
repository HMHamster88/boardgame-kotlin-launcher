package boardgame.launcher.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var consoleOverlay: View? = null
    private var consoleLog: TextView? = null
    private var consoleScroll: ScrollView? = null
    private var consoleListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app targets SDK 35, so Android 15+ draws edge-to-edge. The content is padded by the
        // system-bar and IME insets, otherwise the header sits under the status bar and the console
        // input under the keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.WHITE)
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val consoleButton = TextView(this).apply {
            text = ">_"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(30, 14, 30, 14)
            contentDescription = "Console"
            setOnClickListener { showConsole() }
        }
        root.addView(
            consoleButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM
            ).apply { setMargins(0, 0, 24, 24) }
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true

        startForegroundService(Intent(this, ServerService::class.java))
        waitForServerAndLoad()
    }

    private fun waitForServerAndLoad() {
        Thread {
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (serverReady()) {
                    runOnUiThread { webView.loadUrl("http://127.0.0.1:${ServerService.PORT}/") }
                    return@Thread
                }
                Thread.sleep(300)
            }
            AppConsole.println("[ui] server did not start within 60 s")
            runOnUiThread {
                webView.loadData(
                    "<h3>Server did not start</h3><p>Open the &gt;_ console for details.</p>",
                    "text/html",
                    "utf-8"
                )
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serverReady(): Boolean = try {
        val connection = URL("http://127.0.0.1:${ServerService.PORT}/server-info").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun showConsole() {
        if (consoleOverlay != null) return

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            isClickable = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 8, 8, 8)
        }
        val title = TextView(this).apply {
            text = "Console"
            textSize = 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clear = Button(this).apply {
            text = "Clear"
            setOnClickListener { AppConsole.clear() }
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { hideConsole() }
        }
        header.addView(title)
        header.addView(clear)
        header.addView(close)

        val log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setTextIsSelectable(true)
            setPadding(16, 8, 16, 8)
        }
        val scroll = ScrollView(this).apply { addView(log) }
        consoleLog = log
        consoleScroll = scroll

        val input = EditText(this).apply {
            hint = "command (help)"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val run = Button(this).apply {
            text = "Run"
            setOnClickListener {
                val command = input.text.toString().trim()
                if (command.isEmpty()) return@setOnClickListener
                if (command == "exit" || command == "quit") {
                    hideConsole()
                    return@setOnClickListener
                }
                input.setText("")
                AppConsole.println("> $command")
                AppConsole.runCommand(command) { result ->
                    runOnUiThread { if (result.isNotBlank()) AppConsole.println(result) }
                }
            }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 12)
        }
        inputRow.addView(input)
        inputRow.addView(run)

        overlay.addView(header)
        overlay.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(inputRow)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        consoleOverlay = overlay

        val listener = { runOnUiThread { if (consoleOverlay != null) refreshConsole() } }
        consoleListener = listener
        AppConsole.addListener(listener)
        refreshConsole()
    }

    private fun refreshConsole() {
        val log = consoleLog ?: return
        val scroll = consoleScroll ?: return
        if (consoleOverlay == null) return
        log.text = AppConsole.snapshot()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideConsole() {
        val overlay = consoleOverlay ?: return
        consoleListener?.let { AppConsole.removeListener(it) }
        consoleListener = null
        root.removeView(overlay)
        consoleOverlay = null
        consoleLog = null
        consoleScroll = null
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (consoleOverlay != null) hideConsole() else super.onBackPressed()
    }

    override fun onDestroy() {
        hideConsole()
        webView.destroy()
        super.onDestroy()
    }
}
