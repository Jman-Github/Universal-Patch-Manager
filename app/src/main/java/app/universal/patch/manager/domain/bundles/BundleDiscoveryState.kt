package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.api.CatalogFilters
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class DiscoveryScrollPosition(val index: Int = 0, val offset: Int = 0) {
    fun normalized() = copy(index = index.coerceAtLeast(0), offset = offset.coerceAtLeast(0))
}

/** Small browsing preferences, separate from the expiring catalog response cache. */
@Serializable
data class BundleDiscoveryState(
    val appsTab: Boolean = false,
    val bundlesFilters: CatalogFilters = CatalogFilters(),
    val appsFilters: CatalogFilters = CatalogFilters(release = "all"),
    val filtersExpanded: Boolean = true,
    val bundlesScroll: DiscoveryScrollPosition = DiscoveryScrollPosition(),
    val appsScroll: DiscoveryScrollPosition = DiscoveryScrollPosition()
) {
    fun filtersFor(appsTab: Boolean) = if (appsTab) appsFilters else bundlesFilters
    fun scrollFor(appsTab: Boolean) = if (appsTab) appsScroll else bundlesScroll

    fun withFilters(appsTab: Boolean, filters: CatalogFilters): BundleDiscoveryState =
        if (filtersFor(appsTab) == filters) this
        else if (appsTab) copy(appsFilters = filters, appsScroll = DiscoveryScrollPosition())
        else copy(bundlesFilters = filters, bundlesScroll = DiscoveryScrollPosition())

    fun withScroll(appsTab: Boolean, expectedFilters: CatalogFilters,
        position: DiscoveryScrollPosition): BundleDiscoveryState {
        // A disposed list for the previous query must not overwrite the new query's position.
        if (filtersFor(appsTab) != expectedFilters) return this
        return if (appsTab) copy(appsScroll = position.normalized())
            else copy(bundlesScroll = position.normalized())
    }

    fun showBundlesForPackage(packageName: String) = withFilters(false,
        appsFilters.copy(packageName = packageName, query = "", exactPackage = true,
            sort = bundlesFilters.sort)).copy(appsTab = false)

    fun encode(): String = stateJson.encodeToString(this)

    companion object {
        private val stateJson = Json { ignoreUnknownKeys = true }
        fun decode(value: String): BundleDiscoveryState = runCatching {
            stateJson.decodeFromString<BundleDiscoveryState>(value).let {
                it.copy(bundlesScroll = it.bundlesScroll.normalized(), appsScroll = it.appsScroll.normalized())
            }
        }.getOrDefault(BundleDiscoveryState())

        /** The saved state [saved] with its filters shown or hidden as [expanded], the rest kept. */
        fun withFiltersExpanded(saved: String, expanded: Boolean): String =
            decode(saved).copy(filtersExpanded = expanded).encode()
    }
}
