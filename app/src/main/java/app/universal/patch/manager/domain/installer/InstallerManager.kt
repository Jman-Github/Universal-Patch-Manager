package app.universal.patch.manager.domain.installer

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.InstallerPreferenceTokens
import app.universal.patch.manager.util.PLAY_STORE_INSTALLER_PACKAGE
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun installerTokenMatchesPatchMode(
    token: InstallerManager.Token,
    useMount: Boolean
): Boolean = token != InstallerManager.Token.None &&
    (token == InstallerManager.Token.AutoSaved ||
        token == InstallerManager.Token.RootPlayStore) == useMount

internal fun shouldUseConfiguredInstallerWithoutPrompt(
    chooseInstallerPerInstall: Boolean
): Boolean = !chooseInstallerPerInstall

internal fun persistedInstallerPackageName(
    installType: InstallType,
    selectedInstallerPackageName: String?,
    existingInstallType: InstallType?,
    existingInstallerPackageName: String?
): String? = when (installType) {
    InstallType.CUSTOM -> selectedInstallerPackageName
        ?: existingInstallerPackageName.takeIf { existingInstallType == InstallType.CUSTOM }
    InstallType.MOUNT -> selectedInstallerPackageName
    else -> null
}

// Root mounts stay InstallType.MOUNT for mount lifecycle handling. Keep the explicitly
// selected Play Store attribution in the existing installer-package metadata field.
internal fun usesPersistedPlayStoreMountMode(
    installType: InstallType,
    installerPackageName: String?
): Boolean = installType == InstallType.MOUNT &&
    installerPackageName == PLAY_STORE_INSTALLER_PACKAGE

internal fun packageInfoIsCompleteSingleApk(packageInfo: PackageInfo): Boolean {
    val splitRequiredValue =
        packageInfo.applicationInfo?.metaData?.get("com.android.vending.splits.required")
    val splitRequired = splitRequiredValue == true ||
        splitRequiredValue?.toString()?.equals("true", ignoreCase = true) == true
    return !splitRequired &&
        packageInfo.splitNames.isNullOrEmpty() &&
        packageInfo.applicationInfo?.splitSourceDirs.isNullOrEmpty()
}

internal fun patchedOutputSupportsRootMount(
    patchedPackageName: String?,
    originalPackageName: String,
    patchedIsCompleteSingleApk: Boolean,
    patchedIsSplitDependentBase: Boolean,
    patchedHasSigningCertificate: Boolean,
    installedHasSharedUserId: Boolean,
    hasUsableStockIdentity: Boolean,
    hasUsableSplitStockIdentity: Boolean,
    patchedVersionMatchesSource: Boolean
): Boolean = patchedPackageName == originalPackageName &&
    (patchedIsCompleteSingleApk || patchedIsSplitDependentBase && hasUsableSplitStockIdentity) &&
    patchedHasSigningCertificate &&
    !installedHasSharedUserId &&
    hasUsableStockIdentity &&
    patchedVersionMatchesSource

internal fun rootMountStockIdentityUsable(
    installedMatchesSourceVersion: Boolean,
    installedHasSigningCertificate: Boolean,
    hasStockSource: Boolean,
    stockIdentityCompatible: Boolean
): Boolean {
    val installedCanSupplyStock =
        installedMatchesSourceVersion && installedHasSigningCertificate
    return if (installedCanSupplyStock) {
        !hasStockSource || stockIdentityCompatible
    } else {
        hasStockSource && stockIdentityCompatible
    }
}

internal fun rootMountStockReplacementRequired(
    installedMatchesSourceVersion: Boolean
): Boolean = !installedMatchesSourceVersion

internal fun installerTokenSelectableForPatchedOutput(
    token: InstallerManager.Token,
    useMount: Boolean,
    supportsRootMount: Boolean
): Boolean = installerTokenMatchesPatchMode(token, useMount) ||
    (!useMount && supportsRootMount &&
        (token == InstallerManager.Token.AutoSaved ||
            token == InstallerManager.Token.RootPlayStore))

internal fun rejectMountToken(
    token: InstallerManager.Token,
    target: InstallerManager.InstallTarget,
    allowMount: Boolean
): Boolean = installerTokenMatchesPatchMode(token, useMount = true) &&
    (!allowMount || target == InstallerManager.InstallTarget.UNPATCHED_APP)

class InstallerManager(
    private val app: Application,
    private val prefs: PreferencesManager,
    private val rootInstaller: RootInstaller,
    private val shizukuInstaller: ShizukuInstaller
) {
    val apkSignatureChecksEnabled: Boolean
        get() = !prefs.disableApkSignatureChecks.getBlocking()

    private val packageManager: PackageManager = app.packageManager
    private val authority = InstallerFileProvider.authority(app)
    private val shareDir: File = File(app.cacheDir, SHARE_DIR).apply { mkdirs() }
    private val dummyUri: Uri = InstallerFileProvider.buildUri(app, "dummy.apk")
    private val defaultInstallerComponent: ComponentName? by lazy { resolveDefaultInstallerComponent() }
    private val defaultInstallerPackage: String? get() = defaultInstallerComponent?.packageName
    private val hiddenInstallerPackages: Set<String>
        get() = prefs.installerHiddenComponents.getBlocking()
            .mapNotNull(ComponentName::unflattenFromString)
            .map { it.packageName }
            .toSet()

    fun listEntries(target: InstallTarget, includeNone: Boolean): List<Entry> =
        listEntries(target, includeNone, listOf(APK_MIME_CANDIDATE))

    fun listEntriesForFile(
        target: InstallTarget,
        includeNone: Boolean,
        sourceFile: File
    ): List<Entry> = listEntries(target, includeNone, mimeCandidatesFor(sourceFile))

    private fun listEntries(
        target: InstallTarget,
        includeNone: Boolean,
        componentMimeCandidates: List<InstallerMimeCandidate>
    ): List<Entry> {
        val hiddenPackages = hiddenInstallerPackages
        val entries = mutableListOf<Entry>()

        entryFor(Token.Internal, target, checkRoot = false)?.let(entries::add)
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
        if (componentMimeCandidates == listOf(APK_MIME_CANDIDATE)) {
            entryFor(Token.PlayStore, target, checkRoot = false)?.let(entries::add)
            if (target != InstallTarget.UNPATCHED_APP) {
                entryFor(Token.RootPlayStore, target, checkRoot = false)?.let(entries::add)
                entryFor(Token.AutoSaved, target, checkRoot = false)?.let(entries::add)
            }
        }
        entryFor(Token.Shizuku, target, checkRoot = false)?.let(entries::add)
        entryFor(Token.ShizukuGooglePlay, target, checkRoot = false)?.let(entries::add)

        val activityEntries = queryInstallerActivities(componentMimeCandidates)
            .filter(::isInstallerCandidate)
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                if (isDefaultComponent(component)) return@mapNotNull null
                if (component.packageName in hiddenPackages) return@mapNotNull null
                if (isExcludedDuplicate(component.packageName, info.loadLabel(packageManager)?.toString() ?: info.activityInfo.packageName)) {
                    return@mapNotNull null
                }
                entryFor(
                    Token.Component(component),
                    target,
                    checkRoot = false,
                    componentMimeCandidates = componentMimeCandidates
                )
            }
            .sortedBy { it.label.lowercase() }

        entries += activityEntries

        val customEntries = readCustomInstallerTokens()
            .mapNotNull { token ->
                entryFor(
                    token,
                    target,
                    checkRoot = false,
                    componentMimeCandidates = componentMimeCandidates
                )
            }
            .filterNot { entry ->
                val componentToken = entry.token as? Token.Component ?: return@filterNot false
                componentToken.componentName.packageName in hiddenPackages
            }
            .filterNot { customEntry ->
                entries.any { tokensEqual(it.token, customEntry.token) }
            }
            .sortedBy { it.label.lowercase() }

        entries += customEntries

        if (includeNone) {
            entryFor(Token.None, target, checkRoot = false)?.let(entries::add)
        }

        return entries
    }

    fun describeEntry(token: Token, target: InstallTarget): Entry? = entryFor(token, target)

    fun parseToken(value: String?): Token {
        val token = when (value) {
            InstallerPreferenceTokens.AUTO_SAVED,
            InstallerPreferenceTokens.ROOT -> Token.AutoSaved
            InstallerPreferenceTokens.SYSTEM -> Token.Internal
            InstallerPreferenceTokens.PLAY_STORE -> Token.PlayStore
            InstallerPreferenceTokens.ROOT_PLAY_STORE -> Token.RootPlayStore
            InstallerPreferenceTokens.NONE -> Token.None
            InstallerPreferenceTokens.SHIZUKU -> Token.Shizuku
            InstallerPreferenceTokens.SHIZUKU_PLAY_STORE,
            InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY -> Token.ShizukuGooglePlay
            InstallerPreferenceTokens.INTERNAL, null, "" -> Token.Internal
            else -> ComponentName.unflattenFromString(value)?.let { component ->
                if (isDefaultComponent(component)) Token.Internal else Token.Component(component)
            } ?: Token.Internal
        }
        Log.d(TAG, "parseToken($value) -> ${token.describe()}")
        return token
    }

    fun tokenToPreference(token: Token): String = when (token) {
        Token.Internal -> InstallerPreferenceTokens.INTERNAL
        Token.PlayStore -> InstallerPreferenceTokens.PLAY_STORE
        Token.RootPlayStore -> InstallerPreferenceTokens.ROOT_PLAY_STORE
        Token.AutoSaved -> InstallerPreferenceTokens.AUTO_SAVED
        Token.None -> InstallerPreferenceTokens.NONE
        Token.Shizuku -> InstallerPreferenceTokens.SHIZUKU
        Token.ShizukuGooglePlay -> InstallerPreferenceTokens.SHIZUKU_PLAY_STORE
        is Token.Component -> token.componentName.flattenToString()
    }

    fun getPrimaryToken(): Token = configuredShizukuToken(
        parseToken(prefs.installerPrimary.getBlocking())
    )

    fun getFallbackToken(): Token = configuredShizukuToken(
        parseToken(prefs.installerFallback.getBlocking())
    )

    suspend fun updatePrimaryToken(token: Token) {
        Log.d(TAG, "updatePrimaryToken -> ${token.describe()}")
        if (isShizukuToken(token)) {
            prefs.shizukuInstallAsPlayStore.update(token == Token.ShizukuGooglePlay)
        }
        prefs.installerPrimary.update(tokenToPreference(normalizeStoredShizukuToken(token)))
    }

    suspend fun updateFallbackToken(token: Token) {
        Log.d(TAG, "updateFallbackToken -> ${token.describe()}")
        if (isShizukuToken(token)) {
            prefs.shizukuInstallAsPlayStore.update(token == Token.ShizukuGooglePlay)
        }
        prefs.installerFallback.update(tokenToPreference(normalizeStoredShizukuToken(token)))
    }

    suspend fun updateShizukuPlayStoreMode(enabled: Boolean) {
        val primary = withPlayStoreSource(
            parseToken(prefs.installerPrimary.get()),
            false
        )
        val fallback = withPlayStoreSource(
            parseToken(prefs.installerFallback.get()),
            false
        )
        prefs.installerPrimary.update(tokenToPreference(primary))
        prefs.installerFallback.update(tokenToPreference(fallback))
        prefs.shizukuInstallAsPlayStore.update(enabled)
    }

    private fun configuredShizukuToken(token: Token): Token {
        if (!isShizukuToken(token)) return token
        val enabled = prefs.shizukuInstallAsPlayStore.getBlocking() ||
            token == Token.ShizukuGooglePlay
        return withPlayStoreSource(token, enabled)
    }

    private fun normalizeStoredShizukuToken(token: Token): Token =
        if (token == Token.ShizukuGooglePlay) Token.Shizuku else token

    fun storedCustomInstallerTokens(): List<Token.Component> = readCustomInstallerTokens()

    suspend fun addCustomInstaller(component: ComponentName): Boolean {
        val flattened = component.flattenToString()
        var added = false
        prefs.edit {
            val current = prefs.installerCustomComponents.value
            if (flattened !in current) {
                prefs.installerCustomComponents.value = current + flattened
                added = true
            }
        }
        return added
    }

    suspend fun removeCustomInstaller(component: ComponentName): Boolean {
        val flattened = component.flattenToString()
        var removed = false
        prefs.edit {
            val current = prefs.installerCustomComponents.value
            if (flattened in current) {
                prefs.installerCustomComponents.value = current - flattened
                removed = true
            }
        }
        return removed
    }

    fun suggestInstallerPackages(
        query: String,
        limit: Int = DEFAULT_PACKAGE_SUGGESTION_LIMIT
    ): List<PackageSuggestion> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        val lower = normalized.lowercase()
        val packages = runCatching {
            getInstalledPackagesCompat(PackageManager.GET_ACTIVITIES)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to query installed packages for suggestions", error)
            return emptyList()
        }

        val results = mutableListOf<PackageSuggestion>()
        packages.forEach { info ->
            val packageName = info.packageName ?: return@forEach
            val applicationInfo = info.applicationInfo
            val label = applicationInfo?.loadLabel(packageManager)?.toString().orEmpty()
            val matches = packageName.contains(lower, ignoreCase = true) ||
                label.contains(lower, ignoreCase = true)
            if (!matches) return@forEach

            val activities = info.activities?.asSequence() ?: emptySequence()
            val hasInstallerActivity = activities
                .map { ComponentName(packageName, it.name) }
                .any { isComponentAvailable(it) }

            if (!hasInstallerActivity) return@forEach

            results += PackageSuggestion(
                packageName = packageName,
                label = label.takeIf { it.isNotBlank() && it != packageName }
            )
            if (results.size >= limit) return@forEach
        }

        return results.sortedBy { it.packageName.lowercase() }.take(limit)
    }

    fun findInstallerEntriesForPackage(
        packageName: String,
        target: InstallTarget
    ): List<Entry> {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return emptyList()

        val resolveInfos = queryInstallerActivities()
            .filter { it.activityInfo.packageName.equals(normalized, ignoreCase = true) }

        val entries = resolveInfos
            .mapNotNull { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                entryFor(Token.Component(component), target, checkRoot = false)
            }
            .sortedBy { it.label.lowercase() }

        if (entries.isNotEmpty()) return entries

        return readCustomInstallerTokens()
            .filter { it.componentName.packageName.equals(normalized, ignoreCase = true) }
            .mapNotNull { entryFor(it, target, checkRoot = false) }
    }

    fun searchInstallerEntries(
        query: String,
        target: InstallTarget
    ): List<Entry> {
        val normalized = query.trim()
        val results = LinkedHashMap<ComponentName, Entry>()

        fun add(entry: Entry) {
            val component = (entry.token as? Token.Component)?.componentName ?: return
            results.putIfAbsent(component, entry)
        }

        val customTokens = readCustomInstallerTokens()
        if (normalized.isEmpty()) {
            customTokens.forEach { token ->
                entryFor(token, target, checkRoot = false)?.let(::add)
            }
            return results.values.sortedBy { it.label.lowercase() }
        }

        val lower = normalized.lowercase()

        customTokens.forEach { token ->
            val entry = entryFor(token, target, checkRoot = false) ?: return@forEach
            val packageMatch = token.componentName.packageName.contains(lower, ignoreCase = true)
            val classMatch = token.componentName.className.contains(lower, ignoreCase = true)
            val labelMatch = entry.label.contains(lower, ignoreCase = true)
            if (packageMatch || classMatch || labelMatch) add(entry)
        }

        queryInstallerActivities()
            .filter(::isInstallerCandidate)
            .forEach { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                val entry = entryFor(Token.Component(component), target, checkRoot = false) ?: return@forEach
                val label = entry.label.lowercase()
                val description = entry.description?.lowercase().orEmpty()
                val packageMatch = component.packageName.contains(lower, ignoreCase = true)
                val classMatch = component.className.contains(lower, ignoreCase = true)
                val labelMatch = label.contains(lower)
                val descriptionMatch = description.contains(lower)
                if (packageMatch || classMatch || labelMatch || descriptionMatch) {
                    add(entry)
                }
            }

        suggestInstallerPackages(normalized, SEARCH_PACKAGE_SUGGESTION_LIMIT).forEach { suggestion ->
            findInstallerEntriesForPackage(suggestion.packageName, target).forEach(::add)
        }

        findInstallerEntriesForPackage(normalized, target).forEach(::add)

        return results.values.sortedBy { it.label.lowercase() }
    }

    fun resolvePlan(
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?,
        allowMount: Boolean = true
    ): InstallPlan {
        val sequence = buildSequence(target, sourceFile, allowMount)
        sequence.forEach { token ->
            createPlan(token, target, sourceFile, expectedPackage, sourceLabel)?.let { return it }
        }

        // Should never happen, fallback to internal install.
        return InstallPlan.Internal(target)
    }

    fun resolvePlanForToken(
        token: Token,
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?,
        allowMount: Boolean = true
    ): InstallPlan? {
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/pull/779
        if (rejectMountToken(token, target, allowMount)) {
            return resolvePlan(
                target = target,
                sourceFile = sourceFile,
                expectedPackage = expectedPackage,
                sourceLabel = sourceLabel,
                allowMount = false
            )
        }
        return createPlan(token, target, sourceFile, expectedPackage, sourceLabel)
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/b394eb8c4319ff16198193b49e204dfd352d208f/app/src/main/java/app/morphe/manager/domain/installer/SessionInstaller.kt
    fun createSystemFallbackPlan(
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?
    ): InstallPlan.External = createPackageInstallerPlan(
        target = target,
        sourceFile = sourceFile,
        expectedPackage = expectedPackage,
        sourceLabel = sourceLabel,
        installerPackageName = app.packageName,
        installerLabel = app.getString(R.string.installer_internal_name),
        token = Token.Internal
    )

    private fun createPackageInstallerPlan(
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?,
        installerPackageName: String,
        installerLabel: String,
        token: Token
    ): InstallPlan.External {
        require(!SplitApkPreparer.isSplitArchive(sourceFile)) {
            "Split APKs require an installation session. Please retry the split installation."
        }
        val shared = copyToShareDir(sourceFile)
        val uri = InstallerFileProvider.buildUri(app, shared)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
            clipData = ClipData.newRawUri("APK", uri)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, installerPackageName)
        }
        return InstallPlan.External(
            target = target,
            intent = intent,
            sharedFile = shared,
            uri = uri,
            expectedPackage = expectedPackage,
            installerLabel = installerLabel,
            sourceLabel = sourceLabel,
            token = token
        )
    }

    fun cleanup(plan: InstallPlan.External) {
        runCatching {
            app.revokeUriPermission(plan.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { plan.sharedFile.delete() }
    }

    @Suppress("DEPRECATION")
    suspend fun tryFinalizePlayStoreAttribution(plan: InstallPlan.External): Exception? {
        if (plan.token != Token.PlayStore) return null
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            isPlayStoreInstallerSource(plan.expectedPackage)
        ) return null

        val installedApk = plan.sharedFile.takeIf(File::isFile)
            ?: runCatching {
                packageManager.getApplicationInfo(plan.expectedPackage, 0)
                    .sourceDir
                    ?.let(::File)
                    ?.takeIf(File::isFile)
            }.getOrNull()
            ?: return IllegalStateException("Installed APK is unavailable for Play Store attribution")

        try {
            // On modern Android, Settings can prefer the system installer's recorded originating
            // package over the installer of record. Reinstalling the same APK through the root
            // package-manager path clears that UPM origin while preserving the installed app.
            rootInstaller.installAsPlayStore(
                apkFile = installedApk,
                userId = android.os.Process.myUid() / 100_000
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return error
        }

        return if (isPlayStoreInstallerSource(plan.expectedPackage)) {
            null
        } else {
            IllegalStateException("Android did not record Google Play Store as the installation source")
        }
    }

    @Suppress("DEPRECATION")
    private fun isPlayStoreInstallerSource(packageName: String): Boolean = runCatching {
        val installerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            packageManager.getInstallerPackageName(packageName)
        }
        installerPackageName == PLAY_STORE_INSTALLER_PACKAGE
    }.getOrDefault(false)

    private fun readCustomInstallerTokens(): List<Token.Component> =
        prefs.installerCustomComponents.getBlocking()
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .distinct()
            .map { Token.Component(it) }

    private fun createPlan(
        token: Token,
        target: InstallTarget,
        sourceFile: File,
        expectedPackage: String,
        sourceLabel: String?
    ): InstallPlan? {
        if (token == Token.AutoSaved || token == Token.RootPlayStore) {
            require(!SplitApkPreparer.isSplitArchive(sourceFile)) {
                app.getString(R.string.mount_split_not_supported)
            }
        }
        return when (token) {
            Token.Internal -> InstallPlan.Internal(target)
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
            Token.PlayStore -> if (
                sourceFile.extension.equals("apk", ignoreCase = true) &&
                !SplitApkPreparer.isSplitArchive(sourceFile) &&
                availabilityFor(Token.PlayStore, target).available
            ) {
                createPackageInstallerPlan(
                    target = target,
                    sourceFile = sourceFile,
                    expectedPackage = expectedPackage,
                    sourceLabel = sourceLabel,
                    installerPackageName = PLAY_STORE_INSTALLER_PACKAGE,
                    installerLabel = app.getString(R.string.installer_play_store_name),
                    token = Token.PlayStore
                )
            } else null

            Token.RootPlayStore -> if (
                sourceFile.extension.equals("apk", ignoreCase = true) &&
                availabilityFor(Token.RootPlayStore, target).available
            ) {
                InstallPlan.Mount(target, installAsPlayStore = true)
            } else null

            Token.None -> null
            Token.AutoSaved -> if (availabilityFor(Token.AutoSaved, target).available) {
                InstallPlan.Mount(target)
            } else null

            Token.Shizuku -> if (availabilityFor(Token.Shizuku, target).available) {
                InstallPlan.Shizuku(target)
            } else null

            Token.ShizukuGooglePlay -> if (availabilityFor(Token.ShizukuGooglePlay, target).available) {
                InstallPlan.Shizuku(
                    target = target,
                    token = Token.ShizukuGooglePlay,
                    installerPackageNameOverride = ShizukuInstaller.GOOGLE_PLAY_PACKAGE
                )
            } else null

            is Token.Component -> {
                if (isDefaultComponent(token.componentName)) {
                    return InstallPlan.Internal(target)
                }
                val sourceMimeCandidate = mimeCandidatesFor(sourceFile).firstOrNull { candidate ->
                    isComponentAvailable(token.componentName, candidate)
                }
                if (sourceMimeCandidate == null) {
                    null
                } else {
                    val shared = copyToShareDir(sourceFile)
                    val uri = InstallerFileProvider.buildUri(app, shared)
                    try {
                        val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, sourceMimeCandidate.mimeType)
                            addFlags(permissionFlags or Intent.FLAG_ACTIVITY_NEW_TASK)
                            clipData = ClipData.newRawUri("APK", uri)
                            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, app.packageName)
                            component = token.componentName
                        }
                        app.grantUriPermission(
                            token.componentName.packageName,
                            uri,
                            permissionFlags
                        )
                        InstallPlan.External(
                            target = target,
                            intent = intent,
                            sharedFile = shared,
                            uri = uri,
                            expectedPackage = expectedPackage,
                            installerLabel = resolveLabel(token.componentName),
                            sourceLabel = sourceLabel,
                            token = token
                        )
                    } catch (error: Throwable) {
                        runCatching {
                            app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        shared.delete()
                        throw error
                    }
                }
            }
        }
    }

    private fun tokensEqual(a: Token, b: Token): Boolean = when {
        a === b -> true
        a is Token.Component && b is Token.Component -> a.componentName == b.componentName
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackagesCompat(flags: Int): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getInstalledPackages(flags)
        }

    private fun resolveLabel(componentName: ComponentName): String =
        runCatching {
            val activityInfo: ActivityInfo = packageManager.getActivityInfo(componentName, 0)
            activityInfo.loadLabel(packageManager)?.toString() ?: componentName.packageName
        }.getOrDefault(componentName.packageName)

    private fun entryFor(
        token: Token,
        target: InstallTarget,
        checkRoot: Boolean = true,
        componentMimeCandidates: List<InstallerMimeCandidate> = listOf(APK_MIME_CANDIDATE)
    ): Entry? = when (token) {
        Token.Internal -> Entry(
            token = Token.Internal,
            label = app.getString(R.string.installer_internal_name),
            description = app.getString(R.string.installer_internal_description),
            availability = Availability(true),
            icon = loadInstallerIcon(defaultInstallerPackage)
        )

        Token.None -> Entry(
            token = Token.None,
            label = app.getString(R.string.installer_option_none),
            description = app.getString(R.string.installer_none_description),
            availability = Availability(true),
            icon = null
        )

        Token.PlayStore -> Entry(
            token = Token.PlayStore,
            label = app.getString(R.string.installer_play_store_name),
            description = app.getString(R.string.installer_play_store_description),
            availability = availabilityFor(Token.PlayStore, target, checkRoot),
            icon = loadInstallerIcon(PLAY_STORE_INSTALLER_PACKAGE)
        )

        Token.RootPlayStore -> Entry(
            token = Token.RootPlayStore,
            label = app.getString(R.string.installer_root_play_store_name),
            description = app.getString(R.string.installer_root_play_store_description),
            availability = availabilityFor(Token.RootPlayStore, target, checkRoot),
            icon = loadInstallerIcon(PLAY_STORE_INSTALLER_PACKAGE)
        )

        Token.AutoSaved -> Entry(
            token = Token.AutoSaved,
            label = app.getString(R.string.installer_auto_saved_name),
            description = app.getString(R.string.installer_auto_saved_description),
            availability = availabilityFor(Token.AutoSaved, target, checkRoot),
            icon = null
        )

        Token.Shizuku -> Entry(
            token = Token.Shizuku,
            label = app.getString(R.string.installer_shizuku_name),
            description = app.getString(R.string.installer_shizuku_description),
            availability = availabilityFor(Token.Shizuku, target, checkRoot),
            icon = shizukuInstaller.installedManagerPackageName()?.let(::loadInstallerIcon)
        )

        Token.ShizukuGooglePlay -> Entry(
            token = Token.ShizukuGooglePlay,
            label = app.getString(R.string.installer_shizuku_google_play_name),
            description = app.getString(R.string.installer_shizuku_google_play_description),
            availability = availabilityFor(Token.ShizukuGooglePlay, target, checkRoot),
            icon = shizukuInstaller.installedManagerPackageName()?.let(::loadInstallerIcon)
        )

        is Token.Component -> {
            val availability = availabilityFor(
                token,
                target,
                checkRoot,
                componentMimeCandidates
            )
            Entry(
                token = token,
                label = resolveLabel(token.componentName),
                description = token.componentName.packageName,
                availability = availability,
                icon = loadInstallerIcon(token.componentName)
            )
        }
    }

    private fun copyToShareDir(source: File): File {
        val sourceExtension = source.extension.lowercase().let { extension ->
            if (extension == "apk" && SplitApkPreparer.isSplitArchive(source)) "apks" else extension
        }
        val extension = sourceExtension
            .takeIf { it == "apk" || it in SPLIT_ARCHIVE_EXTENSIONS }
            ?: "apk"
        val target = File(shareDir, "${UUID.randomUUID()}.$extension")
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (error: IOException) {
            target.delete()
            throw error
        }
        return target
    }

    private fun buildSequence(
        target: InstallTarget,
        sourceFile: File,
        allowMount: Boolean
    ): List<Token> {
        val tokens = mutableListOf<Token>()
        val primary = getPrimaryToken()
        val fallback = getFallbackToken()
        val componentMimeCandidates = mimeCandidatesFor(sourceFile)

        fun add(token: Token) {
            if (token == Token.None) return
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/779
            if (rejectMountToken(token, target, allowMount)) return
            if (token in tokens) return
            if (!availabilityFor(
                    token,
                    target,
                    componentMimeCandidates = componentMimeCandidates
                ).available
            ) {
                return
            }
            tokens += token
        }

        add(primary)

        val rejectedPrimaryMount = rejectMountToken(primary, target, allowMount)
        if (rejectedPrimaryMount && fallback != primary) add(fallback)

        if (Token.Internal !in tokens) add(Token.Internal)

        if (!rejectedPrimaryMount && fallback != primary) add(fallback)

        return tokens
    }

    private fun availabilityFor(
        token: Token,
        target: InstallTarget,
        checkRoot: Boolean = true,
        componentMimeCandidates: List<InstallerMimeCandidate> = listOf(APK_MIME_CANDIDATE)
    ): Availability = when (token) {
        Token.Internal -> Availability(true)
        Token.PlayStore -> when {
            target == InstallTarget.MANAGER_UPDATE -> {
                // Replacing this app terminates its process before the post-install
                // attribution finalizer can run.
                Availability(false, R.string.installer_status_not_supported)
            }

            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> Availability(true)

            checkRoot -> {
                if (rootInstaller.hasRootAccess()) Availability(true)
                else Availability(false, R.string.installer_status_requires_root)
            }

            else -> {
                if (rootInstaller.isDeviceRooted()) Availability(true)
                else Availability(false, R.string.installer_status_requires_root)
            }
        }
        Token.RootPlayStore -> if (!target.supportsRoot) {
            Availability(false, R.string.installer_status_not_supported)
        } else if (checkRoot) {
            if (rootInstaller.hasRootAccess()) Availability(true)
            else Availability(false, R.string.installer_status_requires_root)
        } else {
            if (rootInstaller.isDeviceRooted()) Availability(true)
            else Availability(false, R.string.installer_status_requires_root)
        }
        Token.None -> Availability(true)

        Token.AutoSaved -> if (!target.supportsRoot) {
            Availability(false, R.string.installer_status_not_supported)
        } else if (checkRoot) {
            if (rootInstaller.hasRootAccess()) Availability(true)
            else Availability(false, R.string.installer_status_requires_root)
        } else {
            if (rootInstaller.isDeviceRooted()) Availability(true)
            else Availability(false, R.string.installer_status_requires_root)
        }

        Token.Shizuku,
        Token.ShizukuGooglePlay -> {
            if (!shizukuInstaller.isInstalled()) {
                Availability(false, R.string.installer_status_shizuku_not_installed)
            } else {
                shizukuInstaller.availability(target)
            }
        }

        is Token.Component -> {
            if (isComponentAvailable(token.componentName, componentMimeCandidates)) Availability(true)
            else Availability(false, R.string.installer_status_not_supported)
        }
    }

    fun isComponentAvailable(componentName: ComponentName): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dummyUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            component = componentName
        }
        return intent.resolveActivity(packageManager) != null
    }

    private fun isComponentAvailable(
        componentName: ComponentName,
        candidate: InstallerMimeCandidate
    ): Boolean {
        if (candidate == APK_MIME_CANDIDATE) return isComponentAvailable(componentName)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dummyUriFor(candidate.extension), candidate.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ).any { info ->
            ComponentName(info.activityInfo.packageName, info.activityInfo.name) == componentName
        }
    }

    private fun isComponentAvailable(
        componentName: ComponentName,
        candidates: Collection<InstallerMimeCandidate>
    ): Boolean = candidates.any { candidate ->
        isComponentAvailable(componentName, candidate)
    }

    private fun mimeCandidatesFor(sourceFile: File): List<InstallerMimeCandidate> {
        val extension = sourceFile.extension.lowercase().let { extension ->
            if (extension == "apk" && SplitApkPreparer.isSplitArchive(sourceFile)) "apks" else extension
        }
        val mimeTypes = when (extension) {
            "apks" -> listOf(
                APKS_MIME,
                "application/apks",
                "application/x-apks",
                ARCHIVE_MIME,
                ZIP_COMPRESSED_MIME
            )
            "xapk" -> listOf(
                XAPK_MIME,
                "application/xapk",
                "application/x-xapk",
                ARCHIVE_MIME,
                ZIP_COMPRESSED_MIME
            )
            "apkm" -> listOf(
                APKM_MIME,
                "application/apkm",
                "application/x-apkm",
                ARCHIVE_MIME,
                ZIP_COMPRESSED_MIME
            )
            "zip" -> listOf(ARCHIVE_MIME, ZIP_COMPRESSED_MIME)
            else -> return listOf(APK_MIME_CANDIDATE)
        }
        return mimeTypes.map { mimeType -> InstallerMimeCandidate(mimeType, extension) }
    }

    private fun dummyUriFor(extension: String): Uri =
        InstallerFileProvider.buildUri(app, "dummy.$extension")

    private fun queryInstallerActivities(
        candidates: Collection<InstallerMimeCandidate> = listOf(APK_MIME_CANDIDATE)
    ) = candidates
        .flatMap { candidate ->
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(dummyUriFor(candidate.extension), candidate.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }
        .distinctBy { info ->
            ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        }

    private fun resolveDefaultInstallerComponent(): ComponentName? {
        fun isSystemApp(packageName: String): Boolean {
            val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: return false
            val flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            return info.flags and flags != 0
        }

        val candidates = queryInstallerActivities()
            .filter(::isInstallerCandidate)
            .filter { isSystemApp(it.activityInfo.packageName) }

        if (candidates.isEmpty()) return null

        val preferredPackages = listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        val chosen = preferredPackages.firstNotNullOfOrNull { pkg ->
            candidates.firstOrNull { it.activityInfo.packageName == pkg }
        } ?: candidates.firstOrNull { info ->
            info.loadLabel(packageManager)?.toString()
                ?.equals(AOSP_INSTALLER_LABEL, ignoreCase = true) == true
        } ?: candidates.first()

        val activityInfo = chosen.activityInfo
        return ComponentName(activityInfo.packageName, activityInfo.name)
    }

    private fun isDefaultComponent(componentName: ComponentName): Boolean =
        defaultInstallerPackage == componentName.packageName

    private fun loadInstallerIcon(componentName: ComponentName): Drawable? =
        loadInstallerIcon(componentName.packageName)

    private fun loadInstallerIcon(packageName: String?): Drawable? =
        packageName?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }

    private fun isExcludedDuplicate(packageName: String, label: String): Boolean =
        packageName == AOSP_INSTALLER_PACKAGE &&
            label.equals(AOSP_INSTALLER_LABEL, ignoreCase = true)

    private fun isInstallerCandidate(info: ResolveInfo): Boolean {
        if (!info.activityInfo.exported) return false
        val requestedPermissions = runCatching {
            packageManager.getPackageInfo(
                info.activityInfo.packageName,
                PackageManager.GET_PERMISSIONS
            ).requestedPermissions
        }.getOrNull() ?: return false

        return requestedPermissions.any {
            it == Manifest.permission.REQUEST_INSTALL_PACKAGES ||
                it == Manifest.permission.INSTALL_PACKAGES
        }
    }

    data class Entry(
        val token: Token,
        val label: String,
        val description: String?,
        val availability: Availability,
        val icon: Drawable?
    )

    data class PackageSuggestion(
        val packageName: String,
        val label: String?
    )

    data class Availability(
        val available: Boolean,
        @StringRes val reason: Int? = null
    )

    sealed class Token {
        object Internal : Token()
        object PlayStore : Token()
        object RootPlayStore : Token()
        object AutoSaved : Token()
        object Shizuku : Token()
        object ShizukuGooglePlay : Token()
        object None : Token()
        data class Component(val componentName: ComponentName) : Token()
    }

    sealed class InstallPlan {
        data class Internal(val target: InstallTarget) : InstallPlan()
        data class RootPlayStore(val target: InstallTarget) : InstallPlan()
        data class Mount(
            val target: InstallTarget,
            val installAsPlayStore: Boolean = false
        ) : InstallPlan()
        data class Shizuku(
            val target: InstallTarget,
            val token: Token = Token.Shizuku,
            val installerPackageNameOverride: String? = null
        ) : InstallPlan()
        data class External(
            val target: InstallTarget,
            val intent: Intent,
            val sharedFile: File,
            val uri: Uri,
            val expectedPackage: String,
            val installerLabel: String,
            val sourceLabel: String?,
            val token: Token
        ) : InstallPlan()
    }

    enum class InstallTarget(val supportsRoot: Boolean) {
        PATCHER(true),
        SAVED_APP(true),
        UNPATCHED_APP(false),
        MANAGER_UPDATE(false),
        LSPOSED_MODULE(false),
        DOWNLOADER_HELPER(false)
    }

    private data class InstallerMimeCandidate(
        val mimeType: String,
        val extension: String
    )

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val ARCHIVE_MIME = "application/zip"
        private const val ZIP_COMPRESSED_MIME = "application/x-zip-compressed"
        private const val APKS_MIME = "application/vnd.android.apks"
        private const val XAPK_MIME = "application/vnd.android.xapk"
        private const val APKM_MIME = "application/vnd.android.apkm"
        private val SPLIT_ARCHIVE_EXTENSIONS = setOf("apks", "xapk", "apkm", "zip")
        private val APK_MIME_CANDIDATE = InstallerMimeCandidate(APK_MIME, "apk")
        internal const val SHARE_DIR = "installer_share"
        private const val AOSP_INSTALLER_PACKAGE = "com.google.android.packageinstaller"
        private const val AOSP_INSTALLER_LABEL = "Package installer"
        private const val SEARCH_PACKAGE_SUGGESTION_LIMIT = 24
        private const val DEFAULT_PACKAGE_SUGGESTION_LIMIT = 8
        private const val TAG = "InstallerManager"

        internal fun mimeTypeForExtension(extension: String): String =
            when (extension.lowercase()) {
                "apks" -> APKS_MIME
                "xapk" -> XAPK_MIME
                "apkm" -> APKM_MIME
                "zip" -> ARCHIVE_MIME
                else -> APK_MIME
            }
    }

    fun openShizukuApp(): Boolean = shizukuInstaller.launchApp()

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    fun shizukuStatus(target: InstallTarget): ShizukuInstaller.Status =
        shizukuInstaller.status(target)

    suspend fun uninstallWithShizuku(packageName: String): ShizukuInstaller.OperationResult =
        shizukuInstaller.uninstall(packageName)

    suspend fun installWithShizuku(
        apkFile: File,
        expectedPackage: String,
        installerPackageNameOverride: String? = null
    ): ShizukuInstaller.OperationResult =
        shizukuInstaller.install(apkFile, expectedPackage, installerPackageNameOverride)

    fun isShizukuToken(token: Token?): Boolean =
        token == Token.Shizuku || token == Token.ShizukuGooglePlay

    fun usesPlayStoreSource(token: Token?): Boolean =
        token == Token.PlayStore ||
            token == Token.RootPlayStore ||
            token == Token.ShizukuGooglePlay

    fun baseInstallerToken(token: Token): Token = when (token) {
        Token.PlayStore -> Token.Internal
        Token.RootPlayStore -> Token.AutoSaved
        Token.ShizukuGooglePlay -> Token.Shizuku
        else -> token
    }

    fun supportsPlayStoreMode(token: Token): Boolean = when (baseInstallerToken(token)) {
        Token.Internal,
        Token.AutoSaved,
        Token.Shizuku -> true
        else -> false
    }

    fun withPlayStoreMode(token: Token, enabled: Boolean): Token = when (baseInstallerToken(token)) {
        Token.Internal -> if (enabled) Token.PlayStore else Token.Internal
        Token.AutoSaved -> if (enabled) Token.RootPlayStore else Token.AutoSaved
        Token.Shizuku -> if (enabled) Token.ShizukuGooglePlay else Token.Shizuku
        else -> token
    }

    fun withPlayStoreSource(token: Token, enabled: Boolean): Token = when (token) {
        Token.Shizuku,
        Token.ShizukuGooglePlay -> if (enabled) Token.ShizukuGooglePlay else Token.Shizuku
        else -> token
    }

    fun formatFailureHint(status: Int, extraMessage: String?): String? {
        val normalizedExtra = extraMessage?.takeIf { it.isNotBlank() }
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE -> app.getString(R.string.installer_hint_generic)
            PackageInstaller.STATUS_FAILURE_ABORTED -> app.getString(R.string.installer_hint_aborted)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> app.getString(R.string.installer_hint_blocked)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> when {
                isVersionDowngrade(normalizedExtra) -> app.getString(R.string.installer_hint_downgrade)
                isPackageAlreadyInstalledConflict(normalizedExtra) -> app.getString(R.string.installer_hint_conflict)
                else -> app.getString(R.string.installer_hint_conflict_generic)
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> app.getString(R.string.installer_hint_incompatible)
            PackageInstaller.STATUS_FAILURE_INVALID -> app.getString(R.string.installer_hint_invalid)
            PackageInstaller.STATUS_FAILURE_STORAGE -> app.getString(R.string.installer_hint_storage)
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> app.getString(R.string.installer_hint_timeout)
            else -> null
        }

        return when {
            base == null -> normalizedExtra
            normalizedExtra == null -> base
            else -> app.getString(R.string.installer_hint_with_reason, base, normalizedExtra)
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    fun formatShizukuFailure(status: Int, message: String?): String {
        val raw = message?.takeIf { it.isNotBlank() }
        val lower = raw.orEmpty().lowercase(Locale.ROOT)
        val summary = when {
            status == PackageInstaller.STATUS_FAILURE_BLOCKED ||
                "permission" in lower || "denied" in lower ->
                app.getString(R.string.installer_shizuku_error_permission)
            status == PackageInstaller.STATUS_FAILURE_TIMEOUT ||
                "timed out" in lower || "timeout" in lower ->
                app.getString(R.string.installer_shizuku_error_timeout)
            isVersionDowngrade(raw) ->
                app.getString(R.string.installer_shizuku_error_downgrade)
            isSignatureMismatch(raw) ->
                app.getString(R.string.installer_shizuku_error_signature)
            status == PackageInstaller.STATUS_FAILURE_INVALID ||
                "invalid_apk" in lower || "parse_error" in lower ||
                "failed to parse" in lower ->
                app.getString(R.string.installer_shizuku_error_invalid_apk)
            "user_restricted" in lower || "failed_user" in lower || "profile" in lower ->
                app.getString(R.string.installer_shizuku_error_user_profile)
            else -> raw ?: app.getString(R.string.installer_hint_generic)
        }
        return if (raw != null && raw != summary) {
            app.getString(R.string.installer_shizuku_install_fail_with_details, summary, raw)
        } else {
            app.getString(R.string.installer_shizuku_install_fail, summary)
        }
    }

    fun isSignatureMismatch(message: String?): Boolean {
        val normalized = message?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized.contains("install_failed_update_incompatible") ||
            normalized.contains("install_failed_signature_inconsistent") ||
            normalized.contains("signatures do not match") ||
            normalized.contains("signature mismatch")
    }

    fun isVersionDowngrade(message: String?): Boolean {
        val normalized = message?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized.contains("install_failed_version_downgrade") ||
            normalized.contains("version downgrade")
    }

    private fun isPackageAlreadyInstalledConflict(message: String?): Boolean {
        val normalized = message?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized.contains("install_failed_already_exists") ||
            normalized.contains("already exists") ||
            normalized.contains("already installed")
    }
}

private fun InstallerManager.Token.describe(): String = when (this) {
    InstallerManager.Token.Internal -> "Internal"
    InstallerManager.Token.PlayStore -> "PlayStore"
    InstallerManager.Token.RootPlayStore -> "RootPlayStore"
    InstallerManager.Token.AutoSaved -> "AutoSaved"
    InstallerManager.Token.Shizuku -> "Shizuku"
    InstallerManager.Token.ShizukuGooglePlay -> "ShizukuGooglePlay"
    InstallerManager.Token.None -> "None"
    is InstallerManager.Token.Component -> "Component(${componentName.flattenToString()})"
}
