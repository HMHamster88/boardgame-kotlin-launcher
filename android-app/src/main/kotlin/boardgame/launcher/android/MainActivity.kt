package boardgame.launcher.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app targets SDK 35, so Android 15+ draws edge-to-edge. The WebView is therefore
        // padded by the system-bar insets, otherwise the page header sits under the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.WHITE)
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
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
            runOnUiThread {
                webView.loadData(
                    "<h3>Server did not start</h3><p>Check logcat for boardgame.launcher.android.</p>",
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

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
