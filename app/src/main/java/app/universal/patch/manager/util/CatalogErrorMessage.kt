package app.universal.patch.manager.util

import android.content.Context
import app.universal.patch.manager.R

/** Localize app-owned generation diagnostics without changing internal retry signals. */
fun Context.catalogErrorMessage(message: String?): String = when {
    message == null -> getString(R.string.patch_bundle_discovery_error)
    message.startsWith("Catalog changed;") && message.contains("release history") ->
        getString(R.string.catalog_changed_history)
    message.startsWith("Catalog changed;") -> getString(R.string.catalog_changed_refresh)
    else -> message
}
