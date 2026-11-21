package app.universal.patch.manager.patcher.split

import android.app.Application
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.AssetManager
import android.os.Build
import app.universal.patch.manager.util.PM
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SplitArchiveDisplayResolver {
    suspend fun resolvePackageInfo(
        source: File,
        workspace: File,
        pm: PM,
        checkCancelled: () -> Unit
    ): PackageInfo? = withContext(Dispatchers.IO) {
        checkCancelled()
        if (!SplitApkPreparer.isSplitArchive(source, checkCancelled = checkCancelled)) {
            return@withContext pm.getPackageInfo(source)
        }
        workspace.mkdirs()
        val extracted = SplitApkInspector.extractRepresentativeApk(
            source = source,
            workspace = workspace,
            maxExtractedBytes = SplitApkPreparer.MAX_EXTRACTED_ENTRY_BYTES,
            maxArchiveEntries = SplitApkPreparer.MAX_ARCHIVE_ENTRIES,
            validateEntryName = SplitApkPreparer::requireSafeArchiveEntryName,
            requireBase = true,
            checkCancelled = checkCancelled,
            validateEntry = { entry ->
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    SplitApkPreparer.requireSupportedSplitEntry(entry)
                }
            }
        ) ?: return@withContext null
        try {
            pm.getPackageInfo(extracted.file)
        } finally {
            extracted.cleanup()
        }
    }

    suspend fun resolve(
        source: File,
        workspace: File,
        app: Application,
        pm: PM,
        includeIcon: Boolean = true
    ): ResolvedSplitArchiveDisplay? = withContext(Dispatchers.IO) {
        if (!SplitApkPreparer.isSplitArchive(source)) return@withContext null
        workspace.mkdirs()

        val extractionDir = File(workspace, "display-${UUID.randomUUID()}")
        extractionDir.mkdirs()

        var packageInfo: PackageInfo? = null
        var iconDrawable: Drawable? = null
        var label: String? = null

        try {
            val extractedApks = extractRelevantApks(source, extractionDir)
            val baseApk = extractedApks.firstOrNull { SplitApkPreparer.isExplicitBaseApkEntryName(it.name) }
                ?: extractedApks.firstOrNull()
                ?: return@withContext null

            packageInfo = pm.getPackageInfo(baseApk)
            if (packageInfo == null) return@withContext null

            val resourcesSession = createResourcesForApks(app, extractedApks) ?: return@withContext null
            val iconBitmapDrawable = try {
                label = resolveLabel(packageInfo, resourcesSession.resources, app)
                if (includeIcon) {
                    iconDrawable = resolveIcon(packageInfo, resourcesSession.resources, app)
                    iconDrawable?.let { toBitmapDrawable(it, app.resources) }
                } else {
                    null
                }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { resourcesSession.assetManager.close() }
                }
            }

            ResolvedSplitArchiveDisplay(
                packageInfo = packageInfo,
                label = label,
                icon = iconBitmapDrawable
            )
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    private fun extractRelevantApks(source: File, extractionDir: File): List<File> {
        val output = mutableListOf<File>()
        val selectedEntryNames = SplitApkPreparer.splitApkEntryNames(source)

        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name in selectedEntryNames }
                .toList()
            if (entries.isEmpty()) return emptyList()

            val base = entries.firstOrNull { SplitApkPreparer.isExplicitBaseApkEntryName(it.name) }
                ?: entries.filter { !isConfigLikeApkName(it.name) }
                    .maxByOrNull { entry -> entry.size }
            val selected = LinkedHashSet<String>()
            base?.let { selected.add(it.name) }

            val matchingConfigEntries = SplitApkPreparer.selectDeviceMatchingConfigEntryNames(
                entries.map { it.name }
            )
            entries.forEach { entry ->
                if (entry.name in matchingConfigEntries) {
                    selected.add(entry.name)
                }
            }

            if (selected.isEmpty()) {
                selected += entries.first().name
            }

            selected.forEachIndexed { index, name ->
                val entry = zip.getEntry(name) ?: return@forEachIndexed
                val fileName = "${index}-${entry.name.substringAfterLast('/')}"
                val destination = File(extractionDir, fileName)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination.toPath()).use { outputStream ->
                        input.copyTo(outputStream)
                    }
                }
                output += destination
            }
        }

        return output
    }

    private fun isConfigLikeApkName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("config") ||
            lower.contains("split_config") ||
            lower.contains("config.")
    }

    private data class ResourcesSession(
        val resources: Resources,
        val assetManager: AssetManager
    )

    private fun createResourcesForApks(app: Application, apks: List<File>): ResourcesSession? {
        if (apks.isEmpty()) return null
        val assetManager = AssetManager::class.java
            .getDeclaredConstructor()
            .newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        apks.forEach { apk ->
            val cookie = addAssetPath.invoke(assetManager, apk.absolutePath) as? Int ?: 0
            if (cookie == 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { assetManager.close() }
                }
                return null
            }
        }
        return ResourcesSession(
            resources = Resources(assetManager, app.resources.displayMetrics, app.resources.configuration),
            assetManager = assetManager
        )
    }

    private fun resolveLabel(
        packageInfo: PackageInfo,
        resources: Resources,
        app: Application
    ): String {
        val appInfo = packageInfo.applicationInfo ?: return packageInfo.packageName
        appInfo.nonLocalizedLabel?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (appInfo.labelRes != 0) {
            runCatching { resources.getString(appInfo.labelRes) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return runCatching { appInfo.loadLabel(app.packageManager)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: packageInfo.packageName
    }

    private fun resolveIcon(
        packageInfo: PackageInfo,
        resources: Resources,
        app: Application
    ): Drawable? {
        val appInfo = packageInfo.applicationInfo ?: return null
        val iconRes = appInfo.icon
        if (iconRes != 0) {
            val fromResources = runCatching {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    resources.getDrawable(iconRes, null)
                } else {
                    resources.getDrawable(iconRes)
                }
            }.getOrNull()
            if (fromResources != null) return fromResources
        }
        return runCatching { appInfo.loadIcon(app.packageManager) }.getOrNull()
    }

    private fun toBitmapDrawable(drawable: Drawable, resources: Resources): BitmapDrawable {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

}

data class ResolvedSplitArchiveDisplay(
    val packageInfo: PackageInfo,
    val label: String,
    val icon: Drawable?
)
