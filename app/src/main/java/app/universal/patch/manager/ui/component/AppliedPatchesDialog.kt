package app.universal.patch.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.ui.component.bundle.BundleTopBar
import app.universal.patch.manager.ui.component.bundle.PatchItem
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.R
import org.koin.compose.koinInject

data class AppliedPatchBundleUi(
    val uid: Int,
    val title: String,
    val version: String?,
    val patchInfos: List<PatchInfo>,
    val fallbackNames: List<String>,
    val bundleAvailable: Boolean,
    val hasUpdate: Boolean = false,
    val fallbackDetails: Map<String, app.universal.patch.manager.data.room.profile.PatchProfilePayload.PatchDisplayInfo> = emptyMap()
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppliedPatchesDialog(
    bundles: List<AppliedPatchBundleUi>,
    onDismissRequest: () -> Unit,
    @StringRes titleRes: Int = R.string.applied_patches,
    @StringRes sectionHeaderRes: Int? = null,
    @StringRes sectionSubtitleRes: Int? = null,
    optionsByBundle: Options = emptyMap(),
    @StringRes confirmTextRes: Int? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true
) {
    val prefs: PreferencesManager = koinInject()
    val searchEngineHost by prefs.searchEngineHost.getAsState()
    FullscreenDialog(onDismissRequest = onDismissRequest) {
        Scaffold(
            topBar = {
                BundleTopBar(
                    title = stringResource(titleRes),
                    onBackClick = onDismissRequest,
                    backIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                )
            },
            bottomBar = {
                if (confirmTextRes != null && onConfirm != null) {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismissRequest) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = onConfirm,
                                enabled = confirmEnabled
                            ) {
                                Text(stringResource(confirmTextRes))
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            if (bundles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.applied_patches_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            val listState = rememberLazyListState()
            Box(modifier = Modifier.padding(paddingValues)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (sectionHeaderRes != null) {
                        item(key = "section-header") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge,
                                tonalElevation = 2.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(sectionHeaderRes),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    sectionSubtitleRes?.let { subtitleRes ->
                                        Text(
                                            text = stringResource(subtitleRes),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(bundles, key = AppliedPatchBundleUi::uid) { bundle ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val bundleTitle = buildString {
                                append(bundle.title)
                                bundle.version?.takeIf { it.isNotBlank() }?.let {
                                    append(" (")
                                    append(it)
                                    append(")")
                                }
                            }

                            Text(
                                text = bundleTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            bundle.patchInfos.forEachIndexed { index, patch ->
                                val versionKey = "versions-$index"
                                val optionsKey = "options-$index"
                                var expandVersions by rememberSaveable(bundle.uid, patch.name, versionKey) { mutableStateOf(false) }
                                var expandOptions by rememberSaveable(bundle.uid, patch.name, optionsKey) { mutableStateOf(false) }
                                val bundleOptions = optionsByBundle[bundle.uid]
                                val patchOptionValues = bundleOptions?.get(patch.name)

                                PatchItem(
                                    patch = patch,
                                    expandVersions = expandVersions,
                                    onExpandVersions = { expandVersions = !expandVersions },
                                    expandOptions = expandOptions,
                                    onExpandOptions = { expandOptions = !expandOptions },
                                    searchEngineHost = searchEngineHost,
                                    showCompatibilityMeta = false,
                                    showOptionValues = true,
                                    optionValues = patchOptionValues
                                )

                                if (index != bundle.patchInfos.lastIndex) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }

                            if (bundle.fallbackNames.isNotEmpty()) {
                                if (bundle.patchInfos.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.applied_patches_bundle_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                } else if (!bundle.bundleAvailable) {
                                    Text(
                                        text = stringResource(R.string.applied_patches_bundle_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                }

                                bundle.fallbackNames.forEach { patchName ->
                                    val details = bundle.fallbackDetails[patchName]
                                    Text(
                                        text = "\u2022 ${details?.label ?: patchName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                                    )
                                    details?.description?.takeIf { it.isNotBlank() }?.let { description ->
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Scrollbar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Scrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyListScrollbar(listState, modifier, prominent = true)
}
