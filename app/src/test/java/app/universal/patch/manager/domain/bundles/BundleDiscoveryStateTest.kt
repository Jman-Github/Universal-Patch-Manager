package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.CatalogFilters
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class BundleDiscoveryStateTest {
    @Test fun restoresBothTabsAndEveryFilterFromStorage() {
        val original = BundleDiscoveryState(
            appsTab = true, filtersExpanded = false,
            bundlesFilters = CatalogFilters(query = "music", packageName = "app.example@1.2",
                sourceUrl = "https://example.com/patches", version = "v3", bundleType = "Morphe:V1",
                release = "prerelease", sort = "BUNDLE_NAME_ASC", exactPackage = true),
            appsFilters = CatalogFilters(query = "video", packageName = "org.example", release = "all",
                bundleType = "ReVanced:v22", sort = "UPDATED_ASC"),
            bundlesScroll = DiscoveryScrollPosition(40, 17), appsScroll = DiscoveryScrollPosition(21, 89)
        )
        assertEquals(original, BundleDiscoveryState.decode(original.encode()))
    }

    @Test fun toleratesMissingUnknownAndCorruptSavedValues() {
        assertEquals(BundleDiscoveryState(), BundleDiscoveryState.decode(""))
        assertEquals(BundleDiscoveryState(), BundleDiscoveryState.decode("invalid"))
        assertEquals(BundleDiscoveryState(), BundleDiscoveryState.decode("{}"))
        val future = BundleDiscoveryState.decode("""{"appsTab":true,"newField":42}""")
        assertTrue(future.appsTab)
        assertEquals("latest", future.bundlesFilters.release)
        assertEquals("all", future.appsFilters.release)
    }

    @Test fun changingFiltersResetsOnlyThatTabsPosition() {
        val before = BundleDiscoveryState(bundlesScroll = DiscoveryScrollPosition(20, 5),
            appsScroll = DiscoveryScrollPosition(10, 2))
        val unchanged = before.withFilters(false, before.bundlesFilters)
        assertEquals(before, unchanged)
        val after = before.withFilters(false, before.bundlesFilters.copy(sourceUrl = "source", version = "v1"))
        assertEquals(DiscoveryScrollPosition(), after.bundlesScroll)
        assertEquals(before.appsScroll, after.appsScroll)
        assertEquals(before.appsFilters, after.appsFilters)
    }

    @Test fun aDisposedListCannotOverwriteANewerQueriesPosition() {
        val old = CatalogFilters(query = "old")
        val current = BundleDiscoveryState(bundlesFilters = CatalogFilters(query = "new"),
            bundlesScroll = DiscoveryScrollPosition(7, 10))
        assertEquals(current, current.withScroll(false, old, DiscoveryScrollPosition(100, 50)))
        val restored = BundleDiscoveryState.decode(
            """{"bundlesScroll":{"index":-5,"offset":-2},"appsScroll":{"index":2,"offset":3}}""")
        assertEquals(DiscoveryScrollPosition(), restored.bundlesScroll)
        assertEquals(DiscoveryScrollPosition(2, 3), restored.appsScroll)
    }

    @Test fun openingAnAppSelectsItsBundlesAndKeepsTheBundleSort() {
        val before = BundleDiscoveryState(appsTab = true,
            bundlesFilters = CatalogFilters(sort = "BUNDLE_NAME_DESC"),
            appsFilters = CatalogFilters(query = "find app", sourceUrl = "source", release = "all"),
            bundlesScroll = DiscoveryScrollPosition(30, 4), appsScroll = DiscoveryScrollPosition(12, 8))
        val after = before.showBundlesForPackage("app.selected")
        assertFalse(after.appsTab)
        assertEquals("app.selected", after.bundlesFilters.packageName)
        assertEquals("", after.bundlesFilters.query)
        assertTrue(after.bundlesFilters.exactPackage)
        assertEquals("BUNDLE_NAME_DESC", after.bundlesFilters.sort)
        assertEquals("source", after.bundlesFilters.sourceUrl)
        assertEquals(before.appsFilters, after.appsFilters)
        assertEquals(before.appsScroll, after.appsScroll)
        assertEquals(DiscoveryScrollPosition(), after.bundlesScroll)
    }

    @Test fun navigationSeesLatestStateBeforeOrderedWritesFinish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writes = Channel<BundleDiscoveryState>(Channel.UNLIMITED)
        try {
            var first = true
            val store = BundleDiscoveryStateStore(BundleDiscoveryState(), persist = {
                if (first) {
                    first = false
                    entered.complete(Unit)
                    releaseWrite.await()
                }
                writes.send(BundleDiscoveryState.decode(it.encode()))
            }, scope = scope)
            store.update { it.copy(appsTab = true) }
            withTimeout(5_000) { entered.await() }
            store.update { it.withFilters(true, CatalogFilters(query = "first search", release = "all")) }
            store.update { it.withFilters(true, CatalogFilters(query = "last search", release = "release")) }
            // A new navigation entry reads the same app-scoped state, without awaiting the disk.
            assertEquals("last search", store.state.value.appsFilters.query)
            assertTrue(store.state.value.appsTab)
            releaseWrite.complete(Unit)
            withTimeout(5_000) {
                assertEquals("", writes.receive().appsFilters.query)
                val saved = writes.receive()
                assertEquals(store.state.value, saved)
                assertEquals("release", saved.appsFilters.release)
            }
        } finally { scope.cancel() }
    }

    @Test fun importingTheFiltersFlagKeepsTheRestOfTheBrowsingState() {
        val saved = BundleDiscoveryState(
            appsTab = true, filtersExpanded = true,
            appsFilters = CatalogFilters(query = "video", release = "all"),
            appsScroll = DiscoveryScrollPosition(12, 4)
        )
        val collapsed = BundleDiscoveryState.decode(
            BundleDiscoveryState.withFiltersExpanded(saved.encode(), expanded = false)
        )
        assertEquals(saved.copy(filtersExpanded = false), collapsed)
        // Nothing saved yet: the defaults, with only the flag changed.
        assertEquals(
            BundleDiscoveryState(filtersExpanded = false),
            BundleDiscoveryState.decode(BundleDiscoveryState.withFiltersExpanded("", expanded = false))
        )
    }

    @Test fun aFailedWriteDoesNotStopLaterSaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<BundleDiscoveryState>()
        var first = true
        try {
            val store = BundleDiscoveryStateStore(BundleDiscoveryState(), persist = {
                if (first) { first = false; throw IOException("simulated write failure") }
                saved.complete(it)
            }, scope = scope, onWriteError = { failure.complete(Unit) })
            store.update { it.copy(filtersExpanded = false) }
            withTimeout(5_000) { failure.await() }
            store.update { it.copy(appsTab = true) }
            assertEquals(store.state.value, withTimeout(5_000) { saved.await() })
            assertFalse(store.state.value.filtersExpanded)
        } finally { scope.cancel() }
    }
}
