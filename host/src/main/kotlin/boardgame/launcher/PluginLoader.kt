package boardgame.launcher

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

/** Platform-specific plugin classloading (desktop: URLClassLoader, Android: DexClassLoader). */
interface PluginLoader {
    fun <T : Any> load(file: File, service: Class<T>, parent: ClassLoader?): T

    /** Releases classloaders created so far (used when hot-reloading plugins in development). */
    fun close() {}
}

class UrlPluginLoader : PluginLoader {
    private val loaders = CopyOnWriteArrayList<URLClassLoader>()

    override fun <T : Any> load(file: File, service: Class<T>, parent: ClassLoader?): T {
        val loader = URLClassLoader(arrayOf(file.toURI().toURL()), parent ?: javaClass.classLoader)
        loaders += loader
        val iterator = ServiceLoader.load(service, loader).iterator()
        if (!iterator.hasNext()) {
            throw IllegalStateException("No ${service.simpleName} service in ${file.name}")
        }
        return iterator.next()
    }

    override fun close() {
        loaders.forEach { loader -> runCatching { loader.close() } }
        loaders.clear()
    }
}
