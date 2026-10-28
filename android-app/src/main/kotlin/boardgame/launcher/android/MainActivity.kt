package boardgame.launcher.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
        }
        setContentView(webView)

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
