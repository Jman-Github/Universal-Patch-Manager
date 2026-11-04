package app.universal.patch.manager.network.runtime

enum class RuntimeDownloadStage { PREPARING, DOWNLOADING, VERIFYING, FINISHING }

/** Transfer bytes include any partial download resumed from disk. */
data class RuntimeDownloadProgress(
    val catalogId: String?,
    val name: String,
    val stage: RuntimeDownloadStage,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null
) {
    // Verification and activation are separate work; 100% downloaded is not ready to use.
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 && stage == RuntimeDownloadStage.DOWNLOADING }
            ?.let { (bytesDownloaded.coerceAtLeast(0).toDouble() / it).toFloat().coerceIn(0f, 1f) }
}
