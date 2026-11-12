package app.universal.patch.manager.network.downloader

/** Stable identity is independent of the release tag and APK filename version. */
data class DownloaderCatalogItem(
    val id: String,
    val name: String,
    val version: String,
    val repoUrl: String,
    val assetSelector: String,
    val downloadUrl: String,
    val versionKey: String,
    val size: Long?
)
