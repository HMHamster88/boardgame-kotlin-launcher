package spike.android

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val output = TextView(this).apply {
            textSize = 13f
            setPadding(48, 64, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(output) })

        output.text = "running spike checks…"

        Thread {
            val results = SpikeChecks.run(applicationContext)
            runOnUiThread { output.text = results.joinToString("\n") }
        }.start()
    }
}
