package boardgame.launcher.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** [Http] implementation that works on both the JVM and Android (no java.net.http). */
class UrlConnectionHttp : Http {

    override suspend fun getText(url: String): String? =
        getBytes(url)?.toString(Charsets.UTF_8)

    override suspend fun getBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            var current = URL(url)
            repeat(6) {
                val connection = current.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "boardgame-kotlin-launcher")
                try {
                    when (val code = connection.responseCode) {
                        in 200..299 -> return@withContext connection.inputStream.use { it.readBytes() }
                        in 300..399 -> {
                            val location = connection.getHeaderField("Location") ?: return@withContext null
                            current = URL(current, location)
                        }
                        else -> return@withContext null
                    }
                } finally {
                    connection.disconnect()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
