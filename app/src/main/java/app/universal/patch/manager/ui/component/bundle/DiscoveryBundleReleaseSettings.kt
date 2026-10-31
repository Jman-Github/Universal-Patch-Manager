package app.universal.patch.manager.ui.component.bundle

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.*
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.network.utils.getOrThrow
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.universal.patch.manager.ui.screen.BundleHistorySheet
import app.universal.patch.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.universal.patch.manager.util.toast
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import java.net.URLEncoder

private data class DiscoveryReleaseState(
    val sourceUrl: String,
    val settings: RepositoryBundleSettings,
    val version: String?,
    val metadata: ExternalBundleMetadata?
)

@Composable
internal fun DiscoveryBundleReleaseSettings(source: RemotePatchBundle) {
    val repository = koinInject<PatchBundleRepository>()
    val api = koinInject<ExternalBundlesApi>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selection by remember(source.uid, source.endpoint) { mutableStateOf<DiscoveryReleaseState?>(null) }
    var busy by remember(source.uid) { mutableStateOf(false) }
    var history by remember(source.uid) { mutableStateOf<ExternalBundleSnapshot?>(null) }

    LaunchedEffect(source.uid, source.endpoint) {
        selection = withContext(Dispatchers.IO) {
            val metadata = ExternalBundleMetadataStore.read(source.bundleDirectory)
                ?: return@withContext null
            val registry = registryBundleIconSource(source.endpoint, metadata?.sourceUrl,
                metadata?.ownerName, metadata?.repoName, metadata?.isPrerelease, metadata?.downloadUrl)
                ?: return@withContext null
            val version = runCatching { Url(source.endpoint).parameters["version"] }.getOrNull()
                ?.takeUnless { it.equals("latest", true) || it.isBlank() }
            val stored = RepositoryBundleSettingsStore.read(source.bundleDirectory)
            val settings = if (version != null) stored.copy(useSpecificVersion = true)
                else stored.copy(usePrereleases = registry.prerelease ?: stored.usePrereleases,
                    useLatest = registry.prerelease == null, useSpecificVersion = false)
            DiscoveryReleaseState(registry.sourceUrl, settings, version, metadata)
        }
    }
    val state = selection ?: return

    fun apply(settings: RepositoryBundleSettings, version: String? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // History can select a pre-release while the saved tracking channel is Release.
                // Exact versions must not be restricted by that saved channel.
                val channel = if (version != null || settings.useLatest) null else settings.usePrereleases
                val latest = requireNotNull(ExternalBundlesEndpoints.latestBundleUrl(
                    ExternalBundlesEndpoints.REGISTRY_HOST, state.sourceUrl, channel))
                val url = if (version == null) latest else latest.replace("version=latest",
                    "version=" + URLEncoder.encode(version, "UTF-8"))
                repository.updateRemoteEndpoint(source, url, discoverySettings = settings)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                context.toast(failure.message ?: context.getString(R.string.patch_bundle_discovery_error))
            } finally {
                busy = false
            }
        }
    }

    fun openHistory() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val metadata = state.metadata ?: error(context.getString(R.string.patch_bundle_discovery_error))
                val byId = if (metadata.bundleId > 0) api.getBundleById(metadata.bundleId,
                    metadata.apiHost ?: ExternalBundlesEndpoints.REGISTRY_HOST).getOrNull() else null
                val snapshot = requireNotNull(byId ?: api.getBundleForSource(state.sourceUrl,
                    metadata.isPrerelease, metadata.version).getOrThrow())
                require(snapshot.bundleType.isNotBlank())
                history = snapshot.copy(sourceUrl = state.sourceUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                context.toast(failure.message ?: context.getString(R.string.patch_bundle_discovery_error))
            } finally {
                busy = false
            }
        }
    }

    val settings = state.settings
    @Composable fun Toggle(title: Int, description: Int, checked: Boolean, enabled: Boolean, change: () -> Unit) {
        BundleListItem(headlineText = stringResource(title), supportingText = stringResource(description),
            trailingContent = {
                ExpressiveSettingsSwitch(checked = checked, enabled = enabled && !busy,
                    onCheckedChange = { change() })
            }, modifier = Modifier.alpha(if (enabled && !busy) 1f else 0.38f)
                .clickable(enabled = enabled && !busy, onClick = change))
    }
    Toggle(R.string.repository_bundle_prereleases, R.string.repository_bundle_prereleases_description,
        settings.usePrereleases && !settings.useLatest && !settings.useSpecificVersion, !settings.useSpecificVersion && !settings.useLatest) {
        apply(settings.copy(usePrereleases = !settings.usePrereleases, useLatest = false))
    }
    Toggle(R.string.repository_bundle_latest, R.string.repository_bundle_latest_description,
        settings.useLatest && !settings.useSpecificVersion, !settings.useSpecificVersion) {
        apply(settings.copy(useLatest = !settings.useLatest))
    }
    Toggle(R.string.bundle_use_specific_version, R.string.bundle_use_specific_version_description,
        settings.useSpecificVersion, true) {
        if (settings.useSpecificVersion) apply(settings.copy(useSpecificVersion = false)) else openHistory()
    }
    BundleListItem(headlineText = stringResource(R.string.bundle_select_version),
        supportingText = state.version ?: stringResource(R.string.field_not_set),
        modifier = Modifier.alpha(if (settings.useSpecificVersion && !busy) 1f else 0.38f)
            .clickable(enabled = settings.useSpecificVersion && !busy) { openHistory() })

    history?.let { bundle ->
        val viewModel: BundleDiscoveryViewModel = koinViewModel()
        BundleHistorySheet(bundle, viewModel, onDismiss = { history = null }, selectionMode = true,
            onViewPatches = { selected ->
                history = null
                apply(settings.copy(useSpecificVersion = true), selected.version)
            })
    }
}
