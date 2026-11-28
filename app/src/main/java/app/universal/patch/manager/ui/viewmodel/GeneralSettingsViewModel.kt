package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.theme.Theme
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.toHexString
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.nio.file.Path

enum class ThemePreset {
    DEFAULT,
    LIGHT,
    DARK,
    DYNAMIC,
    MONOCHROME,
    PURE_BLACK
}

private data class ThemePresetConfig(
    val theme: Theme,
    val dynamicColor: Boolean = false,
    val pureBlackTheme: Boolean = false,
    val customAccentHex: String = "",
    val customThemeHex: String = ""
)

class GeneralSettingsViewModel(
    val prefs: PreferencesManager
) : ViewModel() {
    companion object {
        private const val tag = "GeneralSettingsViewModel"
        private const val backgroundDirectoryName = "custom_background"
        private const val backgroundFileBaseName = "background_image"
        private val supportedBackgroundExtensions = setOf(
            "jpg",
            "jpeg",
            "png",
            "gif",
            "svg",
            "tif",
            "tiff",
            "webp",
            "bmp",
            "avif",
            "heic",
            "heif"
        )
    }

    private val presetConfigs = mapOf(
        ThemePreset.DEFAULT to ThemePresetConfig(
            theme = Theme.SYSTEM
        ),
        ThemePreset.LIGHT to ThemePresetConfig(
            theme = Theme.LIGHT
        ),
        ThemePreset.DARK to ThemePresetConfig(
            theme = Theme.DARK
        ),
        ThemePreset.DYNAMIC to ThemePresetConfig(
            theme = Theme.SYSTEM,
            dynamicColor = true
        ),
        ThemePreset.MONOCHROME to ThemePresetConfig(
            theme = Theme.SYSTEM
        ),
        ThemePreset.PURE_BLACK to ThemePresetConfig(
            theme = Theme.DARK,
            pureBlackTheme = true
        )
    )

    fun resetThemeSettings() = viewModelScope.launch {
        prefs.resetThemeSettings()
    }

    fun setCustomAccentColor(color: Color?) = viewModelScope.launch {
        val value = color?.toHexString().orEmpty()
        prefs.customAccentColor.update(value)

    }

    fun setCustomThemeColor(color: Color?) = viewModelScope.launch {
        val value = color?.toHexString().orEmpty()
        prefs.customThemeColor.update(value)

    }

    fun setPureBlackOnSystemDark(enabled: Boolean) = viewModelScope.launch {
        prefs.pureBlackOnSystemDark.update(enabled)
    }

    fun setMaterialYouPureBlackTheme(enabled: Boolean) = viewModelScope.launch {
        prefs.materialYouPureBlackTheme.update(enabled)

    }

    fun setCustomBackgroundImageUri(uri: String) = viewModelScope.launch {
        prefs.customBackgroundImageUri.update(uri)
    }

    fun importCustomBackgroundImageUri(context: Context, sourceUri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val appContext = context.applicationContext
        val sourceName = runCatching {
            appContext.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull() ?: sourceUri.lastPathSegment
        val mimeType = runCatching { appContext.contentResolver.getType(sourceUri) }.getOrNull()
        runCatching {
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                val targetUri = writeCustomBackgroundToInternalStorage(
                    appContext = appContext,
                    sourceName = sourceName,
                    mimeType = mimeType,
                    input = input
                )
                prefs.customBackgroundImageUri.update(targetUri.toString())
            } ?: error("Unable to open input stream for selected background image URI")
        }.onFailure {
            Log.w(tag, "Failed to import custom background image from URI", it)
            withContext(Dispatchers.Main) {
                if (it is IllegalArgumentException) {
                    appContext.toast(appContext.getString(R.string.selected_file_not_supported_image))
                } else {
                    appContext.toast(appContext.getString(R.string.failed_to_load_file))
                }
            }
        }
    }

    fun importCustomBackgroundImagePath(context: Context, sourcePath: Path) = viewModelScope.launch(Dispatchers.IO) {
        val appContext = context.applicationContext
        val file = sourcePath.toFile()
        runCatching {
            val mimeType = URLConnection.guessContentTypeFromName(file.name)
            val targetUri = writeCustomBackgroundToInternalStorage(
                appContext = appContext,
                sourceName = file.name,
                mimeType = mimeType,
                input = file.inputStream()
            )
            prefs.customBackgroundImageUri.update(targetUri.toString())
        }.onFailure {
            Log.w(tag, "Failed to import custom background image from file path", it)
            withContext(Dispatchers.Main) {
                if (it is IllegalArgumentException) {
                    appContext.toast(appContext.getString(R.string.selected_file_not_supported_image))
                } else {
                    appContext.toast(appContext.getString(R.string.failed_to_load_file))
                }
            }
        }
    }

    fun clearCustomBackgroundImageUri(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        val appContext = context.applicationContext
        runCatching {
            deleteManagedCustomBackgroundFile(appContext, prefs.customBackgroundImageUri.get())
        }.onFailure {
            Log.w(tag, "Failed to delete managed custom background image", it)
        }
        prefs.customBackgroundImageUri.update("")
    }

    fun setCustomBackgroundImageOpacity(value: Float) = viewModelScope.launch {
        prefs.customBackgroundImageOpacity.update(value.coerceIn(0f, 1f))
    }

    fun setAppLanguage(languageCode: String) = viewModelScope.launch {
        prefs.appLanguage.update(languageCode)
    }

    fun toggleThemePreset(preset: ThemePreset) = viewModelScope.launch {
        val current = getCurrentThemePreset()
        if (current == preset) {
            val resetTheme = if (preset == ThemePreset.LIGHT) Theme.SYSTEM else null
            clearThemePresetSelection(
                resetTheme = resetTheme,
                clearPresetColors = preset == ThemePreset.MONOCHROME
            )
        } else {
            applyThemePreset(preset)
        }
    }

    private suspend fun applyThemePreset(preset: ThemePreset) {
        val config = presetConfigs[preset] ?: return
        prefs.themePresetSelectionEnabled.update(true)
        prefs.theme.update(config.theme)
        prefs.dynamicColor.update(config.dynamicColor)
        prefs.pureBlackTheme.update(config.pureBlackTheme)
        prefs.customAccentColor.update(config.customAccentHex)
        prefs.customThemeColor.update(config.customThemeHex)
        prefs.themePresetSelectionName.update(preset.name)

    }

    private suspend fun clearThemePresetSelection(
        resetTheme: Theme? = null,
        clearPresetColors: Boolean = false
    ) {
        prefs.themePresetSelectionEnabled.update(false)
        prefs.themePresetSelectionName.update("")
        prefs.dynamicColor.update(false)
        prefs.pureBlackTheme.update(false)
        if (clearPresetColors) {
            prefs.customAccentColor.update("")
            prefs.customThemeColor.update("")
        }
        resetTheme?.let { prefs.theme.update(it) }

    }

    private suspend fun getCurrentThemePreset(): ThemePreset? {
        if (!prefs.themePresetSelectionEnabled.get()) return null
        val storedName = prefs.themePresetSelectionName.get().takeIf { it.isNotBlank() }
        return storedName?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
    }

    private fun writeCustomBackgroundToInternalStorage(
        appContext: Context,
        sourceName: String?,
        mimeType: String?,
        input: InputStream
    ): Uri {
        val directory = File(appContext.managerStorageContext.filesDir, backgroundDirectoryName)
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val extension = resolveBackgroundImageExtension(sourceName, mimeType)
        val targetFile = File(directory, "$backgroundFileBaseName.$extension")

        // Keep only one managed background image file.
        directory.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name.startsWith(backgroundFileBaseName) && existing != targetFile) {
                existing.delete()
            }
        }

        val temporaryFile = File(directory, "$backgroundFileBaseName.tmp")
        input.use { source ->
            temporaryFile.outputStream().use { destination ->
                source.copyTo(destination)
            }
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!temporaryFile.renameTo(targetFile)) {
            temporaryFile.inputStream().use { source ->
                targetFile.outputStream().use { destination ->
                    source.copyTo(destination)
                }
            }
            temporaryFile.delete()
        }

        return Uri.fromFile(targetFile)
    }

    private fun resolveBackgroundImageExtension(sourceName: String?, mimeType: String?): String {
        val fromName = sourceName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        val fromMime = mimeType?.lowercase()?.let {
            when {
                it.contains("jpeg") -> "jpg"
                it.contains("png") -> "png"
                it.contains("gif") -> "gif"
                it.contains("svg") -> "svg"
                it.contains("tiff") -> "tiff"
                it.contains("webp") -> "webp"
                else -> null
            }
        }

        val resolved = fromName ?: fromMime
        val normalized = when (resolved) {
            "jpeg" -> "jpg"
            "tif" -> "tiff"
            else -> resolved
        }
        if (normalized == null || normalized !in supportedBackgroundExtensions) {
            throw IllegalArgumentException("Unsupported background image type.")
        }
        return normalized
    }

    private fun deleteManagedCustomBackgroundFile(appContext: Context, uriString: String) {
        if (uriString.isBlank()) return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (!uri.scheme.equals("file", ignoreCase = true)) return
        val filePath = uri.path?.takeIf { it.isNotBlank() } ?: return
        val file = File(filePath)
        val managedDirectory = File(appContext.managerStorageContext.filesDir, backgroundDirectoryName)
        val managedPath = runCatching { managedDirectory.canonicalFile.toPath() }.getOrNull() ?: return
        val filePathCanonical = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return
        if (filePathCanonical.startsWith(managedPath)) {
            file.delete()
        }
    }
}
