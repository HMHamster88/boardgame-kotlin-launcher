package boardgame.launcher.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** [Http] implementation that works on both the JVM and Android (no java.net.http). */
class UrlConnectionHttp : Http, ProgressHttp {

    override suspend fun getText(url: String): String? =
        getBytes(url)?.toString(Charsets.UTF_8)

    override suspend fun getBytes(url: String): ByteArray? = getBytes(url) { _, _ -> }

    override suspend fun getBytes(url: String, onProgress: (received: Long, total: Long) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
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
                            in 200..299 -> {
                                val total = connection.contentLengthLong
                                return@withContext connection.inputStream.use { stream ->
                                    readWithProgress(stream, total, onProgress)
                                }
                            }
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

    private fun readWithProgress(
        stream: InputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ): ByteArray {
        val buffer = ByteArray(64 * 1024)
        val output = ByteArrayOutputStream()
        var received = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            received += read
            onProgress(received, total)
        }
        return output.toByteArray()
    }
}
