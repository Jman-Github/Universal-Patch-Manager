package app.universal.patch.manager.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.universal.patch.manager.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val NOTICE_UNIQUE_ID = "app.universal.patch.manager"

private data class LicenseDefinition(
    val key: String,
    val name: String,
    val url: String?,
    val content: String?,
)

private data class LibraryDefinition(
    val uniqueId: String,
    val name: String,
    val website: String?,
    val licenses: List<LicenseDefinition>,
)

@Composable
fun NoticeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val noticeText = remember {
        context.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
    }

    AboutViewerDialog(
        title = stringResource(R.string.notice),
        onDismiss = onDismiss,
    ) {
        AutoLinkText(
            text = noticeText,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val libraries = remember {
        parseLibraryDefinitions(
            context.resources.openRawResource(R.raw.licenses_index).bufferedReader().use { it.readText() }
        )
    }
    var selectedLibrary by remember { mutableStateOf<LibraryDefinition?>(null) }

    if (selectedLibrary != null) {
        LicenseContentDialog(
            library = selectedLibrary!!,
            onDismiss = { selectedLibrary = null }
        )
    }

    AboutViewerDialog(
        title = stringResource(R.string.open_source_licenses),
        onDismiss = onDismiss,
        scrollable = false
    ) {
        if (libraries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.open_source_licenses_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            app.universal.patch.manager.ui.component.LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize(),
                thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
            ) {
                items(libraries) { library ->
                    ListItem(
                        modifier = Modifier.clickable { selectedLibrary = library },
                        headlineContent = {
                            Text(
                                text = library.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                text = library.website ?: library.licenses.joinToString { it.name },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun LicenseContentDialog(
    library: LibraryDefinition,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val noticeText = remember {
        context.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
    }
    val content = remember(library, noticeText) {
        if (library.uniqueId == NOTICE_UNIQUE_ID) {
            noticeText
        } else {
            buildString {
                library.website?.let { append(it).append("\n\n") }
                library.licenses.forEachIndexed { index, license ->
                    append(license.name)
                    license.url?.let { append("\n").append(it) }
                    license.content?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    if (index != library.licenses.lastIndex) {
                        append("\n\n\n")
                    }
                }
            }
        }
    }

    AboutViewerDialog(
        title = library.name,
        onDismiss = onDismiss,
    ) {
        AutoLinkText(
            text = content,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutViewerDialog(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 420.dp, max = 760.dp)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge
                )
                val contentModifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 620.dp)
                if (scrollable) {
                    app.universal.patch.manager.ui.component.ColumnWithScrollbar(
                        modifier = contentModifier,
                        thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
                    ) { content() }
                } else {
                    Box(modifier = contentModifier) { content() }
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

private fun parseLibraryDefinitions(rawJson: String): List<LibraryDefinition> {
    if (rawJson.isBlank()) return emptyList()
    val root = Json.parseToJsonElement(rawJson).jsonObject
    val licenseDefinitions = root["licenses"]
        ?.jsonObject
        ?.mapValues { (key, value) ->
            val obj = value.jsonObject
            LicenseDefinition(
                key = key,
                name = obj.string("name") ?: key,
                url = obj.string("url"),
                content = obj.string("content")
            )
        }
        .orEmpty()

    return root["libraries"]
        ?.jsonArray
        ?.map { element ->
            val obj = element.jsonObject
            LibraryDefinition(
                uniqueId = obj.string("uniqueId").orEmpty(),
                name = obj.string("name").orEmpty(),
                website = obj.string("website"),
                licenses = obj.stringArray("licenses").mapNotNull { licenseDefinitions[it] }
            )
        }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

@Composable
fun AutoLinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val uriHandler = LocalUriHandler.current
    val urlRegex = Regex("(https?://[\\w./?=&%#-]+)")

    val annotated = remember(text) {
        buildAnnotatedString {
            var lastIndex = 0
            urlRegex.findAll(text).forEach { match ->
                val url = match.value
                append(text.substring(lastIndex, match.range.first))
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(SpanStyle(color = Color(0xFF64B5F6))) {
                    append(url)
                }
                pop()
                lastIndex = match.range.last + 1
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    @Suppress("DEPRECATION")
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color)
    ) { offset ->
        annotated.getStringAnnotations("URL", offset, offset)
            .firstOrNull()
            ?.let { uriHandler.openUri(it.item) }
    }
}
