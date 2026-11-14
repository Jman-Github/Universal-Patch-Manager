package app.universal.patch.manager.network.runtime

const val INTERNAL_RUNTIME_SUPPORT_PACKAGE = "app.universal.patch.manager.support.runtime"

/** Identify support even when its APK is missing, failed, or awaiting trust. */
val PatcherRuntimePluginSourceEntry.isInternalSupport: Boolean
    get() = catalogId == PatcherRuntimeKind.SHARED_SUPPORT.id ||
        expectedKind == PatcherRuntimeKind.SHARED_SUPPORT.id ||
        expectedPackageName == INTERNAL_RUNTIME_SUPPORT_PACKAGE ||
        assetSelector.removeSuffix(".apk").equals("shared-support-plugin", ignoreCase = true)

val PatcherRuntimePluginSourceState.isInternalSupport: Boolean
    get() = entry.isInternalSupport ||
        (state as? PatcherRuntimePluginSourceState.State.Loaded)?.plugin?.kind == PatcherRuntimeKind.SHARED_SUPPORT ||
        (state as? PatcherRuntimePluginSourceState.State.Untrusted)?.packageName == INTERNAL_RUNTIME_SUPPORT_PACKAGE

fun isInternalRuntimePlugin(packageName: String, state: PatcherRuntimePluginState?): Boolean =
    packageName == INTERNAL_RUNTIME_SUPPORT_PACKAGE ||
        (state as? PatcherRuntimePluginState.Loaded)?.plugin?.kind == PatcherRuntimeKind.SHARED_SUPPORT

/** Previously exposed settings must not leave the required internal dependency frozen. */
fun PatcherRuntimePluginSourceEntry.withInternalSupportPolicy(): PatcherRuntimePluginSourceEntry =
    if (isInternalSupport) copy(autoUpdate = true, latest = true, prerelease = false) else this
