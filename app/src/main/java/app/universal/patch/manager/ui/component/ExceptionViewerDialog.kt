package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.IconButton
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.bundle.BundleTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionViewerDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    FullscreenDialog(
        onDismissRequest = onDismiss,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                BundleTopBar(
                    title = stringResource(R.string.patches_error),
                    onBackClick = onDismiss,
                    backIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.back)
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        text
                                    )
                                    type = "text/plain"
                                }

                                val shareIntent = Intent.createChooser(sendIntent, null)
                                context.startActivity(shareIntent)
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.share)
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            ColumnWithScrollbar(
                modifier = Modifier.padding(paddingValues)
            ) {
                val stackScrollState = rememberScrollState()
                Text(
                    text,
                    modifier = Modifier
                        .consumeHorizontalScroll(stackScrollState)
                )
            }
        }
    }
}
