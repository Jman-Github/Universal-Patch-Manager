package app.universal.patch.manager.patcher.patch

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import java.util.Locale
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class PatchInfo(
    val name: String,
    val description: String?,
    val include: Boolean,
    val compatiblePackages: ImmutableList<CompatiblePackage>?,
    val options: ImmutableList<Option<*>>?,
    val availability: ImmutableMap<PatchInstallerType, PatchAvailabilityState>? = null,
    // Selection and option keys use name; runtimes may supply a separate readable title.
    val displayName: String = name,
    val category: String? = null,
) {

    // Code adapted from Morphe, see third-party/NOTICE for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/747
    fun defaultSelected(
        installerType: PatchInstallerType,
        availabilityEnabled: Boolean = true,
    ): Boolean = if (!availabilityEnabled) include else when (
        availability?.get(installerType)
    ) {
        PatchAvailabilityState.REQUIRED,
        PatchAvailabilityState.ENABLED -> true

        PatchAvailabilityState.UNAVAILABLE,
        PatchAvailabilityState.DISABLED -> false

        null -> include
    }

    fun lockState(
        installerType: PatchInstallerType,
        availabilityEnabled: Boolean = true,
    ): PatchLockState = if (!availabilityEnabled) PatchLockState.NONE else when (
        availability?.get(installerType)
    ) {
        PatchAvailabilityState.REQUIRED -> PatchLockState.LOCKED_ON
        PatchAvailabilityState.UNAVAILABLE -> PatchLockState.LOCKED_OFF
        PatchAvailabilityState.ENABLED,
        PatchAvailabilityState.DISABLED,
        null -> PatchLockState.NONE
    }

    fun compatibleWith(packageName: String) =
        compatiblePackages?.any { it.packageName == packageName } ?: true

    fun supports(packageName: String, versionName: String?, versionCode: Long? = null): Boolean {
        val packages = compatiblePackages ?: return true // Universal patch

        return packages.any { pkg ->
            if (pkg.packageName != packageName) return@any false
            if (pkg.versions == null) return@any true
            if (versionName == null || versionName !in pkg.versions) return@any false

            val supportedCodes = pkg.versionCodes?.get(versionName) ?: return@any true
            versionCode == null || versionCode in supportedCodes
        }
    }

    fun isExperimental(packageName: String, versionName: String?): Boolean {
        if (versionName == null) return false

        return compatiblePackages
            ?.firstOrNull { it.packageName == packageName }
            ?.experimentalVersions
            ?.contains(versionName) == true
    }

    /** Metadata adapter for version recommendations; no patcher SDK is loaded. */
    fun toPatcherPatch(): MetadataPatch = MetadataPatch(include,
        compatiblePackages?.map { it.packageName to it.versions }?.toSet())

    companion object {
        fun fromMorpheMetadata(metadata: Map<String, Any?>, preserveDouble: Boolean = false): PatchInfo {
            val name = (metadata["name"] as? String).orEmpty()
            val description = metadata["description"] as? String
            val include = metadata["use"] as? Boolean ?: true
            val compatiblePackages = (metadata["compatiblePackages"] as? List<*>)
                ?.mapNotNull { entry ->
                    val map = entry as? Map<*, *> ?: return@mapNotNull null
                    val packageName = map["packageName"] as? String ?: return@mapNotNull null
                    val versions = (map["versions"] as? Iterable<*>)
                        ?.mapNotNull { it as? String }
                        ?.toImmutableSet()
                        ?.takeIf { it.isNotEmpty() }
                    val experimentalVersions = (map["experimentalVersions"] as? Iterable<*>)
                        ?.mapNotNull { it as? String }
                        ?.toImmutableSet()
                        ?.takeIf { it.isNotEmpty() }
                    val versionCodes = parseVersionCodes(map["versionCodes"])
                    CompatiblePackage(packageName, versions, experimentalVersions, versionCodes)
                }
                ?.toImmutableList()
                ?.takeIf { it.isNotEmpty() }

            val options = (metadata["options"] as? List<*>)
                ?.mapNotNull { entry ->
                    val map = entry as? Map<*, *> ?: return@mapNotNull null
                    Option.fromMorpheMetadata(map, preserveDouble)
                }
                ?.toImmutableList()
                ?.takeIf { it.isNotEmpty() }

            val availability = (metadata["availability"] as? Map<*, *>)
                ?.mapNotNull { (rawInstallerType, rawAvailability) ->
                    val installerType = rawInstallerType
                        ?.toString()
                        ?.let { runCatching { PatchInstallerType.valueOf(it) }.getOrNull() }
                        ?: return@mapNotNull null
                    val patchAvailability = rawAvailability
                        ?.toString()
                        ?.let { runCatching { PatchAvailabilityState.valueOf(it) }.getOrNull() }
                        ?: return@mapNotNull null
                    installerType to patchAvailability
                }
                ?.toMap()
                ?.toImmutableMap()
                ?.takeIf { it.isNotEmpty() }

            return PatchInfo(
                name,
                description,
                include,
                compatiblePackages,
                options,
                availability,
                displayName = (metadata["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: name,
                category = (metadata["category"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        private fun parseVersionCodes(raw: Any?): ImmutableMap<String, ImmutableSet<Long>>? {
            val entries = raw as? Map<*, *> ?: return null
            return entries.mapNotNull { (rawVersion, rawCodes) ->
                val version = rawVersion as? String ?: return@mapNotNull null
                val codes = (rawCodes as? Iterable<*>)
                    ?.mapNotNull { (it as? Number)?.toLong() }
                    ?.toImmutableSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                version to codes
            }.toMap().toImmutableMap().takeIf { it.isNotEmpty() }

        }
    }
}

@Immutable
data class CompatiblePackage(
    val packageName: String,
    val versions: ImmutableSet<String>?,
    val experimentalVersions: ImmutableSet<String>? = null,
    val versionCodes: ImmutableMap<String, ImmutableSet<Long>>? = null
)

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/706
enum class ExplicitOptionKind { Folder, FilePath, Files, Image, Color }

data class ImageSize(val width: Int, val height: Int)

@Immutable
data class Option<T>(
    val title: String,
    val key: String,
    val description: String,
    val required: Boolean,
    val type: KType,
    val default: T?,
    val presets: Map<String, T?>?,
    val validator: (T?) -> Boolean,
    val explicitKind: ExplicitOptionKind? = null,
    val allowedExtensions: ImmutableList<String>? = null,
    val recommendedSize: ImageSize? = null,
) {
    companion object {
        private val scalarTypes = mapOf(
            "boolean" to typeOf<Boolean>(),
            "int" to typeOf<Int>(),
            "long" to typeOf<Long>(),
            "double" to typeOf<Float>(),
            "float" to typeOf<Float>(),
            "string" to typeOf<String>()
        )
        private val listTypes = mapOf(
            typeOf<Boolean>() to typeOf<List<Boolean>>(),
            typeOf<Int>() to typeOf<List<Int>>(),
            typeOf<Long>() to typeOf<List<Long>>(),
            typeOf<Float>() to typeOf<List<Float>>(),
            typeOf<Double>() to typeOf<List<Double>>(),
            typeOf<String>() to typeOf<List<String>>()
        )

        fun fromMorpheMetadata(metadata: Map<*, *>, preserveDouble: Boolean = false): Option<Any> {
            val key = metadata["key"]?.toString().orEmpty()
            val title = metadata["title"]?.toString().orEmpty().ifBlank { key }
            val description = metadata["description"]?.toString().orEmpty()
            val required = metadata["required"] as? Boolean ?: false
            val type = parseType(metadata["type"], preserveDouble)
            val default = coerceValue(type, metadata["default"])
            val presets = (metadata["presets"] as? Map<*, *>)?.mapNotNull { (name, value) ->
                val presetKey = name?.toString() ?: return@mapNotNull null
                val presetValue = coerceValue(type, value)
                presetKey to presetValue
            }?.toMap()?.takeIf { it.isNotEmpty() }

            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/706
            val explicitKind = metadata["explicitKind"]
                ?.toString()
                ?.let { rawKind ->
                    ExplicitOptionKind.entries.firstOrNull { it.name == rawKind }
                }
            val allowedExtensions = (metadata["allowedExtensions"] as? Iterable<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                ?.toImmutableList()
                ?.takeIf { it.isNotEmpty() }
            val recommendedSize = (metadata["recommendedSize"] as? Map<*, *>)?.let { size ->
                val width = (size["width"] as? Number)?.toInt() ?: return@let null
                val height = (size["height"] as? Number)?.toInt() ?: return@let null
                ImageSize(width, height)
            }

            return Option(
                title = title,
                key = key,
                description = description,
                required = required,
                type = type,
                default = default,
                presets = presets,
                validator = { true },
                explicitKind = explicitKind,
                allowedExtensions = allowedExtensions,
                recommendedSize = recommendedSize,
            )
        }

        private fun parseType(raw: Any?, preserveDouble: Boolean): KType {
            val normalized = raw?.toString()?.lowercase(Locale.US).orEmpty()
            val elementType = if (normalized.contains("list")) {
                val elementName = Regex("<(.+)>").find(normalized)?.groupValues?.getOrNull(1)
                elementName?.lowercase(Locale.US)
            } else null

            val scalarName = elementType ?: normalized
            val resolvedScalar = if (preserveDouble && scalarName.contains("double")) {
                typeOf<Double>()
            } else resolveScalarType(scalarName)
            if (normalized.contains("list")) {
                return listTypes[resolvedScalar] ?: typeOf<List<String>>()
            }

            return resolvedScalar
        }

        private fun resolveScalarType(raw: String): KType {
            val normalized = raw.lowercase(Locale.US)
            scalarTypes.forEach { (key, type) ->
                if (normalized.contains(key)) return type
            }
            return typeOf<String>()
        }

        private fun coerceValue(type: KType, value: Any?): Any? {
            if (value == null) return null
            if (type.classifier == List::class) {
                val elementType = type.arguments.first().type ?: typeOf<String>()
                val iterable = value as? Iterable<*> ?: return null
                val converted = mutableListOf<Any?>()
                for (element in iterable) {
                    if (element == null) return null
                    val coerced = coerceScalar(elementType, element) ?: return null
                    converted.add(coerced)
                }
                return converted
            }
            return coerceScalar(type, value)
        }

        private fun coerceScalar(type: KType, value: Any?): Any? {
            if (value == null) return null
            return when (type) {
                typeOf<Boolean>() -> when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull()
                    else -> null
                }
                typeOf<Int>() -> when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }
                typeOf<Long>() -> when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
                typeOf<Double>() -> when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                }
                typeOf<Float>() -> when (value) {
                    is Number -> value.toFloat()
                    is String -> value.toFloatOrNull()
                    else -> null
                }
                typeOf<String>() -> value.toString()
                else -> value.toString()
            }
        }
    }
}

@PublishedApi
internal fun Option<*>.hasRequiredValue(values: Map<String, Any?>): Boolean {
    if (!required) return true
    if (explicitKind == null) return default != null || key in values

    val value = if (key in values) values[key] else default
    return when (explicitKind) {
        ExplicitOptionKind.Folder,
        ExplicitOptionKind.FilePath,
        ExplicitOptionKind.Image,
        ExplicitOptionKind.Color -> (value as? String)?.isNotBlank() == true

        ExplicitOptionKind.Files -> {
            val paths = value as? Iterable<*> ?: return false
            val iterator = paths.iterator()
            iterator.hasNext() && iterator.asSequence().all { path ->
                path is String && path.isNotBlank()
            }
        }

        null -> false
    }
}
