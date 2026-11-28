package app.universal.patch.manager.domain.repository

internal enum class BundleUpdateNetworkBlock { OFFLINE, METERED }

/** Metered permission never substitutes for an actual network connection. */
internal fun bundleUpdateNetworkBlock(
    connected: Boolean,
    unmetered: Boolean,
    allowMetered: Boolean,
    allowUnsafe: Boolean = false,
): BundleUpdateNetworkBlock? = when {
    !connected -> BundleUpdateNetworkBlock.OFFLINE
    !unmetered && !allowMetered && !allowUnsafe -> BundleUpdateNetworkBlock.METERED
    else -> null
}
