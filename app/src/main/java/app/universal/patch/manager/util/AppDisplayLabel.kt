package app.universal.patch.manager.util

import android.content.Context
import android.content.pm.PackageInfo

internal fun resolveAppDisplayLabel(
    context: Context,
    packageInfo: PackageInfo?,
    defaultText: String? = null,
    requireMetadataLabel: Boolean = false,
): String? {
    val packageName = packageInfo?.packageName
    fun clean(raw: String?): String? = if (requireMetadataLabel) {
        actualAppMetadataLabel(raw, packageName)
    } else raw?.let { cleanDisplayLabel(it, packageName) }
    val launcherLabel = packageName
        ?.let { loadInstalledLauncherLabel(context, it) }
        .let(::clean)
        ?.takeIf { it.isNotBlank() && it != packageName }
    if (launcherLabel != null) return launcherLabel

    val installedLabel = packageName
        ?.let { loadInstalledLabel(context, it) }
        .let(::clean)
        ?.takeIf { it.isNotBlank() && it != packageName }
    if (installedLabel != null) return installedLabel

    val localLabel = runCatching {
        packageInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString()
    }.getOrNull()
    val cleanedLocal = clean(localLabel)?.let { cleaned ->
        cleaned.takeIf { it.isNotBlank() && cleaned != packageName }
    }
    if (!cleanedLocal.isNullOrBlank()) return cleanedLocal

    return packageInfo?.applicationInfo?.nonLocalizedLabel?.toString()
        ?.takeIf { !requireMetadataLabel || actualAppMetadataLabel(it, packageName) != null }
        ?.takeIf { it.isNotBlank() }
        ?: packageName?.takeUnless { requireMetadataLabel }
        ?: defaultText
}

/** Package/class-name guesses must not suppress a real application-name lookup. */
internal fun actualAppMetadataLabel(raw: String?, packageName: String?): String? {
    val label = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (packageName != null && (label == packageName || label.startsWith("$packageName."))) return null
    if (looksLikePackageLabel(label) && label.endsWith("Application")) return null
    if (shouldFallbackUnderscoreLabel(label, packageName)) return null
    return label
}

private fun cleanDisplayLabel(raw: String, packageName: String?): String {
    val trimmed = raw.trim()
    if (shouldFallbackUnderscoreLabel(trimmed, packageName)) {
        packageName?.let(::fallbackLabelFromPackageName)?.let { return it }
    }
    val pkg = packageName.orEmpty()
    if (pkg.isNotEmpty() && (trimmed.startsWith(pkg) || trimmed.contains(pkg))) {
        val candidate = trimmed.substringAfterLast('.')
        val withoutSuffix = candidate.removeSuffix("Application")
        return withoutSuffix.ifBlank { candidate }.ifBlank { trimmed }
    }
    if (trimmed.endsWith("Application")) {
        val withoutSuffix = trimmed.removeSuffix("Application")
        return withoutSuffix.substringAfterLast('.').ifBlank { withoutSuffix }
    }
    return trimmed
}

private fun shouldFallbackUnderscoreLabel(label: String, packageName: String?): Boolean {
    if ('_' !in label) return false
    val normalizedPackageName = packageName.orEmpty()
    if (normalizedPackageName.isNotBlank() && label.contains(normalizedPackageName, ignoreCase = true)) {
        return true
    }
    return looksLikePackageLabel(label)
}

private fun looksLikePackageLabel(label: String): Boolean {
    val segments = label.split('.')
    if (segments.size < 3) return false
    return segments.all { segment ->
        segment.isNotBlank() && segment.all { it.isLetterOrDigit() || it == '_' }
    }
}

private fun fallbackLabelFromPackageName(packageName: String): String {
    val tail = packageName.substringAfterLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
    if (tail.isBlank()) return packageName
    return tail.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { segment ->
            segment.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }
}

@Suppress("DEPRECATION")
private fun loadInstalledLabel(context: Context, packageName: String): String? =
    runCatching {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        appInfo.loadLabel(context.packageManager).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

private fun loadInstalledLauncherLabel(context: Context, packageName: String): String? = runCatching {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: return@runCatching null
    launchIntent.resolveActivityInfo(context.packageManager, 0)
        ?.loadLabel(context.packageManager)
        ?.toString()
}.getOrNull()?.takeIf { it.isNotBlank() }
