package boardgame.launcher.update

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreResolverTest {

    private class CountingHttp(private val bytes: ByteArray) : Http {
        var downloads = 0
        override suspend fun getText(url: String): String? = null
        override suspend fun getBytes(url: String): ByteArray {
            downloads++
            return bytes
        }
    }

    private class FixedReleases(private val release: Release?) : ReleaseApi {
        override suspend fun latest(homepage: String): Release? = release
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun tempDir(): File = Files.createTempDirectory("bg-core-test").toFile()

    private fun resolver(dir: File, releases: ReleaseApi, http: Http): CoreResolver {
        val installer = ModuleInstaller(http, JvmFileOps(), releases)
        return CoreResolver(dir, installer, "https://github.com/HMHamster88/boardgame-kotlin-core")
    }

    private fun installedCore(dir: File, version: String): File {
        val target = File(dir, "core/$version")
        File(target, "back").mkdirs()
        File(target, "back/core.jar").writeText("jar")
        File(target, "version.properties").writeText("version=$version\n")
        return target
    }

    @Test
    fun `uses installed core without network when updates are disabled`() = runBlocking {
        val dir = tempDir()
        installedCore(dir, "1.0.0")
        val http = CountingHttp(ByteArray(0))

        val resolved = resolver(dir, FixedReleases(null), http).resolve(checkForUpdates = false)

        assertEquals("1.0.0", resolved.version)
        assertEquals(0, http.downloads)
    }

    @Test
    fun `installs the latest release when nothing is installed`() = runBlocking {
        val dir = tempDir()
        val archive = zip(mapOf("back/core.jar" to "classes"))
        val http = CountingHttp(archive)
        val release = Release("1.2.0", listOf(ReleaseAsset("core.zip", "http://example/core.zip", null)))

        val resolved = resolver(dir, FixedReleases(release), http).resolve(checkForUpdates = true)

        assertEquals("1.2.0", resolved.version)
        assertTrue(resolved.jar.isFile)
        assertEquals("1.2.0", File(dir, "core/1.2.0/version.properties").readText().trim().removePrefix("version="))
        assertEquals(1, http.downloads)
    }

    @Test
    fun `does not download when the installed version is current`() = runBlocking {
        val dir = tempDir()
        installedCore(dir, "1.2.0")
        val http = CountingHttp(ByteArray(0))
        val release = Release("1.2.0", listOf(ReleaseAsset("core.zip", "http://example/core.zip", null)))

        val resolved = resolver(dir, FixedReleases(release), http).resolve(checkForUpdates = true)

        assertEquals("1.2.0", resolved.version)
        assertEquals(0, http.downloads)
    }

    @Test
    fun `picks the newest installed version`() {
        val dir = tempDir()
        installedCore(dir, "1.0.0")
        installedCore(dir, "1.10.0")
        installedCore(dir, "1.9.9")

        val resolved = resolver(dir, FixedReleases(null), CountingHttp(ByteArray(0))).installed()

        assertEquals("1.10.0", resolved?.version)
    }

    @Test
    fun `fails clearly when no core is installed and no release is available`() = runBlocking {
        val dir = tempDir()
        val resolver = resolver(dir, FixedReleases(null), CountingHttp(ByteArray(0)))

        assertFailsWith<IllegalStateException> { resolver.resolve(checkForUpdates = true) }
        Unit
    }
}
