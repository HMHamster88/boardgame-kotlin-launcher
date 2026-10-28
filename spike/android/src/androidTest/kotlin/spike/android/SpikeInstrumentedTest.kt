package spike.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpikeInstrumentedTest {

    @Test
    fun pluginDexLoadsAndKtorCioServes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val results = SpikeChecks.run(context)
        val failures = results.filter { it.startsWith("FAIL") }
        assertTrue("Failures:\n" + results.joinToString("\n"), failures.isEmpty())
    }
}
