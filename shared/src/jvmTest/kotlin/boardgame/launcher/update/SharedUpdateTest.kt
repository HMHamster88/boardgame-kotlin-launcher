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

class SharedUpdateTest {

    private class FakeHttp(private val text: String? = null, private val bytes: ByteArray? = null) : Http {
        override suspend fun getText(url: String): String? = text
        override suspend fun getBytes(url: String): ByteArray? = bytes
    }

    private class FakeReleases(private val release: Release?) : ReleaseApi {
        override suspend fun latest(homepage: String): Release? = release
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
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

    private fun tempDir(): File = Files.createTempDirectory("bg-shared-test").toFile()

    @Test
    fun `version comparison`() {
        assertTrue(Version.isNewer("1.2.0", "1.1.9"))
        assertTrue(Version.isNewer("v2.0.0", "1.9.9"))
        assertTrue(!Version.isNewer("1.0.0", "1.0.0"))
        assertTrue(!Version.isNewer("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun `installs release atomically and writes version marker`() = runBlocking {
        val dir = tempDir()
        val target = File(dir, "core/1.2.3").path
        val archive = zip("back/core.jar" to "classes")
        val installer = ModuleInstaller(
            http = FakeHttp(bytes = archive),
            files = JvmFileOps(),
            releases = FakeReleases(null)
        )

        val installed = installer.install(
            Release("1.2.3", listOf(ReleaseAsset("core.zip", "http://example/core.zip", null))),
            target
        )

        assertEquals("1.2.3", installed.version)
        assertTrue(File(target, "back/core.jar").isFile)
        assertEquals("1.2.3", installer.readVersion(target))
        assertEquals("version=1.2.3\n", JvmFileOps().readText("$target/version.properties"))
        assertTrue(!File("$target.tmp").exists())
    }

    @Test
    fun `rejects checksum mismatch`() = runBlocking {
        val dir = tempDir()
        val installer = ModuleInstaller(
            http = FakeHttp(bytes = zip("x.txt" to "x")),
            files = JvmFileOps(),
            releases = FakeReleases(null)
        )

        assertFailsWith<IllegalArgumentException> {
            installer.install(
                Release("1.0.0", listOf(ReleaseAsset("x.zip", "http://example/x.zip", "deadbeef"))),
                File(dir, "target").path
            )
        }
        assertTrue(!File(dir, "target").exists())
    }

    @Test
    fun `github url and asset parsing`() = runBlocking {
        val json = """
            {"tag_name":"v1.4.0","assets":[
              {"name":"module.zip","browser_download_url":"https://example/module.zip","digest":"sha256:abc123"}
            ]}
        """.trimIndent()

        val release = GithubReleases(FakeHttp(text = json)).latest("https://github.com/HMHamster88/boardgame-kotlin-cah")
        assertEquals("1.4.0", release?.version)
        assertEquals("https://example/module.zip", release?.assets?.first()?.url)
        assertEquals("abc123", release?.assets?.first()?.sha256)
    }

    @Test
    fun `zip extraction refuses path traversal`() {
        val dir = tempDir()
        val archive = zip("../escaped.txt" to "nope", "ok.txt" to "yes")

        JvmFileOps().unzip(archive, dir.path)

        assertTrue(File(dir, "ok.txt").isFile)
        assertTrue(!File(dir.parentFile, "escaped.txt").exists())
    }
}
