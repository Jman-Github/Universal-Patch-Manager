package app.universal.patch.manager.ui.component

import androidx.annotation.DrawableRes
import app.universal.patch.manager.R

/**
 * Provider previews are available without downloading or installing an APK.
 * Artwork from helper-for-morphe; see third-party/downloader-catalog-icons.NOTICE.
 * https://github.com/rushiranpise/helper-for-morphe/tree/fee1355ffbc9c0ea94b1bcdd85e092171aee014f/app/src/main/res
 */
@DrawableRes
internal fun downloaderCatalogPreviewIcon(repoUrl: String, assetSelector: String): Int? =
    when (repoUrl.trimEnd('/')) {
        "https://github.com/brosssh/revanced-manager-downloaders" -> when (
            assetSelector.removePrefix("revanced-manager-").removeSuffix("-downloader")
        ) {
            "apkcombo" -> R.drawable.ic_downloader_catalog_apkcombo
            "apkmirror" -> R.drawable.ic_downloader_catalog_apkmirror
            "apkpure" -> R.drawable.ic_downloader_catalog_apkpure
            "play-store" -> R.drawable.ic_downloader_catalog_play_store
            else -> null
        }
        "https://github.com/rushiranpise/helper-for-morphe" ->
            if (assetSelector == "apk-download-helper") R.drawable.ic_downloader_catalog_helper else null
        else -> null
    }
