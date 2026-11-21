package app.universal.patch.manager.domain.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppMetadataRequestLocksTest {
    @Test fun unrelatedPackagesDoNotWaitAndSamePackageIsSerialized() = runBlocking {
        val locks = AppMetadataRequestLocks()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("Aa") { release.await() } }
        var sameRan = false
        val same = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("Aa") { sameRan = true } }
        var otherRan = false
        // These keys have the same String hash; hash collisions must not serialize apps.
        locks.withLock("BB") { otherRan = true }
        assertTrue(otherRan)
        assertFalse(sameRan)
        release.complete(Unit)
        first.join()
        same.join()
        assertTrue(sameRan)
    }

    @Test fun cancellingWaiterDoesNotReleaseAnotherRequestsLock() = runBlocking {
        val locks = AppMetadataRequestLocks()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("app") { release.await() } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("app") { error("Cancelled waiter ran") } }
        cancelled.cancelAndJoin()
        var laterRan = false
        val later = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("app") { laterRan = true } }
        assertFalse(laterRan)
        release.complete(Unit)
        first.join()
        later.join()
        assertTrue(laterRan)
        locks.withLock("app") { /* Can acquire a new lock after all users leave. */ }
    }
}
