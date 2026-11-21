package app.universal.patch.manager.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.appcompat.content.res.AppCompatResources
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.ui.component.AnnotatedLinkText
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.viewmodel.AboutViewModel.Companion.getSocialIcon
import app.universal.patch.manager.util.longPressOnly
import app.universal.patch.manager.util.openUrl
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.withHapticFeedback
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(
    onBackClick: () -> Unit,
    navigate: (Settings.Destination) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService<ClipboardManager>() }
    val managerVersionCopiedMessage = stringResource(R.string.manager_version_copied)
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var showNoticeDialog by rememberSaveable { mutableStateOf(false) }
    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }
    val managerVersion = remember { BuildConfig.VERSION_NAME }
    val managerVersionLabel = remember(managerVersion) {
        "v${managerVersion.removePrefix("v").removePrefix("V")}"
    }
    // painterResource() is broken on release builds for some reason.
    val icon = rememberDrawablePainter(drawable = remember {
        AppCompatResources.getDrawable(context, R.drawable.ic_launcher_foreground)
    })

    val githubButtons = remember(context) {
        listOf(
            AboutLink(
                titleRes = R.string.github,
                vectorIcon = getSocialIcon("GitHub"),
                url = "https://github.com/Jman-Github/universal-patch-manager"
            )
        )
    }
    val creditButtons = remember {
        listOf(
            AboutLink(
                titleRes = R.string.revanced_manager_credit,
                subtitleRes = R.string.revanced_manager_credit_subtext,
                drawableIconRes = R.drawable.ic_credit_revanced,
                url = "https://github.com/ReVanced/revanced-manager"
            ),
            AboutLink(
                titleRes = R.string.morphe_manager_credit,
                subtitlePrefixRes = R.string.morphe_manager_credit_prefix,
                subtitleLinkText = "https://morphe.software",
                subtitleSuffixRes = R.string.morphe_manager_credit_suffix,
                drawableIconRes = R.drawable.ic_credit_morphe,
                url = "https://github.com/MorpheApp/morphe-manager"
            )
        )
    }
    val licensingItems = remember {
        listOf(
            AboutSectionItem(
                titleRes = R.string.notice,
                supportingRes = R.string.notice_description,
                icon = Icons.Outlined.Description,
                onClick = { showNoticeDialog = true }
            ),
            AboutSectionItem(
                titleRes = R.string.open_source_licenses,
                supportingRes = R.string.open_source_licenses_description,
                icon = Icons.Outlined.Gavel,
                onClick = { showLicensesDialog = true }
            )
        )
    }

    if (showNoticeDialog) {
        NoticeDialog(onDismiss = { showNoticeDialog = false })
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.About) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        val scrollState = rememberScrollState()
        app.universal.patch.manager.ui.component.ColumnWithScrollbar(
            state = scrollState,
            thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingsSearchHighlight(
                targetKey = R.string.about_revanced_manager,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                ExpressiveSettingsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(highlightModifier),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Image(
                            modifier = Modifier.size(92.dp),
                            painter = icon,
                            contentDescription = stringResource(R.string.app_name)
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics {
                                hideFromAccessibility()
                            }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val copyManagerVersion = {
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Manager version",
                                            managerVersionLabel
                                        )
                                    )
                                    context.toast(managerVersionCopiedMessage)
                                }
                            }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            Text(
                                text = managerVersionLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.longPressOnly(
                                    label = stringResource(R.string.copy_to_clipboard),
                                    onLongPress = copyManagerVersion
                                )
                            )
                            Text(
                                text = " (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        AnnotatedLinkText(
                            text = stringResource(R.string.revanced_manager_description),
                            linkLabel = stringResource(R.string.here),
                            url = "https://github.com/Jman-Github/Universal-Patch-Manager#-features",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.about_donate,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                ExpressiveSettingsCard(
                    modifier = Modifier.fillMaxWidth().then(highlightModifier),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.about_donate),
                        supportingContent = stringResource(R.string.about_donate_description),
                        leadingContent = {
                            Box(Modifier.size(56.dp)) {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Favorite, contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        AsyncImage(
                                            model = "https://github.com/Jman-Github.png?size=128",
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier.size(24.dp).align(Alignment.BottomEnd),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Favorite, contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        },
                        onClick = {
                            context.openUrl("https://github.com/sponsors/Jman-Github?frequency=one-time&sponsor=Jman-Github")
                        }
                    )
                }
            }

            ExpressiveSettingsCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                githubButtons.forEachIndexed { index, button ->
                    SettingsSearchHighlight(
                        targetKey = button.titleRes,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        ExpressiveSettingsItem(
                            modifier = highlightModifier,
                            headlineContent = stringResource(button.titleRes),
                            leadingContent = {
                                AboutLinkIcon(button = button)
                            },
                            onClick = { context.openUrl(button.url) }
                        )
                    }
                    if (index != githubButtons.lastIndex) {
                        ExpressiveSettingsDivider()
                    }
                }
            }

            ExpressiveSettingsCard(
                modifier = Modifier
                    .fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.credits,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    Text(
                        text = stringResource(R.string.credits),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .then(highlightModifier)
                    )
                }
                ExpressiveSettingsDivider()
                creditButtons.forEachIndexed { index, button ->
                    SettingsSearchHighlight(
                        targetKey = button.titleRes,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        ExpressiveSettingsItem(
                            modifier = highlightModifier,
                            headlineContent = stringResource(button.titleRes),
                            supportingContent = button.subtitleRes?.let { stringResource(it) },
                            supportingContentSlot = if (button.subtitleRes == null && button.subtitlePrefixRes != null && button.subtitleLinkText != null) {
                                {
                                    CreditSubtitleLinkText(
                                        prefix = stringResource(button.subtitlePrefixRes),
                                        link = button.subtitleLinkText,
                                        suffix = button.subtitleSuffixRes?.let { stringResource(it) }.orEmpty()
                                    )
                                }
                            } else {
                                null
                            },
                            leadingContent = { AboutLinkIcon(button = button) },
                            onClick = { context.openUrl(button.url) }
                        )
                    }
                    if (index != creditButtons.lastIndex) {
                        ExpressiveSettingsDivider()
                    }
                }
            }

            ExpressiveSettingsCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SettingsSearchHighlight(
                        targetKey = R.string.licensing,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        Text(
                            text = stringResource(R.string.licensing),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = highlightModifier
                        )
                    }
                    licensingItems.forEachIndexed { index, item ->
                        SettingsSearchHighlight(
                            targetKey = item.titleRes,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            ExpressiveSettingsItem(
                                modifier = highlightModifier,
                                headlineContent = stringResource(item.titleRes),
                                supportingContent = stringResource(item.supportingRes),
                                leadingContent = { Icon(item.icon, contentDescription = null) },
                                onClick = item.onClick
                            )
                        }
                        if (index != licensingItems.lastIndex) {
                            ExpressiveSettingsDivider()
                        }
                    }
                }
            }
        }
    }
}

private data class AboutLink(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val subtitleRes: Int? = null,
    @androidx.annotation.StringRes val subtitlePrefixRes: Int? = null,
    @androidx.annotation.StringRes val subtitleSuffixRes: Int? = null,
    val subtitleLinkText: String? = null,
    val vectorIcon: ImageVector? = null,
    @DrawableRes val drawableIconRes: Int? = null,
    val url: String
)

private data class AboutSectionItem(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val supportingRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun AboutLinkIcon(button: AboutLink) {
    val context = LocalContext.current
    when {
        button.drawableIconRes != null -> {
            val painter = rememberDrawablePainter(
                drawable = remember(button.drawableIconRes) {
                    AppCompatResources.getDrawable(context, button.drawableIconRes)
                }
            )
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
        }
        button.vectorIcon != null -> {
            Icon(
                button.vectorIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun CreditSubtitleLinkText(
    prefix: String,
    link: String,
    suffix: String,
) {
    val context = LocalContext.current
    val annotated = remember(prefix, link, suffix) {
        buildAnnotatedString {
            append(prefix.trimEnd())
            append(" ")
            pushStringAnnotation(tag = "URL", annotation = link)
            withStyle(SpanStyle(color = Color(0xFF64B5F6))) {
                append(link)
            }
            pop()
            append(suffix)
        }
    }

    AutoLinkLikeText(
        text = annotated,
        onLinkClick = { context.openUrl(link) }
    )
}

@Composable
private fun AutoLinkLikeText(
    text: AnnotatedString,
    onLinkClick: () -> Unit
) {
    androidx.compose.foundation.text.ClickableText(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        onClick = { offset ->
            if (text.getStringAnnotations("URL", offset, offset).isNotEmpty()) {
                onLinkClick()
            }
        }
    )
}
