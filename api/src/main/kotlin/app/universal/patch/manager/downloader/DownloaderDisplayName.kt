package app.universal.patch.manager.downloader

/** Removes legacy host branding from user-visible downloader names only. */
fun String.toDownloaderDisplayName(): String {
    val prefix = "ReVanced Manager:"
    val trimmed = trim()
    if (!trimmed.startsWith(prefix, ignoreCase = true)) return this
    return trimmed.substring(prefix.length).trim().ifBlank { this }
}
