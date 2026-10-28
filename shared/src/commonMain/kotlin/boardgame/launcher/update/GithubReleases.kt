package boardgame.launcher.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class AssetJson(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val digest: String? = null
)

@Serializable
private data class ReleaseJson(
    @SerialName("tag_name") val tagName: String = "",
    val assets: List<AssetJson> = emptyList()
)

/** GitHub `releases/latest` client: one repository per module, `assets[0]` is the artifact. */
class GithubReleases(private val http: Http) : ReleaseApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(homepage: String): Release? {
        val url = homepage.trimEnd('/').toApiRepo() + "/releases/latest"
        val text = http.getText(url) ?: return null
        val release = runCatching { json.decodeFromString<ReleaseJson>(text) }.getOrNull() ?: return null
        if (release.tagName.isBlank()) return null
        return Release(
            version = release.tagName.removePrefix("v"),
            assets = release.assets.map { asset ->
                ReleaseAsset(
                    name = asset.name,
                    url = asset.url,
                    sha256 = asset.digest?.removePrefix("sha256:")?.takeIf { it.isNotBlank() }
                )
            }
        )
    }

    private fun String.toApiRepo(): String = when {
        startsWith("https://github.com/") ->
            "https://api.github.com/repos/" + removePrefix("https://github.com/")
        else -> this
    }
}
