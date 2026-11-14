package app.universal.patch.manager.ui.screen.settings.update

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.settings.Changelog
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.viewmodel.ChangelogsViewModel
import app.universal.patch.manager.util.relativeTime
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogsSettingsScreen(
    onBackClick: () -> Unit,
    vm: ChangelogsViewModel = koinViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.changelog),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (vm.releaseInfo == null) Arrangement.Center else Arrangement.Top
        ) {
            vm.releaseInfo?.let { info ->
                val uriHandler = LocalUriHandler.current
                ExpressiveSettingsCard(
                    modifier = Modifier.padding(16.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Changelog(
                            markdown = info.description.replace("`", ""),
                            version = info.version,
                            publishDate = info.createdAt.relativeTime(LocalContext.current)
                        )
                        info.pageUrl?.let { url ->
                            ExpressiveSettingsDivider()
                            ExpressiveSettingsItem(
                                headlineContent = stringResource(R.string.changelog_open_github),
                                supportingContent = url,
                                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                onClick = { uriHandler.openUri(url) }
                            )
                        }
                    }
                } 
            } ?: ExpressiveSettingsCard(
                modifier = Modifier.padding(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShimmerBox(modifier = Modifier.fillMaxWidth().height(22.dp))
                    ShimmerBox(modifier = Modifier.fillMaxWidth().height(16.dp))
                    ShimmerBox(modifier = Modifier.fillMaxWidth().height(16.dp))
                    ShimmerBox(modifier = Modifier.width(180.dp).height(14.dp))
                }
            }
        }
    }
}
