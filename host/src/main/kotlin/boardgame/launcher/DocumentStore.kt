package boardgame.launcher

import boardgame.api.Storage
import boardgame.api.apiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed document store: `<root>/<collection>/<id>.json`.
 * Writes are atomic (temp file + ATOMIC_MOVE) and reads are plain files, which is enough for the
 * handful of documents this server keeps.
 */
class DocumentStore(private val root: File) : Storage {

    init {
        root.mkdirs()
    }

    override fun get(collection: String, id: String): String? {
        val file = file(collection, id)
        return if (file.isFile) file.readText() else null
    }

    override fun put(collection: String, id: String, json: String) {
        val target = file(collection, id)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(json)
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    override fun delete(collection: String, id: String) {
        file(collection, id).delete()
    }

    override fun getAll(collection: String): List<String> {
        val dir = File(root, collection)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.map { it.readText() }
            ?: emptyList()
    }

    override fun findByField(collection: String, field: String, value: String): String? =
        getAll(collection).firstOrNull { document ->
            val json = runCatching { apiJson.parseToJsonElement(document) }.getOrNull() as? JsonObject
            json?.get(field)?.jsonPrimitive?.content == value
        }

    private fun file(collection: String, id: String): File =
        File(File(root, collection), sanitize(id) + ".json")

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
