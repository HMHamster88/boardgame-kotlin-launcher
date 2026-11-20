package boardgame.launcher.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private enum class Tab { GAME, CONSOLE }

    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var consoleView: LinearLayout
    private lateinit var consoleLog: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var gameTab: TextView
    private lateinit var consoleTab: TextView
    private lateinit var consoleInput: EditText
    private var selectedTab = Tab.GAME
    private var consoleListener: (() -> Unit)? = null

    private val accentColor = Color.rgb(46, 125, 50)
    private val tabTextColor = Color.rgb(90, 90, 90)
    private val selectedTabColor = Color.rgb(232, 245, 233)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app targets SDK 35, so Android 15+ draws edge-to-edge. The content is padded by the
        // system-bar and IME insets, otherwise the header sits under the status bar and the tab bar
        // under the navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.WHITE)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    swipeRefresh.isRefreshing = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) swipeRefresh.isRefreshing = false
                }
            }
        }

        // Chrome-style pull-to-refresh: only starts when the page is scrolled to the very top.
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accentColor)
            setProgressBackgroundColorSchemeColor(Color.WHITE)
            setOnRefreshListener { webView.reload() }
            addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                swipeRefresh,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        consoleView = buildConsoleView().apply { visibility = View.GONE }
        content.addView(
            consoleView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        gameTab = createTab("Game")
        consoleTab = createTab("Console")
        gameTab.setOnClickListener { selectTab(Tab.GAME) }
        consoleTab.setOnClickListener { selectTab(Tab.CONSOLE) }

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        tabBar.addView(gameTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        tabBar.addView(consoleTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        setContentView(root)
        selectTab(Tab.GAME)

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

        consoleListener = {
            runOnUiThread {
                if (selectedTab == Tab.CONSOLE) refreshConsole(scrollToBottom = true)
            }
        }
        consoleListener?.let { AppConsole.addListener(it) }

        startForegroundService(Intent(this, ServerService::class.java))
        waitForServerAndLoad()
    }

    private fun createTab(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        contentDescription = "$label tab"
    }

    private fun selectTab(tab: Tab) {
        selectedTab = tab
        val gameSelected = tab == Tab.GAME

        webView.visibility = if (gameSelected) View.VISIBLE else View.GONE
        consoleView.visibility = if (gameSelected) View.GONE else View.VISIBLE
        styleTab(gameTab, gameSelected)
        styleTab(consoleTab, !gameSelected)

        if (!gameSelected) refreshConsole(scrollToBottom = true)
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(if (selected) accentColor else tabTextColor)
        tab.setBackgroundColor(if (selected) selectedTabColor else Color.WHITE)
    }

    private fun buildConsoleView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        val clear = TextView(this).apply {
            text = "Clear"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { AppConsole.clear() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(8), 0)
        }
        header.addView(clear)
        container.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        consoleLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        consoleScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(consoleLog)
        }
        container.addView(consoleScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        consoleInput = EditText(this).apply {
            hint = "command (help)"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(40, 40, 40))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ ->
                runConsoleCommand()
                true
            }
        }
        val run = Button(this).apply {
            text = "Run"
            setOnClickListener { runConsoleCommand() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        inputRow.addView(consoleInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(run)
        container.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return container
    }

    private fun runConsoleCommand() {
        val command = consoleInput.text.toString().trim()
        if (command.isEmpty()) return
        if (command == "exit" || command == "quit") {
            selectTab(Tab.GAME)
            return
        }
        consoleInput.setText("")
        AppConsole.println("> $command")
        AppConsole.runCommand(command) { result ->
            runOnUiThread { if (result.isNotBlank()) AppConsole.println(result) }
        }
    }

    private fun refreshConsole(scrollToBottom: Boolean = false) {
        consoleLog.text = AppConsole.snapshot()
        if (scrollToBottom) {
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
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
                    "<h3>Server did not start</h3><p>Open the Console tab for details.</p>",
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (selectedTab == Tab.CONSOLE) {
            selectTab(Tab.GAME)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        consoleListener?.let { AppConsole.removeListener(it) }
        consoleListener = null
        webView.destroy()
        super.onDestroy()
    }
}
