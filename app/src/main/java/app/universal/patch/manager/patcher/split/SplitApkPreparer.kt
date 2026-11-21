package app.universal.patch.manager.patcher.split

import app.universal.patch.manager.patcher.runtime.RuntimeDevice
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.util.NativeLibStripper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

object SplitApkPreparer {
    private val SUPPORTED_EXTENSIONS = setOf("apks", "apkm", "xapk")
    private const val SKIPPED_STEP_PREFIX = "[skipped]"
    private const val MAX_FLATTEN_RETRIES = 2
    internal const val MAX_ARCHIVE_ENTRIES = 100_000
    internal const val MAX_EXTRACTED_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L
    internal const val MAX_TOTAL_EXTRACTED_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 1_000L
    private const val RATIO_CHECK_MIN_SIZE = 64L * 1024L * 1024L

    private class SplitArchiveEntryLimitException(maxArchiveEntries: Int) :
        IllegalArgumentException(
            "Archive contains more than $maxArchiveEntries ZIP entries."
        )

    private val KNOWN_ABIS = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    private val DENSITY_DPI_VALUES = linkedMapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640
    )
    private val DENSITY_QUALIFIERS = DENSITY_DPI_VALUES.keys

    fun isSplitArchive(
        file: File?,
        maxArchiveEntries: Int = MAX_ARCHIVE_ENTRIES,
        checkCancelled: () -> Unit = {}
    ): Boolean {
        if (file == null || !file.exists()) return false
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS && extension != "zip" && extension != "apk") return false
        return try {
            splitApkEntryNames(
                file = file,
                maxArchiveEntries = maxArchiveEntries,
                checkCancelled = checkCancelled
            ).isNotEmpty()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    internal fun splitApkEntryNames(
        file: File,
        maxArchiveEntries: Int = MAX_ARCHIVE_ENTRIES,
        checkCancelled: () -> Unit = {}
    ): Set<String> {
        require(maxArchiveEntries >= 0) { "Invalid archive entry limit." }
        if (!file.exists()) return emptySet()
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS && extension != "zip" && extension != "apk") {
            return emptySet()
        }
        return try {
            ZipFile(file).use { zip ->
                checkCancelled()
                if (zip.size() > maxArchiveEntries) {
                    throw SplitArchiveEntryLimitException(maxArchiveEntries)
                }
                resolveSplitApkEntryNames(zip, extension)
            }
        } catch (error: SplitArchiveEntryLimitException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun prepareIfNeeded(
        source: File,
        workspace: File,
        logger: Logger = defaultLogger,
        stripNativeLibs: Boolean = false,
        skipUnneededSplits: Boolean = false,
        includedModules: Set<String>? = null,
        onProgress: ((String) -> Unit)? = null,
        onSubSteps: ((List<String>) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
        sortMergedApkEntries: Boolean = false,
        compressNativeLibraries: Boolean = false,
        onStripProgress: ((Int) -> Unit)? = null
    ): PreparationResult {
        if (!isSplitArchive(source)) {
            return PreparationResult(source, merged = false)
        }

        workspace.mkdirs()
        val workingDir = File(workspace, "split-${System.currentTimeMillis()}")
        return try {
            var preparationSource = source
            var mergedApk: File? = null
            var mergeEntries = 0
            var flattenPass = 0
            while (true) {
                coroutineContext.ensureActive()
                val passDir = workingDir.resolve("pass-$flattenPass")
                val modulesDir = passDir.resolve("modules").also { it.mkdirs() }
                val passOutput = workingDir.resolve(
                    "${source.nameWithoutExtension}-merged-${flattenPass + 1}.apk"
                )
                val sourceSize = preparationSource.length()
                logger.info(
                    "Preparing split APK bundle from ${preparationSource.name} " +
                        "(size=${sourceSize} bytes, pass=${flattenPass + 1})"
                )
                val entries = extractSplitEntries(preparationSource, modulesDir, onProgress)
                coroutineContext.ensureActive()
                logger.info("Found ${entries.size} split modules: ${entries.joinToString { it.name }}")
                logger.info("Module sizes: ${entries.joinToString { "${it.name}=${it.file.length()} bytes" }}")
                val mergeOrder = Merger.listMergeOrder(modulesDir.toPath())
                coroutineContext.ensureActive()
                val inspection = inspectMergeOrder(mergeOrder)
                val skippedModules = includedModules
                    ?.map(::normalizeModuleSelectionName)
                    ?.toSet()
                    ?.let { selectedLookup ->
                        mergeOrder.filterNot {
                            selectedLookup.contains(normalizeModuleSelectionName(it))
                        }.toSet()
                    }
                    ?: buildSet {
                        if (stripNativeLibs) {
                            addAll(inspection.unusedAbiModules)
                        }
                        if (skipUnneededSplits) {
                            addAll(inspection.unusedLanguageModules)
                            addAll(inspection.unusedDensityModules)
                        }
                    }
                if (flattenPass == 0) {
                    logSplitSelection(logger, onLog, mergeOrder, skippedModules)
                    onSubSteps?.invoke(buildSplitSubSteps(mergeOrder, skippedModules, stripNativeLibs))
                }
                coroutineContext.ensureActive()

                Merger.merge(
                    apkDir = modulesDir.toPath(),
                    outputApk = passOutput,
                    skipModules = skippedModules,
                    onProgress = onProgress,
                    onLog = onLog,
                    sortApkEntries = sortMergedApkEntries,
                    compressNativeLibraries = compressNativeLibraries
                )
                coroutineContext.ensureActive()

                val validation = validatePreparedApk(passOutput)
                if (validation.isUsable) {
                    mergedApk = passOutput
                    mergeEntries = entries.size
                    break
                }
                if (flattenPass >= MAX_FLATTEN_RETRIES || validation.embeddedSplitEntries.isEmpty()) {
                    throw IOException(
                        "Merged APK is missing required root files: ${validation.describe()}"
                    )
                }
                logger.warn(
                    "Merged APK still looks like a split container " +
                        "(${validation.describe()}); retrying flatten pass ${flattenPass + 2}."
                )
                preparationSource = passOutput
                flattenPass += 1
            }

            if (stripNativeLibs) {
                val finalApk = requireNotNull(mergedApk)
                onProgress?.invoke("Stripping native libraries")
                NativeLibStripper.strip(finalApk, onProgress = { onStripProgress?.invoke(it) })
                coroutineContext.ensureActive()
            }

            onProgress?.invoke("Finalizing merged APK")
            coroutineContext.ensureActive()

            val finalApk = requireNotNull(mergedApk)
            logger.info(
                "Split APK merged to ${finalApk.absolutePath} " +
                        "(modules=${mergeEntries}, mergedSize=${finalApk.length()} bytes)"
            )
            PreparationResult(
                file = finalApk,
                merged = true
            ) {
                workingDir.deleteRecursively()
            }
        } catch (error: Throwable) {
            workingDir.deleteRecursively()
            throw error
        }
    }

    suspend fun inspect(source: File): SplitArchiveInspection {
        require(isSplitArchive(source)) { "Source is not a supported split archive." }

        val operationContext = coroutineContext
        val mergeOrder = runInterruptible(Dispatchers.IO) {
            val names = splitApkEntryNames(source, checkCancelled = operationContext::ensureActive)
            ZipFile(source).use { zip ->
                names.forEach { name -> validateSplitEntry(requireNotNull(zip.getEntry(name))) }
            }
            SplitManifestInspector.archiveOrder(source, names, operationContext::ensureActive)
        }
        val inspection = inspectMergeOrder(mergeOrder)
        val modules = mergeOrder.map { moduleName ->
            SplitArchiveModule(
                name = moduleName,
                kind = classifyModule(moduleName, isBase = moduleName == mergeOrder.first()),
                detail = moduleDetail(moduleName)
            )
        }
        return SplitArchiveInspection(
            modules = modules,
            baseModuleName = mergeOrder.firstOrNull(),
            recommendedModules = buildRecommendedModules(modules, inspection),
            languageTrimmedModules = mergeOrder.toSet() - inspection.unusedLanguageModules,
            densityTrimmedModules = mergeOrder.toSet() - inspection.unusedDensityModules,
            abiTrimmedModules = mergeOrder.toSet() - inspection.unusedAbiModules,
            hasUnusedAbiModules = inspection.unusedAbiModules.isNotEmpty()
        )
    }

    private data class MergeOrderInspection(
        val unusedAbiModules: Set<String>,
        val unusedLanguageModules: Set<String>,
        val unusedDensityModules: Set<String>
    )

    private fun logSplitSelection(
        logger: Logger,
        onLog: ((String) -> Unit)?,
        mergeOrder: List<String>,
        skippedModules: Set<String>
    ) {
        val includedMessage =
            "Included splits: ${mergeOrder.filterNot(skippedModules::contains).toLogList()}"
        val excludedMessage =
            "Excluded splits: ${mergeOrder.filter(skippedModules::contains).toLogList()}"
        if (onLog != null) {
            onLog(includedMessage)
            onLog(excludedMessage)
        } else {
            logger.info(includedMessage)
            logger.info(excludedMessage)
        }
    }

    private fun Collection<String>.toLogList(): String =
        if (isEmpty()) "None" else joinToString(", ")

    private fun inspectMergeOrder(mergeOrder: List<String>): MergeOrderInspection {
        val localeTokens = deviceLocaleTokens()
        val allowedDensityQualifiers = supportedDensityQualifiers(
            mergeOrder = mergeOrder,
            densityQualifier = deviceDensityQualifier()
        )
        return MergeOrderInspection(
            unusedAbiModules = unusedAbiModules(mergeOrder),
            unusedLanguageModules = unusedLanguageModules(mergeOrder, localeTokens),
            unusedDensityModules = mergeOrder.filter {
                shouldSkipDensityModule(it, allowedDensityQualifiers)
            }.toSet()
        )
    }

    private fun buildRecommendedModules(
        modules: List<SplitArchiveModule>,
        inspection: MergeOrderInspection
    ): Set<String> = modules.asSequence()
        .filter { module ->
            when (module.kind) {
                SplitArchiveModuleKind.BASE -> true
                SplitArchiveModuleKind.ABI -> module.name !in inspection.unusedAbiModules
                SplitArchiveModuleKind.DENSITY -> module.name !in inspection.unusedDensityModules
                SplitArchiveModuleKind.LANGUAGE -> module.name !in inspection.unusedLanguageModules
                SplitArchiveModuleKind.FEATURE,
                SplitArchiveModuleKind.OTHER -> true
            }
        }
        .map { module -> module.name }
        .toSet()

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/0b4d6ee006e098cbaed7b01ec529e5a585e1e8ec
    // APK resources under res/ or assets/ are not split modules. APKS also supports
    // the direct splits/ and standalones/ entries used by bundletool archives.
    private fun isSplitModuleEntry(entryName: String, extension: String): Boolean {
        if (!entryName.endsWith(".apk", ignoreCase = true) || entryName.contains('\\')) return false
        if (!entryName.contains('/')) return true
        if (extension != "apks") return false
        val parent = entryName.substringBefore('/')
        val leaf = entryName.substringAfter('/')
        return parent in setOf("splits", "standalones") && !leaf.contains('/')
    }

    private fun resolveSplitApkEntryNames(
        zip: ZipFile,
        extension: String
    ): Set<String> {
        val candidates = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filter { isSplitModuleEntry(it.name, extension) }
            .toList()
        if (candidates.isEmpty()) return emptySet()

        // Host output retains separate APKs. Avoid repeatedly loading all resource tables just
        // to identify an archive stored under the legacy saved-app .apk filename.
        if (extension == "apk" && SplitApkFiles.isOutputArchive(zip)) {
            return candidates.mapTo(LinkedHashSet()) { it.name }
        }
        if (extension in SUPPORTED_EXTENSIONS) {
            return candidates.mapTo(LinkedHashSet()) { it.name }
        }
        if (extension == "zip") {
            return resolveZipApkEntryNames(candidates)
        }

        if (candidates.size < 2 || candidates.none { isLikelySplitApkEntryName(it.name) }) {
            return emptySet()
        }
        // Flatten retries re-feed merged APKs that already have a root manifest,
        // so keep verifying those containers instead of rejecting them early.
        // Downloaders store containers as base.apk. Without a root manifest this
        // cannot be an installable APK; identify it like a ZIP instead of extracting
        // and loading every component on each metadata/progress query.
        if (!hasRootManifest(zip) && resolveZipApkEntryNames(candidates).isNotEmpty()) {
            return candidates.mapTo(LinkedHashSet()) { it.name }
        }
        if (!isVerifiedSplitCandidateSet(zip, candidates)) return emptySet()
        return candidates.mapTo(LinkedHashSet()) { it.name }
    }

    private fun hasRootManifest(zip: ZipFile): Boolean =
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name == "AndroidManifest.xml"
        }

    private fun hasRootResourcesTable(zip: ZipFile): Boolean =
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name == "resources.arsc"
        }

    private fun resolveZipApkEntryNames(
        candidates: List<java.util.zip.ZipEntry>
    ): Set<String> {
        if (candidates.size == 1) {
            return linkedSetOf(candidates.single().name)
        }

        val splitLike = candidates.filter { isLikelySplitApkEntryName(it.name) }
        if (splitLike.isEmpty()) return emptySet()

        // Once a generic ZIP is recognized as a split set, every APK belongs to
        // the set. Dynamic-feature APK names are not required to match the
        // config/base naming heuristics used to identify the archive.
        if (selectProbableZipBaseEntry(candidates, splitLike) == null) return emptySet()
        return candidates.mapTo(LinkedHashSet()) { it.name }



    }

    private fun selectProbableZipBaseEntry(
        candidates: List<java.util.zip.ZipEntry>,
        splitLike: List<java.util.zip.ZipEntry>
    ): java.util.zip.ZipEntry? {
        candidates.firstOrNull { isExplicitBaseApkEntryName(it.name) }?.let { return it }
        val splitLikeNames = splitLike.mapTo(HashSet()) { it.name }
        return candidates
            .asSequence()
            .filterNot { it.name in splitLikeNames }
            .maxByOrNull { entry -> if (entry.size >= 0L) entry.size else Long.MIN_VALUE }
    }

    internal fun isExplicitBaseApkEntryName(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/').lowercase(Locale.ROOT)
        if (!fileName.endsWith(".apk")) return false
        val stem = fileName.removeSuffix(".apk")
        return fileName == "base.apk" ||
            stem == "base" ||
            stem == "main" ||
            stem == "master" ||
            stem.startsWith("base-") ||
            stem.endsWith("-main") ||
            stem.endsWith("-master")
    }

    internal fun selectDeviceMatchingConfigEntryNames(entryNames: Collection<String>): Set<String> {
        if (entryNames.isEmpty()) return emptySet()
        val localeTokens = deviceLocaleTokens()
        val allowedDensityQualifiers = supportedDensityQualifiers(
            mergeOrder = entryNames.toList(),
            densityQualifier = deviceDensityQualifier()
        )
        val preferredAbiTokens = selectPreferredAbiTokens(entryNames)
        return entryNames.filterTo(linkedSetOf()) { entryName ->
            matchesDisplayConfigEntry(
                entryName = entryName,
                localeTokens = localeTokens,
                allowedDensityQualifiers = allowedDensityQualifiers,
                preferredAbiTokens = preferredAbiTokens
            )
        }
    }

    private fun isVerifiedSplitCandidateSet(
        zip: ZipFile,
        candidates: List<java.util.zip.ZipEntry>
    ): Boolean = runCatching {
        val workingDir = Files.createTempDirectory("split-verify-").toFile()
        try {
            val usedLeafNames = HashSet<String>()
            var totalExtractedBytes = 0L
            val checkExtractionCancelled: () -> Unit = {}
            candidates.forEach { entry ->
                validateSplitEntry(entry)
                val leafName = entry.name.substringAfterLast('/')
                require(usedLeafNames.add(leafName.uppercase(Locale.ROOT))) {
                    "Split archive contains colliding APK names: $leafName"
                }
                val destination = workingDir.resolve(leafName)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination.toPath()).use { output ->
                        val copied = input.copyToBounded(
                            output = output,
                            maxEntryBytes = MAX_EXTRACTED_ENTRY_BYTES,
                            maxTotalBytes = MAX_TOTAL_EXTRACTED_BYTES,
                            previouslyExtractedBytes = totalExtractedBytes,
                            checkCancelled = checkExtractionCancelled
                        )
                        require(copied == entry.size) {
                            "Split APK entry size metadata does not match its contents: ${entry.name}"
                        }
                        totalExtractedBytes = Math.addExact(totalExtractedBytes, copied)
                    }
                }
            }
            val mergeOrder = runCatching {
                Merger.listMergeOrder(workingDir.toPath())
            }.getOrDefault(emptyList())
            mergeOrder.size >= 2 && mergeOrder.any(::isLikelySplitApkEntryName)
        } finally {
            workingDir.deleteRecursively()
        }
    }.getOrDefault(false)

    internal fun isLikelySplitApkEntryName(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        if (!fileName.endsWith(".apk", ignoreCase = true)) return false
        val lowerName = fileName.lowercase(Locale.ROOT)
        val stem = lowerName.removeSuffix(".apk")

        if (lowerName == "base.apk") return true
        if (lowerName.startsWith("split_config.") || lowerName.startsWith("config.")) return true
        if (stem.startsWith("split_")) return true
        if (stem == "main" || stem == "master") return true
        if (stem.startsWith("base-") || stem.endsWith("-main") || stem.endsWith("-master")) return true
        return false
    }

    private fun validatePreparedApk(file: File): PreparedApkValidation =
        try {
            ZipFile(file).use { zip ->
                val embeddedSplitEntries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .filter { isSplitModuleEntry(it, "apk") }
                    .filter { isExplicitBaseApkEntryName(it) || isLikelySplitApkEntryName(it) }
                    .toCollection(LinkedHashSet())
                PreparedApkValidation(
                    hasRootManifest = hasRootManifest(zip),
                    hasRootResources = hasRootResourcesTable(zip),
                    embeddedSplitEntries = embeddedSplitEntries
                )
            }
        } catch (error: IOException) {
            throw IOException(
                "Could not read merged APK ${file.name} (${file.length()} bytes, " +
                    "${file.parentFile?.usableSpace?.div(1024 * 1024)}MB free): " +
                    "${error.message ?: error.javaClass.simpleName}",
                error
            )
        }

    internal data class ExtractedModule(
        val archiveName: String,
        val name: String,
        val file: File
    )

    private data class PreparedApkValidation(
        val hasRootManifest: Boolean,
        val hasRootResources: Boolean,
        val embeddedSplitEntries: Set<String>
    ) {
        val isUsable: Boolean
            get() = hasRootManifest && hasRootResources && embeddedSplitEntries.isEmpty()

        fun describe(): String {
            val embedded = if (embeddedSplitEntries.isEmpty()) {
                "none"
            } else {
                embeddedSplitEntries.joinToString(",")
            }
            return "manifest=$hasRootManifest, resources=$hasRootResources, embeddedSplits=$embedded"
        }
    }

    private fun buildSplitSubSteps(
        mergeOrder: List<String>,
        skippedModules: Set<String>,
        stripNativeLibs: Boolean
    ): List<String> {
        val steps = mutableListOf<String>()
        steps.add("Extracting split APKs")
        val skippedLookup = skippedModules
            .map(::normalizeModuleSelectionName)
            .toSet()
        mergeOrder.forEach { name ->
            val label = "Merging $name"
            val entry = if (skippedLookup.contains(normalizeModuleSelectionName(name))) {
                "$SKIPPED_STEP_PREFIX$label"
            } else {
                label
            }
            steps.add(entry)
        }
        steps.add("Writing merged APK")
        if (stripNativeLibs) {
            steps.add("Stripping native libraries")
        }
        steps.add("Finalizing merged APK")
        return steps
    }

    private fun supportedAbiTokens(abiModules: List<String>): Set<String> =
        selectPrimaryAbi(RuntimeDevice.supportedAbis, abiModules)
            ?.let { primary ->
                buildAbiTokens(primary)
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet()
            }
            ?: emptySet()

    private fun buildAbiTokens(abi: String): Set<String> {
        val normalized = abi.lowercase(Locale.ROOT)
        return setOf(
            normalized,
            normalized.replace('-', '_'),
            normalized.replace('_', '-')
        )
    }

    private fun selectPrimaryAbi(
        supportedAbis: List<String>,
        abiModules: List<String>
    ): String? =
        supportedAbis.firstOrNull { abi ->
            abi.isNotBlank() && abiModules.any {
                matchesAbiTokens(it, buildAbiTokens(abi))
            }
        }

    private fun normalizeModuleSelectionName(name: String): String =
        name.lowercase(Locale.ROOT).removeSuffix(".apk")

    private fun unusedAbiModules(moduleNames: List<String>): Set<String> {
        val abiModules = moduleNames.filter(::hasAbiQualifier)
        if (abiModules.size <= 1) return emptySet()
        val supportedTokens = supportedAbiTokens(abiModules)
        if (supportedTokens.isEmpty()) return emptySet()
        if (abiModules.none { matchesAbiTokens(it, supportedTokens) }) return emptySet()
        return abiModules.filter { shouldSkipModule(it, supportedTokens) }.toSet()
    }

    private fun hasAbiQualifier(moduleName: String): Boolean =
        matchesAbiTokens(
            moduleName = moduleName,
            abiTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        )

    private fun matchesAbiTokens(
        moduleName: String,
        abiTokens: Set<String>
    ): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        return abiTokens.any { token -> lower.contains(token.lowercase(Locale.ROOT)) }
    }

    private fun shouldSkipModule(
        moduleName: String,
        supportedTokens: Set<String>
    ): Boolean {
        if (!hasAbiQualifier(moduleName)) return false
        return !matchesAbiTokens(moduleName, supportedTokens)
    }

    private fun unusedLanguageModules(
        mergeOrder: List<String>,
        localeTokens: Set<String>
    ): Set<String> {
        val languageModules = mergeOrder.filter(::hasLanguageQualifier)
        if (languageModules.size <= 1) return emptySet()
        return languageModules.filter { shouldSkipLanguageModule(it, localeTokens) }.toSet()
    }

    private fun hasLanguageQualifier(moduleName: String): Boolean =
        splitConfigQualifiers(moduleName).any { qualifier ->
            parseLocaleQualifier(qualifier) != null
        }

    private fun shouldSkipLanguageModule(
        moduleName: String,
        localeTokens: Set<String>
    ): Boolean {
        val qualifiers = splitConfigQualifiers(moduleName)
        if (qualifiers.isEmpty() || isAbiSplit(moduleName)) return false
        return qualifiers.any { qualifier ->
            parseLocaleQualifier(qualifier)?.let { localeQualifier ->
                !matchesLocaleQualifier(localeQualifier, localeTokens)
            } ?: false
        }
    }

    private fun shouldSkipDensityModule(
        moduleName: String,
        allowedDensityQualifiers: Set<String>
    ): Boolean {
        val qualifiers = splitConfigQualifiers(moduleName)
        if (qualifiers.isEmpty() || isAbiSplit(moduleName)) return false
        if (allowedDensityQualifiers.isEmpty()) return false
        return qualifiers.any { qualifier ->
            isDensityQualifier(qualifier) && qualifier !in allowedDensityQualifiers
        }
    }

    private fun supportedDensityQualifiers(
        mergeOrder: List<String>,
        densityQualifier: String?
    ): Set<String> {
        if (densityQualifier == null) return emptySet()
        val availableQualifiers = mergeOrder
            .flatMap(::splitConfigQualifiers)
            .filter(::isDensityQualifier)
            .toSet()
        if (availableQualifiers.isEmpty()) return emptySet()
        if (availableQualifiers.size == 1) return emptySet()
        if (densityQualifier in availableQualifiers) return setOf(densityQualifier)

        val targetDensity = DENSITY_DPI_VALUES[densityQualifier] ?: return availableQualifiers
        val availableDensityValues = availableQualifiers.mapNotNull { qualifier ->
            DENSITY_DPI_VALUES[qualifier]?.let { densityValue ->
                qualifier to densityValue
            }
        }
        if (availableDensityValues.isEmpty()) return availableQualifiers

        val closestDistance = availableDensityValues.minOf { (_, densityValue) ->
            kotlin.math.abs(densityValue - targetDensity)
        }
        return availableDensityValues
            .filter { (_, densityValue) ->
                kotlin.math.abs(densityValue - targetDensity) == closestDistance
            }
            .mapTo(linkedSetOf()) { (qualifier, _) -> qualifier }
    }

    private fun isAbiSplit(moduleName: String): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        val knownTokens = KNOWN_ABIS.flatMap { buildAbiTokens(it) }.toSet()
        return knownTokens.any { lower.contains(it) }
    }

    private fun splitConfigQualifiers(moduleName: String): List<String> {
        val normalized = moduleName.lowercase(Locale.ROOT).removeSuffix(".apk")
        val splitIndex = normalized.indexOf("split_config.")
        val configIndex = normalized.indexOf("config.")
        val startIndex = when {
            splitIndex != -1 -> splitIndex + "split_config.".length
            configIndex != -1 -> configIndex + "config.".length
            else -> return emptyList()
        }
        val tail = normalized.substring(startIndex)
        return tail.split('.').filter { it.isNotBlank() }
    }

    private fun isDensityQualifier(token: String): Boolean = token in DENSITY_QUALIFIERS

    private fun isAbiQualifier(token: String): Boolean =
        KNOWN_ABIS.any { abi ->
            buildAbiTokens(abi).any { candidate -> token == candidate.lowercase(Locale.ROOT) }
        }

    private data class LocaleQualifier(
        val language: String,
        val script: String? = null,
        val region: String? = null
    )

    private fun parseLocaleQualifier(rawToken: String): LocaleQualifier? {
        val parts = when {
            rawToken.startsWith("b+", ignoreCase = true) ->
                rawToken.removePrefix("b+").removePrefix("B+").split('+')
            else -> rawToken.replace('-', '_').split('_')
        }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val language = parts.first().lowercase(Locale.ROOT)
        if (language.length !in 2..3 || !language.all { it.isLetter() }) return null

        var script: String? = null
        var region: String? = null
        parts.drop(1).forEach { rawPart ->
            val part = rawPart.lowercase(Locale.ROOT)
            val normalizedRegion = part.removePrefix("r")
            when {
                script == null && part.length == 4 && part.all { it.isLetter() } -> script = part
                region == null &&
                    normalizedRegion.length in 2..3 &&
                    normalizedRegion.all { it.isLetterOrDigit() } -> {
                    region = normalizedRegion
                }
            }
        }

        return LocaleQualifier(language = language, script = script, region = region)
    }

    private fun matchesLocaleQualifier(
        qualifier: LocaleQualifier,
        localeTokens: Set<String>
    ): Boolean {
        val language = qualifier.language
        val script = qualifier.script
        val region = qualifier.region
        return when {
            script == null && region == null -> {
                localeTokens.contains(language)
            }
            script != null && region == null -> {
                localeTokens.contains("${language}_$script") ||
                    localeTokens.contains("${language}-$script")
            }
            script == null && region != null -> {
                localeTokens.contains("${language}_r$region") ||
                    localeTokens.contains("${language}_$region") ||
                    localeTokens.contains("${language}-$region")
            }
            else -> {
                localeTokens.contains("${language}_${script}_$region") ||
                    localeTokens.contains("${language}_${script}-r$region") ||
                    localeTokens.contains("${language}-${script}-$region")
            }
        }
    }

    private fun matchesDisplayConfigEntry(
        entryName: String,
        localeTokens: Set<String>,
        allowedDensityQualifiers: Set<String>,
        preferredAbiTokens: Set<String>
    ): Boolean {
        val qualifiers = splitConfigQualifiers(entryName)
        if (qualifiers.isEmpty()) return false

        var hasRelevantQualifier = false
        qualifiers.forEach { rawQualifier ->
            val qualifier = rawQualifier.lowercase(Locale.ROOT)
            when {
                isDensityQualifier(qualifier) -> {
                    hasRelevantQualifier = true
                    if (allowedDensityQualifiers.isNotEmpty() && qualifier !in allowedDensityQualifiers) {
                        return false
                    }
                }
                parseLocaleQualifier(qualifier)?.let { localeQualifier ->
                    hasRelevantQualifier = true
                    if (!matchesLocaleQualifier(localeQualifier, localeTokens)) {
                        return false
                    }
                    true
                } == true -> Unit
                isAbiQualifier(qualifier) -> {
                    hasRelevantQualifier = true
                    if (preferredAbiTokens.isEmpty() || qualifier !in preferredAbiTokens) {
                        return false
                    }
                }
            }
        }
        return hasRelevantQualifier
    }

    private fun deviceLocaleTokens(): Set<String> {
        val locales = RuntimeDevice.locales

        return locales.flatMap { locale ->
            buildLocaleTokens(locale)
        }.map { it.lowercase(Locale.ROOT) }.toSet()
    }

    private fun buildLocaleTokens(locale: Locale): Set<String> {
        val tokens = LinkedHashSet<String>()
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isBlank()) return tokens
        tokens.add(language)
        val region = locale.country.lowercase(Locale.ROOT)
        if (region.isNotBlank()) {
            tokens.add("${language}_r$region")
            tokens.add("${language}_$region")
            tokens.add("${language}-$region")
        }
        val script = locale.script.lowercase(Locale.ROOT)
        if (script.isNotBlank()) {
            tokens.add("${language}_$script")
            tokens.add("${language}-$script")
            if (region.isNotBlank()) {
                tokens.add("${language}_${script}_$region")
                tokens.add("${language}_${script}-r$region")
                tokens.add("${language}-${script}-$region")
            }
        }
        return tokens
    }

    private fun deviceDensityQualifier(): String? {
        val density = RuntimeDevice.densityDpi
        return when {
            density <= 120 -> "ldpi"
            density <= 160 -> "mdpi"
            density <= 213 -> "tvdpi"
            density <= 240 -> "hdpi"
            density <= 320 -> "xhdpi"
            density <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }
    }

    private fun selectPreferredAbiTokens(entryNames: Collection<String>): Set<String> {
        val normalizedEntries = entryNames.map { it.lowercase(Locale.ROOT) }
        RuntimeDevice.supportedAbis.toTypedArray().forEach { abi ->
            val tokens = buildAbiTokens(abi)
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            if (normalizedEntries.any { entryName ->
                    val qualifiers = splitConfigQualifiers(entryName)
                    qualifiers.any { qualifier -> qualifier.lowercase(Locale.ROOT) in tokens }
                }) {
                return tokens
            }
        }
        return emptySet()
    }

    private fun classifyModule(moduleName: String, isBase: Boolean): SplitArchiveModuleKind {
        val lower = moduleName.lowercase(Locale.ROOT)
        if (isBase) return SplitArchiveModuleKind.BASE
        if (isAbiSplit(moduleName)) return SplitArchiveModuleKind.ABI
        val qualifiers = splitConfigQualifiers(moduleName)
        return when {
            qualifiers.any(::isDensityQualifier) -> SplitArchiveModuleKind.DENSITY
            qualifiers.any { parseLocaleQualifier(it) != null } -> SplitArchiveModuleKind.LANGUAGE
            lower.contains("feature") -> SplitArchiveModuleKind.FEATURE
            qualifiers.isNotEmpty() -> SplitArchiveModuleKind.FEATURE
            else -> SplitArchiveModuleKind.OTHER
        }
    }

    private fun moduleDetail(moduleName: String): String? {
        if (isAbiSplit(moduleName)) {
            return KNOWN_ABIS.firstOrNull { abi ->
                buildAbiTokens(abi).any { token -> moduleName.lowercase(Locale.ROOT).contains(token) }
            }
        }
        val qualifiers = splitConfigQualifiers(moduleName)
        return qualifiers.firstOrNull(::isDensityQualifier)
            ?: qualifiers.firstNotNullOfOrNull { qualifier ->
                parseLocaleQualifier(qualifier)?.let { locale ->
                    locale.region?.let { region -> "${locale.language.uppercase(Locale.ROOT)}-$region" }
                        ?: locale.language.uppercase(Locale.ROOT)
                }
            }
    }

    private fun isBaseModuleName(moduleName: String): Boolean {
        val lower = moduleName.lowercase(Locale.ROOT)
        return lower == "base.apk" || lower.startsWith("base-")
    }

    suspend fun extractForInstall(
        source: File,
        targetDir: File,
        includedModules: Set<String>? = null
    ): List<File> {
        require(isSplitArchive(source)) { "Source is not a supported split archive." }
        targetDir.mkdirs()
        val included = includedModules?.map(::normalizeModuleSelectionName)?.toSet()
        return extractSplitEntries(source, targetDir)
            .filter { included == null || normalizeModuleSelectionName(it.name) in included }
            .sortedWith(
                compareBy<ExtractedModule> { !isBaseModuleName(it.name) }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .map(ExtractedModule::file)
    }

    internal suspend fun extractEntriesForProcessing(
        source: File,
        targetDir: File
    ): List<ExtractedModule> {
        require(isSplitArchive(source)) { "Source is not a supported split archive." }
        targetDir.mkdirs()
        return extractSplitEntries(source, targetDir)
    }

    private suspend fun extractSplitEntries(
        source: File,
        targetDir: File,
        onProgress: ((String) -> Unit)? = null
    ): List<ExtractedModule> {
        val operationContext = coroutineContext
        return runInterruptible(Dispatchers.IO) {

            val extracted = mutableListOf<ExtractedModule>()
            val checkExtractionCancelled = operationContext::ensureActive
            val splitEntryNames = splitApkEntryNames(
                file = source,
                maxArchiveEntries = MAX_ARCHIVE_ENTRIES,
                checkCancelled = checkExtractionCancelled
            )
            ZipFile(source).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filter { it.name in splitEntryNames }
                    .toList()

                if (apkEntries.isEmpty()) {
                    throw IOException("Split archive does not contain any APK entries.")
                }

                onProgress?.invoke("Extracting split APKs")
                val usedLeafNames = HashSet<String>()
                var totalExtractedBytes = 0L
                apkEntries.forEach { entry ->
                    checkExtractionCancelled()
                    validateSplitEntry(entry)
                    val entryName = entry.name.substringAfterLast('/')
                    require(usedLeafNames.add(entryName.uppercase(Locale.ROOT))) {
                        "Split archive contains colliding APK names: $entryName"
                    }
                    val destination = targetDir.resolve(entryName)
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(destination.toPath()).use { output ->
                            val copied = input.copyToBounded(
                                output = output,
                                maxEntryBytes = MAX_EXTRACTED_ENTRY_BYTES,
                                maxTotalBytes = MAX_TOTAL_EXTRACTED_BYTES,
                                previouslyExtractedBytes = totalExtractedBytes,
                                checkCancelled = checkExtractionCancelled
                            )
                            require(copied == entry.size) {
                                "Split APK entry size metadata does not match its contents: ${entry.name}"
                            }
                            totalExtractedBytes = Math.addExact(totalExtractedBytes, copied)
                        }
                    }
                    extracted += ExtractedModule(
                        archiveName = entry.name,
                        name = destination.name,
                        file = destination
                    )
                }
            }
            extracted
        }

    }

    internal fun requireSafeArchiveEntryName(name: String) {
        require(name.isNotBlank() && '\u0000' !in name) {
            "Split archive contains an invalid path."
        }
        require(!name.startsWith('/') && !name.startsWith('\\')) {
            "Split archive contains an absolute path."
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(name) && '\\' !in name) {
            "Split archive contains an unsafe path."
        }
        require(name.split('/').none { it == ".." }) {
            "Split archive contains a path traversal entry."
        }
    }

    internal fun requireSupportedSplitEntry(entry: java.util.zip.ZipEntry) {
        val name = entry.name
        require(entry.size >= 0L && entry.compressedSize >= 0L) {
            "Split archive contains unknown entry sizes."
        }
        require(entry.size <= MAX_EXTRACTED_ENTRY_BYTES) {
            "Split APK entry exceeds the supported size limit: $name"
        }
        if (entry.size >= RATIO_CHECK_MIN_SIZE && entry.compressedSize > 0L) {
            require(entry.size / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                "Split archive contains a suspiciously compressed entry: $name"
            }
        }
    }

    private fun validateSplitEntry(entry: java.util.zip.ZipEntry) {
        requireSafeArchiveEntryName(entry.name)
        requireSupportedSplitEntry(entry)
    }

    private fun java.io.InputStream.copyToBounded(
        output: java.io.OutputStream,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        previouslyExtractedBytes: Long,
        checkCancelled: () -> Unit
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            checkCancelled()
            val count = read(buffer)
            if (count < 0) break
            copied = Math.addExact(copied, count.toLong())
            val total = Math.addExact(previouslyExtractedBytes, copied)
            require(copied <= maxEntryBytes) {
                "Split APK entry exceeds the supported size limit."
            }
            require(total <= maxTotalBytes) {
                "Split APK extraction exceeds the supported total size limit."
            }
            output.write(buffer, 0, count)
        }
        return copied
    }

    data class PreparationResult(
        val file: File,
        val merged: Boolean,
        val cleanup: () -> Unit = {}
    )

    data class SplitArchiveInspection(
        val modules: List<SplitArchiveModule>,
        val baseModuleName: String?,
        val recommendedModules: Set<String>,
        val languageTrimmedModules: Set<String>,
        val densityTrimmedModules: Set<String>,
        val abiTrimmedModules: Set<String>,
        val hasUnusedAbiModules: Boolean
    )

    data class SplitArchiveModule(
        val name: String,
        val kind: SplitArchiveModuleKind,
        val detail: String? = null
    )

    enum class SplitArchiveModuleKind {
        BASE,
        LANGUAGE,
        DENSITY,
        ABI,
        FEATURE,
        OTHER
    }

    private object defaultLogger : Logger() {
        override fun log(level: LogLevel, message: String) = Unit
    }
}
