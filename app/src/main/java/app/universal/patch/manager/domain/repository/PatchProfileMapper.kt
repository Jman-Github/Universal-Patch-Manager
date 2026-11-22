package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.domain.bundles.toLegacyAwareBundleIndex

import android.util.Log
import app.universal.patch.manager.data.room.options.Option
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.patcher.morphe.MorpheRuntimeBridge
import app.universal.patch.manager.patcher.revanced.Revanced21RuntimeBridge
import app.universal.patch.manager.patcher.revanced.Revanced22RuntimeBridge
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.mergeWith
import app.universal.patch.manager.util.tag
import java.security.MessageDigest
data class PatchProfileConfiguration(
    val selection: PatchSelection,
    val options: Options,
    val missingBundles: Set<Int>,
    val changedBundles: Set<Int>
)

fun PatchSelection.toPayload(
    sources: List<PatchBundleSource>,
    bundleInfo: Map<Int, PatchBundleInfo.Global>
): PatchProfilePayload? {
    if (isEmpty()) return null
    val sourceMap = sources.associateBy { it.uid }
    return PatchProfilePayload(
        entries.map { (uid, patches) ->
            val source = sourceMap[uid]
            val info = bundleInfo[uid]
            PatchProfilePayload.Bundle(
                bundleUid = uid,
                patches = patches.map { it.trim() }.filter { it.isNotEmpty() }.sorted(),
                options = emptyMap(),
                displayName = source?.displayTitle ?: info?.name,
                sourceEndpoint = (source as? RemotePatchBundle)?.endpoint,
                sourceName = source?.patchBundle?.manifestAttributes?.name ?: source?.name ?: info?.name,
                version = info?.version,
                optionDisplayInfo = emptyMap()
            )
        }
    )
}

fun PatchSelection.toPayload(
    sources: List<PatchBundleSource>,
    bundleInfo: Map<Int, PatchBundleInfo.Global>,
    options: Options
): PatchProfilePayload? {
    if (isEmpty()) return null
    val sourceMap = sources.associateBy { it.uid }
    return PatchProfilePayload(
        entries.map { (uid, patches) ->
            val source = sourceMap[uid]
            val info = bundleInfo[uid]
            val trimmedPatches = patches.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
            val selected = trimmedPatches.toSet()
            val bundleOptions = options[uid].orEmpty()
            val serializedOptions = buildMap<String, Map<String, Option.SerializedValue>> {
                bundleOptions.forEach { (patchName, optionValues) ->
                    if (selected.isNotEmpty() && patchName !in selected) return@forEach
                    val serializedForPatch = mutableMapOf<String, Option.SerializedValue>()
                    optionValues.forEach { (key, value) ->
                        try {
                            serializedForPatch[key] = Option.SerializedValue.fromValue(value)
                        } catch (e: Option.SerializationException) {
                            Log.w(
                                tag,
                                "Failed to serialize option $uid:$patchName:$key",
                                e
                            )
                        }
                    }
                    if (serializedForPatch.isNotEmpty()) {
                        put(patchName, serializedForPatch.toMap())
                    }
                }
            }
            PatchProfilePayload.Bundle(
                bundleUid = uid,
                patches = trimmedPatches,
                options = serializedOptions,
                displayName = source?.displayTitle ?: info?.name,
                sourceEndpoint = (source as? RemotePatchBundle)?.endpoint,
                sourceName = source?.patchBundle?.manifestAttributes?.name ?: source?.name ?: info?.name,
                version = info?.version,
                optionDisplayInfo = emptyMap()
            )
        }
    )
}

fun PatchProfilePayload.remapAndExtractSelection(
    sources: List<PatchBundleSource>,
    signatures: Map<Int, Set<String>>
): Pair<PatchProfilePayload, PatchSelection> {
    val remapped = remapLocalBundles(sources, signatures)
    val sourceMap = sources.associateBy { it.uid }
    val sourceByEndpoint = sources.mapNotNull { source ->
        source.asRemoteOrNull?.endpoint
            ?.normalizedBundleEndpoint()
            ?.let { it to source }
    }.toLegacyAwareBundleIndex()
    val selection = buildMap<Int, Set<String>> {
        remapped.bundles.forEach { bundle ->
            val resolvedUid = if (sourceMap.containsKey(bundle.bundleUid)) {
                bundle.bundleUid
            } else {
                bundle.sourceEndpoint
                    ?.normalizedBundleEndpoint()
                    ?.let(sourceByEndpoint::get)
                    ?.uid
                    ?: bundle.bundleUid
            }
            val patches = bundle.patches
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (patches.isNotEmpty()) {
                put(resolvedUid, get(resolvedUid).orEmpty() + patches)
            }
        }
    }
    return remapped to selection
}

internal fun PatchSelection.mergeWithRemappedSelection(
    originalPayload: PatchProfilePayload,
    remappedPayload: PatchProfilePayload,
    remappedSelection: PatchSelection,
    sources: List<PatchBundleSource>
): PatchSelection {
    val uidRemap = originalPayload.bundles
        .zip(remappedPayload.bundles)
        .mapNotNull { (original, remapped) ->
            if (original.bundleUid != remapped.bundleUid) {
                original.bundleUid to remapped.bundleUid
            } else {
                null
            }
        }
        .toMap()
        .toMutableMap()

    val sourceMap = sources.associateBy { it.uid }
    val sourceByEndpoint = sources.mapNotNull { source ->
        source.asRemoteOrNull?.endpoint
            ?.normalizedBundleEndpoint()
            ?.let { it to source }
    }.toLegacyAwareBundleIndex()
    originalPayload.bundles.forEach { bundle ->
        if (sourceMap.containsKey(bundle.bundleUid)) return@forEach
        val resolvedUid = bundle.sourceEndpoint
            ?.normalizedBundleEndpoint()
            ?.let(sourceByEndpoint::get)
            ?.uid
            ?: return@forEach
        if (resolvedUid != bundle.bundleUid) {
            uidRemap[bundle.bundleUid] = resolvedUid
        }
    }

    if (uidRemap.isEmpty()) return mergeWith(remappedSelection)

    fun PatchSelection.canonicalize() = buildMap<Int, Set<String>> {
        this@canonicalize.forEach { (uid, patches) ->
            val resolvedUid = uidRemap[uid] ?: uid
            val merged = get(resolvedUid).orEmpty() + patches
            if (merged.isNotEmpty()) put(resolvedUid, merged)
        }
    }
    return canonicalize().mergeWith(remappedSelection.canonicalize())
}

fun PatchProfile.toConfiguration(
    bundles: Map<Int, PatchBundleInfo.Scoped>,
    sources: Map<Int, PatchBundleSource>
): PatchProfileConfiguration = payload.toConfiguration(bundles, sources)

fun PatchProfilePayload.toConfiguration(
    bundleInfo: Map<Int, PatchBundleInfo.Scoped>,
    sources: Map<Int, PatchBundleSource>
): PatchProfileConfiguration {
    val selection = mutableMapOf<Int, MutableSet<String>>()
    val options = mutableMapOf<Int, MutableMap<String, MutableMap<String, Any?>>>()
    val missingBundles = mutableSetOf<Int>()
    val changedBundles = mutableSetOf<Int>()
    val endpointToSource = sources.values.mapNotNull { source ->
        (source as? RemotePatchBundle)?.endpoint
            ?.normalizedBundleEndpoint()
            ?.let { endpoint -> endpoint to source }
    }.toLegacyAwareBundleIndex()

    bundles.forEach { bundle ->
        var resolvedUid = bundle.bundleUid
        var info = bundleInfo[resolvedUid]
        if (info == null) {
            val endpoint = bundle.sourceEndpoint?.normalizedBundleEndpoint()
            if (endpoint != null) {
                val matchingSource = endpointToSource[endpoint]
                if (matchingSource != null) {
                    resolvedUid = matchingSource.uid
                    info = bundleInfo[resolvedUid]
                }
            }
        }
        if (info == null) {
            missingBundles += bundle.bundleUid
            return@forEach
        }
        val patchMetadata = info.forSelection().patches.associateBy { it.name }

        val selectedPatches = bundle.patches
            .filter { patchMetadata.containsKey(it) }
            .toMutableSet()

        selection[resolvedUid] = selectedPatches

        val bundleOptions = mutableMapOf<String, MutableMap<String, Any?>>()
        var bundleChanged = selectedPatches.size != bundle.patches.size

        bundle.options.forEach { (patchName, optionValues) ->
            val patchInfo = patchMetadata[patchName] ?: run {
                bundleChanged = true
                return@forEach
            }
            val optionMetadata = patchInfo.options?.associateBy { it.key } ?: emptyMap()

            optionValues.forEach { (key, serialized) ->
                val option = optionMetadata[key] ?: run {
                    bundleChanged = true
                    return@forEach
                }
                val value = try {
                    serialized.deserializeFor(option)
                } catch (e: Option.SerializationException) {
                    Log.w(
                        tag,
                        "Failed to deserialize option $patchName:$key for bundle ${bundle.bundleUid}",
                        e
                    )
                    bundleChanged = true
                    return@forEach
                }

                bundleOptions
                    .getOrPut(patchName, ::mutableMapOf)[key] = value
            }
        }

        if (bundleOptions.isNotEmpty()) {
            options[resolvedUid] = bundleOptions
        }

        if (bundleChanged) {
            changedBundles += resolvedUid
        }
    }

    return PatchProfileConfiguration(
        selection = selection.mapValues { it.value.toSet() },
        options = options.mapValues { bundleEntry ->
            bundleEntry.value.mapValues { it.value.toMap() }
        },
        missingBundles = missingBundles,
        changedBundles = changedBundles
    )
}

fun PatchProfilePayload.remapLocalBundles(
    sources: List<PatchBundleSource>,
    signatures: Map<Int, Set<String>> = emptyMap()
): PatchProfilePayload {
    if (bundles.isEmpty()) return this

    val localSources = sources.filter { it.asRemoteOrNull == null }
    val byDisplayTitle = localSources.associateBy { it.displayTitle.trim().lowercase() }
    val byName = localSources.associateBy { it.name.trim().lowercase() }
    val byIdentifier = localSources.mapNotNull { source ->
        val identifier = source.patchBundle?.manifestAttributes?.name
            ?.takeUnless { it.isNullOrBlank() }
            ?: source.name
        identifier.trim().takeIf { it.isNotEmpty() }?.lowercase()?.let { it to source }
    }.toMap()
    val endpointToSource = sources.mapNotNull { source ->
        source.asRemoteOrNull?.endpoint
            ?.normalizedBundleEndpoint()
            ?.let { it to source }
    }.toLegacyAwareBundleIndex()

    var changed = false

    val resolvedSignatures = signatures.mapValuesTo(mutableMapOf()) { (_, value) ->
        value.map { it.trim().lowercase() }.toSet()
    }
    localSources.forEach { source ->
        if (resolvedSignatures.containsKey(source.uid)) return@forEach
        val bundle = source.patchBundle ?: return@forEach
        val revanced22Names = runCatching { Revanced22RuntimeBridge.loadMetadata(bundle.patchesJar) }
            .getOrNull()
        val revanced21Names = if (revanced22Names == null) {
            runCatching { Revanced21RuntimeBridge.loadMetadata(bundle.patchesJar) }.getOrNull()
        } else null
        val morpheNames = if (revanced22Names == null && revanced21Names == null) {
            runCatching { MorpheRuntimeBridge.loadMetadata(bundle.patchesJar) }.getOrNull()
        } else null
        val names = (revanced22Names ?: revanced21Names ?: morpheNames)
            ?.map { it.name.trim().lowercase() }
            ?.toSet()
        if (!names.isNullOrEmpty()) resolvedSignatures[source.uid] = names
    }

    val hashIndex = buildMap<String, MutableList<PatchBundleSource>> {
        localSources.forEach { source ->
            val signature = resolvedSignatures[source.uid]
            val hash = signature?.signatureHash()
            if (hash != null) getOrPut(hash, ::mutableListOf).add(source)
        }
    }

    val remappedBundles = bundles.map { bundle ->
        val direct = sources.firstOrNull { it.uid == bundle.bundleUid }
        if (direct != null) {
            if (direct.asRemoteOrNull != null) {
                return@map bundle
            }
            val updated = bundle.copy(
                bundleUid = direct.uid,
                displayName = direct.displayTitle,
                sourceName = direct.patchBundle?.manifestAttributes?.name ?: direct.name,
                sourceEndpoint = null,
                optionDisplayInfo = bundle.optionDisplayInfo
            )
            if (updated != bundle) changed = true
            return@map updated
        }

        bundle.sourceEndpoint?.normalizedBundleEndpoint()?.let { endpoint ->
            val remote = endpointToSource[endpoint]
            if (remote != null) {
                return@map bundle
            }
        }

        val searchKeys = buildList {
            bundle.sourceName?.trim()?.lowercase()?.let(::add)
            bundle.displayName?.trim()?.lowercase()?.let(::add)
        }
        val target = searchKeys.firstNotNullOfOrNull { key ->
            byDisplayTitle[key] ?: byName[key] ?: byIdentifier[key]
        }
        if (target != null && target.asRemoteOrNull == null) {
            val updated = bundle.copy(
                bundleUid = target.uid,
                displayName = target.displayTitle,
                sourceName = target.patchBundle?.manifestAttributes?.name ?: target.name,
                sourceEndpoint = null,
                optionDisplayInfo = bundle.optionDisplayInfo
            )
            if (updated != bundle) changed = true
            return@map updated
        }

        val normalizedSignature = bundle.patches.map { it.trim().lowercase() }.toSet()
        val signatureHash = normalizedSignature.signatureHash()
        val signatureMatchByHash = signatureHash
            ?.let(hashIndex::get)
            ?.firstOrNull { candidate ->
                resolvedSignatures[candidate.uid].orEmpty() == normalizedSignature
            }
        val signatureMatch = signatureMatchByHash ?: localSources
            .mapNotNull { source ->
                val signature = resolvedSignatures[source.uid].orEmpty()
                if (normalizedSignature.isNotEmpty() && normalizedSignature.all(signature::contains)) {
                    val extra = signature.size - normalizedSignature.size
                    source to extra
                } else null
            }
            .minByOrNull { it.second }
            ?.first

        if (signatureMatch != null) {
            val updated = bundle.copy(
                bundleUid = signatureMatch.uid,
                displayName = signatureMatch.displayTitle,
                sourceName = signatureMatch.patchBundle?.manifestAttributes?.name ?: signatureMatch.name,
                sourceEndpoint = null,
                optionDisplayInfo = bundle.optionDisplayInfo
            )
            if (updated != bundle) changed = true
            updated
        } else bundle
    }

    return if (changed) copy(bundles = remappedBundles) else this
}

fun Map<Int, PatchBundleInfo.Global>.toSignatureMap() =
    mapValues { (_, info) ->
        info.patches.map { it.name.trim().lowercase() }.toSet()
    }

private fun String.normalizedBundleEndpoint(): String? =
    app.universal.patch.manager.domain.bundles.bundleEndpointIdentity(this).trimEnd('/').takeIf(String::isNotBlank)

private fun Collection<String>.signatureHash(): String? {
    if (isEmpty()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    this.sorted().forEach { value ->
        val bytes = value.toByteArray()
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

