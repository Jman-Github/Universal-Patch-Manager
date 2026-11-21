package app.universal.patch.manager.domain.repository

import org.junit.Assert.*
import org.junit.Test

class BundleUpdateNetworkPolicyTest {
    @Test fun offlineIsBlockedRegardlessOfDownloadPermissions() {
        for (unmetered in listOf(false, true))
            for (allowMetered in listOf(false, true))
                for (allowUnsafe in listOf(false, true))
                    assertEquals(BundleUpdateNetworkBlock.OFFLINE,
                        bundleUpdateNetworkBlock(false, unmetered, allowMetered, allowUnsafe))
    }

    @Test fun meteredNetworkRequiresPermission() {
        assertEquals(BundleUpdateNetworkBlock.METERED,
            bundleUpdateNetworkBlock(true, false, false))
        assertNull(bundleUpdateNetworkBlock(true, false, true))
        assertNull(bundleUpdateNetworkBlock(true, false, false, allowUnsafe = true))
    }

    @Test fun unmeteredConnectionNeedsNoOverride() {
        assertNull(bundleUpdateNetworkBlock(true, true, false))
    }
}
