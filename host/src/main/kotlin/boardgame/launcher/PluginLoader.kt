package boardgame.launcher

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/** Platform-specific plugin classloading (desktop: URLClassLoader, Android: DexClassLoader). */
interface PluginLoader {
    fun <T : Any> load(file: File, service: Class<T>, parent: ClassLoader?): T
}

class UrlPluginLoader : PluginLoader {
    override fun <T : Any> load(file: File, service: Class<T>, parent: ClassLoader?): T {
        val loader = URLClassLoader(arrayOf(file.toURI().toURL()), parent ?: javaClass.classLoader)
        val iterator = ServiceLoader.load(service, loader).iterator()
        if (!iterator.hasNext()) {
            throw IllegalStateException("No ${service.simpleName} service in ${file.name}")
        }
        return iterator.next()
    }
}
