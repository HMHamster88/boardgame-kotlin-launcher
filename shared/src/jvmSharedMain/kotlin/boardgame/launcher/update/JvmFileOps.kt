package boardgame.launcher.update

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** JVM/Android implementation of [FileOps] (both targets expose java.io/java.util.zip). */
class JvmFileOps : FileOps {

    override fun exists(path: String): Boolean = File(path).exists()

    override fun ensureDir(path: String) {
        File(path).mkdirs()
    }

    override fun readText(path: String): String? {
        val file = File(path)
        return if (file.isFile) file.readText() else null
    }

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    override fun listDirs(path: String): List<String> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    override fun unzip(bytes: ByteArray, targetDir: String) {
        val root = File(targetDir).canonicalFile
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(root, entry.name)
                if (!file.canonicalPath.startsWith(root.path + File.separator)) continue
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    override fun move(from: String, to: String) {
        val source = File(from).toPath()
        val target = File(to).toPath()
        runCatching {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
