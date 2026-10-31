package app.universal.patch.manager.util

import app.universal.patch.manager.data.platform.SharedApkFiles
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.PackageManager.NameNotFoundException
import androidx.core.content.pm.PackageInfoCompat
import android.content.pm.Signature
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import android.net.Uri
import androidx.compose.runtime.Immutable
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.createSession
import ru.solrudev.ackpine.uninstaller.parameters.UninstallParametersDsl
import app.universal.patch.manager.patcher.split.SplitApkFiles
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.security.MessageDigest

@Immutable
@Parcelize
data class AppInfo(
    val packageName: String,
    val patches: Int?,
    val packageInfo: PackageInfo?
) : Parcelable

private const val ANDROID_SHELL_PACKAGE_NAME = "com.android.shell"

internal fun displayInstallerPackageName(
    installingPackageName: String?,
    initiatingPackageName: String?,
    recordedCustomInstallerPackageName: String? = null
): String? {
    val installingPackage = installingPackageName?.takeIf { it.isNotBlank() }
    val initiatingPackage = initiatingPackageName?.takeIf { it.isNotBlank() }
    val recordedCustomInstallerPackage =
        recordedCustomInstallerPackageName?.takeIf { it.isNotBlank() }

    // Shizuku performs the installation as Shell. Prefer the installer UPM launched when it is
    // available; the initiating package remains the fallback for records created before it was
    // persisted.
    if (installingPackage == ANDROID_SHELL_PACKAGE_NAME) {
        return recordedCustomInstallerPackage
            ?: initiatingPackage?.takeUnless { it == ANDROID_SHELL_PACKAGE_NAME }
    }

    return installingPackage
        ?: initiatingPackage?.takeUnless { it == ANDROID_SHELL_PACKAGE_NAME }
        ?: recordedCustomInstallerPackage
}

@SuppressLint("QueryPermissionsNeeded")
class PM(
    private val app: Application,
    patchBundleRepository: PatchBundleRepository,
    private val uninstaller: PackageUninstaller
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    val application: Application get() = app

    val appList = patchBundleRepository.appListBundleInfoFlow.map { bundles ->
        val compatibleApps = scope.async {
            val compatiblePackages = bundles
                .flatMap { (_, bundle) -> bundle.patches }
                .flatMap { it.compatiblePackages.orEmpty() }
                .groupingBy { it.packageName }
                .eachCount()

            compatiblePackages.keys.map { pkg ->
                getPackageInfo(pkg)?.let { packageInfo ->
                    AppInfo(
                        pkg,
                        compatiblePackages[pkg],
                        packageInfo
                    )
                } ?: AppInfo(
                    pkg,
                    compatiblePackages[pkg],
                    null
                )
            }
        }

        val installedApps = scope.async {
            getInstalledPackages().map { packageInfo ->
                AppInfo(
                    packageInfo.packageName,
                    0,
                    packageInfo
                )
            }
        }

        if (compatibleApps.await().isNotEmpty()) {
            (compatibleApps.await() + installedApps.await())
                .distinctBy { it.packageName }
                .sortedWith(
                    compareByDescending<AppInfo> {
                        it.packageInfo != null && (it.patches ?: 0) > 0
                    }.thenByDescending {
                        it.patches
                    }.thenBy {
                        it.packageInfo?.label()
                    }.thenBy { it.packageName }
                )
        } else {
            emptyList()
        }
    }.flowOn(Dispatchers.IO)

    val installedAppList = flow {
        emit(
            getInstalledPackages()
                .map { packageInfo ->
                    AppInfo(
                        packageName = packageInfo.packageName,
                        patches = 0,
                        packageInfo = packageInfo
                    )
                }
                .sortedWith(
                    compareBy<AppInfo> { it.packageInfo?.label()?.lowercase() ?: it.packageName.lowercase() }
                        .thenBy { it.packageName }
                )
        )
    }.flowOn(Dispatchers.IO)

    private fun getInstalledPackages(flags: Int = 0): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            app.packageManager.getInstalledPackages(PackageInfoFlags.of(flags.toLong()))
        else
            app.packageManager.getInstalledPackages(flags)

    fun getPackagesWithFeature(feature: String) =
        getInstalledPackages(PackageManager.GET_CONFIGURATIONS)
            .filter { pkg ->
                pkg.reqFeatures?.any { it.name == feature } ?: false
            }

    fun getPackagesWithFeatures(features: Set<String>) =
        getInstalledPackages(PackageManager.GET_CONFIGURATIONS)
            .filter { pkg ->
                pkg.reqFeatures?.any { it.name in features } ?: false
            }

    fun getPackageInfo(packageName: String, flags: Int = 0): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                app.packageManager.getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
            else
                app.packageManager.getPackageInfo(packageName, flags)
        } catch (_: NameNotFoundException) {
            null
        }

    @Suppress("DEPRECATION")
    fun getInstallerLabel(
        packageName: String,
        recordedCustomInstallerPackageName: String? = null
    ): String? {
        val installerPackageName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo = app.packageManager.getInstallSourceInfo(packageName)
                displayInstallerPackageName(
                    sourceInfo.installingPackageName,
                    sourceInfo.initiatingPackageName,
                    recordedCustomInstallerPackageName
                )
            } else {
                displayInstallerPackageName(
                    app.packageManager.getInstallerPackageName(packageName),
                    null,
                    recordedCustomInstallerPackageName
                )
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return getApplicationInfo(installerPackageName)
            ?.let { info ->
                runCatching { info.loadLabel(app.packageManager).toString() }.getOrNull()
            }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: installerPackageName
    }

    @Suppress("DEPRECATION")
    fun isPlayStoreInstallerSource(packageName: String): Boolean = runCatching {
        val installerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            app.packageManager.getInstallerPackageName(packageName)
        }
        installerPackageName == PLAY_STORE_INSTALLER_PACKAGE
    }.getOrDefault(false)

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/598
    @Suppress("DEPRECATION")
    fun getApplicationInfo(packageName: String, flags: Int = 0): ApplicationInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getApplicationInfo(
                    packageName,
                    ApplicationInfoFlags.of(flags.toLong())
                )
            } else {
                app.packageManager.getApplicationInfo(packageName, flags)
            }
        } catch (_: NameNotFoundException) {
            null
        }

    fun getPackageInfo(file: File, includeSigning: Boolean = false): PackageInfo? {
        val resolvedFile = SharedApkFiles.resolve(file)
        val path = if (SplitApkPreparer.isSplitArchive(resolvedFile)) {
            SplitApkFiles.baseApk(
                resolvedFile,
                app.cacheDir.resolve("split-package-info"),
                SplitApkPreparer.splitApkEntryNames(resolvedFile)
            ).absolutePath
        } else resolvedFile.absolutePath
        val baseFlags = (
            PackageManager.GET_META_DATA.toLong() or
                PackageManager.GET_ACTIVITIES.toLong()
            )
        val signingFlags = if (includeSigning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES.toLong()
            }
        } else {
            0L
        }
        val flags = baseFlags or signingFlags
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageArchiveInfo(path, flags.toInt())
        } ?: return null

        // This is needed in order to load label and icon.
        pkgInfo.applicationInfo?.apply {
            sourceDir = path
            publicSourceDir = path
        }

        return pkgInfo
    }

    /**
     * Resolves an APK label against that APK's own resource table. PackageManager label loading can
     * resolve the resource ID against a currently mounted package and return an unrelated string.
     */
    fun getArchiveLabel(file: File, packageInfo: PackageInfo? = getPackageInfo(file)): String? {
        val info = packageInfo ?: return null
        val applicationInfo = info.applicationInfo ?: return null
        applicationInfo.nonLocalizedLabel?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return cleanLabel(it, info.packageName) }
        if (applicationInfo.labelRes == 0) return null

        val assetManager = runCatching {
            AssetManager::class.java.getDeclaredConstructor().newInstance()
        }.getOrNull() ?: return null
        return try {
            val addAssetPath = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPath.invoke(assetManager, applicationInfo.sourceDir ?: file.absolutePath) as? Int ?: 0
            if (cookie == 0) return null
            val resources = Resources(
                assetManager,
                app.resources.displayMetrics,
                app.resources.configuration
            )
            runCatching { resources.getText(applicationInfo.labelRes).toString() }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { cleanLabel(it, info.packageName) }
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { assetManager.close() }
            }
        }
    }

    fun getSignature(packageInfo: PackageInfo): Signature? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo
                ?.apkContentsSigners
                ?.lastOrNull()
                ?: packageInfo.signingInfo
                    ?.signingCertificateHistory
                    ?.lastOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.lastOrNull()
        }

    fun hasSplitApks(packageInfo: PackageInfo): Boolean =
        packageInfo.applicationInfo?.splitSourceDirs
            ?.map(::File)
            ?.any(File::exists)
            ?: false

    fun isSystemApp(packageInfo: PackageInfo): Boolean {
        val flags = packageInfo.applicationInfo?.flags ?: return false
        val systemFlags =
            android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return flags and systemFlags != 0
    }

    fun PackageInfo.label(): String {
        val raw = this.applicationInfo!!.loadLabel(app.packageManager).toString()
        return cleanLabel(raw, this.packageName)
    }

    fun getVersionCode(packageInfo: PackageInfo) = PackageInfoCompat.getLongVersionCode(packageInfo)

    fun getSignature(packageName: String): Signature =
        // Get the last signature from the list because we want the newest one if SigningInfo.getSigningCertificateHistory() was used.
        PackageInfoCompat.getSignatures(app.packageManager, packageName).last()

    @SuppressLint("InlinedApi")
    fun hasSignature(packageName: String, signature: ByteArray) = PackageInfoCompat.hasSignatures(
        app.packageManager,
        packageName,
        mapOf(signature to PackageManager.CERT_INPUT_RAW_X509),
        false
    )

    suspend fun uninstallPackage(
        pkg: String,
        config: UninstallParametersDsl.() -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        uninstaller.createSession(pkg) {
            confirmation = Confirmation.IMMEDIATE
            config()
        }.await()
    }

    fun launch(pkg: String) = app.packageManager.getLaunchIntentForPackage(pkg)?.let {
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(it)
    }

    fun canInstallPackages() = app.packageManager.canRequestPackageInstalls()

    fun requestInstallPackagesPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (canInstallPackages()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        return false
    }

    private fun cleanLabel(raw: String, packageName: String): String {
        return cleanPackageLabel(raw, packageName)
    }

}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/commit/068686f7cde2d2307eb19164a5837036a097039b
/**
 * Whether a label is an identifier the app never meant to show, rather than a name it chose.
 *
 * Apps without a real label fall back to their package or a launcher class, and only those are
 * worth reducing to a last segment. A brand that simply contains a dot must survive, so a dotted
 * label only qualifies with the shape of a package: no spaces, three or more segments that each
 * start with a letter or underscore, and a lowercase top-level domain in front.
 */
private fun looksLikeIdentifierLabel(label: String, packageName: String): Boolean {
    if (label.any(Char::isWhitespace)) return false
    if (packageName.isNotEmpty() && label.contains(packageName)) return true
    val segments = label.split('.')
    if (segments.size < 3) return false
    if (!segments.all(::isIdentifierSegment)) return false
    return segments.first().none(Char::isUpperCase)
}

private fun isIdentifierSegment(segment: String): Boolean {
    val first = segment.firstOrNull() ?: return false
    return (first.isLetter() || first == '_') && segment.all { it.isLetterOrDigit() || it == '_' }
}

/**
 * Reduces a launcher label to the part worth showing.
 * Kept free of Android APIs so the identifier rules can be tested directly.
 */
internal fun cleanPackageLabel(raw: String, packageName: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    if (!looksLikeIdentifierLabel(trimmed, packageName)) return trimmed

    val base = trimmed.substringAfterLast('.')
    val withoutSuffix = base.removeSuffix("Application")
    val candidate = withoutSuffix.ifBlank { base }
    return candidate.ifBlank { trimmed }
}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/598
fun File.sha256OrNull(): String? = runCatching {
    if (!isFile) return@runCatching null
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (!Thread.currentThread().isInterrupted) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    if (Thread.currentThread().isInterrupted) return@runCatching null
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}.getOrNull()

data class InstalledPackageSnapshot(
    val lastUpdateTime: Long,
    val apkHashes: List<String>?
) {
    fun changedSince(previous: InstalledPackageSnapshot?): Boolean =
        previous == null ||
            lastUpdateTime != previous.lastUpdateTime ||
            (apkHashes != null &&
                previous.apkHashes != null &&
                apkHashes != previous.apkHashes)

    fun matches(apkFiles: List<File>): Boolean {
        val installedHashes = apkHashes ?: return false
        val expectedHashes = apkFiles.map { file ->
            file.sha256OrNull() ?: return false
        }.sorted()
        return installedHashes == expectedHashes
    }
}

fun PM.installedPackageSnapshot(
    packageName: String,
    includeHashes: Boolean = true
): InstalledPackageSnapshot? {
    val packageInfo = getPackageInfo(packageName) ?: return null
    val applicationInfo = getApplicationInfo(packageName)
    val installedApks = applicationInfo?.let { info ->
        buildList {
            info.sourceDir
                ?.takeIf(String::isNotBlank)
                ?.let { add(File(it)) }
            info.splitSourceDirs
                ?.map(::File)
                ?.let(::addAll)
        }
    }.orEmpty()
    val hashes = installedApks
        .takeIf { includeHashes && it.isNotEmpty() }
        ?.map(File::sha256OrNull)
        ?.takeIf { values -> values.none { it == null } }
        ?.filterNotNull()
        ?.sorted()
    return InstalledPackageSnapshot(
        lastUpdateTime = packageInfo.lastUpdateTime,
        apkHashes = hashes
    )
}
