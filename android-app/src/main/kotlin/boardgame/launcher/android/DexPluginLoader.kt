package boardgame.launcher.android

import android.content.Context
import boardgame.launcher.PluginLoader
import dalvik.system.DexClassLoader
import java.io.File
import java.util.ServiceLoader

/** Loads dual-format plugin jars (classes.dex + resources) from the app's files dir. */
class DexPluginLoader(private val context: Context) : PluginLoader {

    override fun <T : Any> load(file: File, service: Class<T>, parent: ClassLoader?): T {
        check(file.isFile) { "Plugin archive not found: ${file.absolutePath}" }
        // Android 14+ requires dynamically loaded files to be read-only before loading.
        check(file.setReadOnly()) { "Could not mark ${file.name} read-only" }

        val loader = DexClassLoader(
            file.absolutePath,
            context.codeCacheDir.absolutePath,
            null,
            parent ?: context.classLoader
        )
        val iterator = ServiceLoader.load(service, loader).iterator()
        check(iterator.hasNext()) { "No ${service.simpleName} service in ${file.name}" }
        return iterator.next()
    }
}
