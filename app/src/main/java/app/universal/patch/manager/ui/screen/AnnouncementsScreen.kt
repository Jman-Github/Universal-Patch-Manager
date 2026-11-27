package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.design.IconButton
import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.network.dto.ReVancedAnnouncement
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.ScrollableBottomSheet
import app.universal.patch.manager.ui.component.LoadingIndicator
import app.universal.patch.manager.ui.model.navigation.Announcement
import app.universal.patch.manager.ui.viewmodel.AnnouncementSections
import app.universal.patch.manager.ui.viewmodel.AnnouncementsViewModel
import app.universal.patch.manager.util.announcementTagDisplayName
import app.universal.patch.manager.util.announcementTagKey
import app.universal.patch.manager.util.distinctAnnouncementTags
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.R
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(
    onBackClick: () -> Unit,
    onAnnouncementClick: (Announcement.Payload) -> Unit,
    vm: AnnouncementsViewModel = koinViewModel()
) {
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refresh(forceRefresh = true)
    }

    val tags by vm.tags.collectAsStateWithLifecycle(initialValue = null)
    val selectedTags by vm.selectedTags.getAsState()
    val selectedTagKeys = selectedTags.map(::announcementTagKey).toSet()
    val announcementSections by vm.announcementSections.collectAsStateWithLifecycle(initialValue = null)
    val readAnnouncements by vm.readAnnouncements.getAsState()

    if (showFilterSheet) {
        AnnouncementFiltersSheet(
            tags = tags.orEmpty(),
            selectedTagKeys = selectedTagKeys,
            onDismissRequest = { showFilterSheet = false },
            onReset = vm::resetTagSelection,
            onToggleTag = vm::changeTagSelection
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = {
                    Text(
                        text = stringResource(R.string.revanced_announcements),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                },
                onBackClick = onBackClick,
                actions = {
                    if (!tags.isNullOrEmpty()) {
                        IconButton(onClick = { showFilterSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.FilterAlt,
                                contentDescription = stringResource(R.string.announcements_filter_tag)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val sections = announcementSections) {
            null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }

            else -> if (sections.isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_announcements_found),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            } else {
                LazyColumnWithScrollbar(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (sections.activeAnnouncements.isNotEmpty()) {
                        item {
                            AnnouncementSection(
                                announcements = sections.activeAnnouncements,
                                readAnnouncements = readAnnouncements,
                                onAnnouncementClick = { announcement ->
                                    vm.markAnnouncementRead(announcement.id)
                                    onAnnouncementClick(Announcement.Payload.from(announcement))
                                }
                            )
                        }
                    }

                    if (sections.archivedAnnouncements.isNotEmpty()) {
                        item {
                            ArchivedAnnouncementsHeader(
                                expanded = archivedExpanded,
                                onToggle = { archivedExpanded = !archivedExpanded }
                            )
                        }
                    }

                    if (archivedExpanded && sections.archivedAnnouncements.isNotEmpty()) {
                        item {
                            AnnouncementSection(
                                announcements = sections.archivedAnnouncements,
                                readAnnouncements = readAnnouncements,
                                onAnnouncementClick = { announcement ->
                                    vm.markAnnouncementRead(announcement.id)
                                    onAnnouncementClick(Announcement.Payload.from(announcement))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementFiltersSheet(
    tags: List<String>,
    selectedTagKeys: Set<String>,
    onDismissRequest: () -> Unit,
    onReset: () -> Unit,
    onToggleTag: (String) -> Unit
) {
    ScrollableBottomSheet(onDismissRequest = onDismissRequest) {
        ColumnWithScrollbar(
            contentModifier = Modifier.padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.announcements_filter_tag),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = announcementTagKey(tag) in selectedTagKeys,
                        onClick = { onToggleTag(tag) },
                        label = { Text(text = announcementTagDisplayName(tag)) }
                    )
                }
            }
            TextButton(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 16.dp),
                onClick = onReset
            ) {
                Text(stringResource(R.string.reset))
            }
        }
    }
}

@Composable
private fun AnnouncementSection(
    announcements: List<ReVancedAnnouncement>,
    readAnnouncements: Set<String>,
    onAnnouncementClick: (ReVancedAnnouncement) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            announcements.forEachIndexed { index, announcement ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                AnnouncementListItem(
                    announcement = announcement,
                    unread = announcement.id.toString() !in readAnnouncements,
                    onClick = { onAnnouncementClick(announcement) }
                )
            }
        }
    }
}

@Composable
private fun ArchivedAnnouncementsHeader(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        label = "archivedAnnouncementsChevron"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.announcements_show_archived),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.collapse_content) else stringResource(
                R.string.expand_content
            ),
            modifier = Modifier.rotate(rotation)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnouncementListItem(
    announcement: ReVancedAnnouncement,
    unread: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val archived = announcement.archivedAt
        ?.toEpochMilliseconds()
        ?.let { it <= System.currentTimeMillis() }
        ?: false

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(iterations = Int.MAX_VALUE)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${announcement.createdAt.relativeTime(context)} • ${announcement.author}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (unread) {
                Badge(modifier = Modifier.size(6.dp))
            }
            if (archived) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = stringResource(R.string.archived),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        AnnouncementTags(
            tags = announcement.tags,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AnnouncementTags(
    tags: List<String>,
    modifier: Modifier = Modifier
) {
    val displayTags = tags.distinctAnnouncementTags()
    if (displayTags.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        displayTags.forEach { tag ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
