package app.universal.patch.manager.ui.model.navigation

import android.os.Parcelable
import app.universal.patch.manager.network.dto.ReVancedAnnouncement
import app.universal.patch.manager.ui.model.InstalledAppAction
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlinx.serialization.Serializable
import java.util.UUID

interface ComplexParameter<T : Parcelable>

@Serializable
object Dashboard

@Serializable
data class BatchPatcher(
    val packageNames: List<String>,
    val startImmediately: Boolean = false,
    val showExistingResult: Boolean = false,
    val manualQueue: Boolean = false,
    val scheduled: Boolean = false,
    val requestId: String = UUID.randomUUID().toString()
)

@Serializable
data class BatchPatchDetails(val itemKey: String)

@Serializable
data object BatchPatchesSelector :
    ComplexParameter<SelectedApplicationInfo.PatchesSelector.ViewModelParams>

@Serializable
object PatchBundleDiscovery

@Serializable
data class PatchBundleDiscoveryPatches(
    val bundleId: Int,
    val apiHost: String = "",
    val sourceUrl: String = "",
    val version: String = "",
    val fileHash: String? = null,
    val isPrerelease: Boolean = false,
    val patchCount: Int = 0,
    val ownerName: String = "",
    val repoName: String = "",
    val bundleType: String = "",
    val downloadUrl: String? = null
)

@Serializable
data object MergeSplitApk

@Serializable
data object SplitApkInstaller

@Serializable
data object CreateYoutubeAssets

@Serializable
data object ApkSigner

@Serializable
data object KeystoreCreator

@Serializable
data object KeystoreConverter

@Serializable
data class AppSelector(
    val autoStorage: Boolean = false,
    val autoStorageReturn: Boolean = false,
    val batchQueue: Boolean = false
)

@Serializable
data class InstalledApplicationInfo(val packageName: String, val action: InstalledAppAction? = null)

@Serializable
data class Update(val downloadOnScreenEntry: Boolean = false)

@Serializable
data object Announcements

@Serializable
data object Announcement : ComplexParameter<Announcement.Payload> {
    @Parcelize
    data class Payload(
        val id: Long,
        val author: String,
        val title: String,
        val content: String,
        val tags: List<String>,
        val createdAt: String,
        val archivedAt: String?,
        val level: Int
    ) : Parcelable {
        companion object {
            fun from(announcement: ReVancedAnnouncement) = Payload(
                id = announcement.id,
                author = announcement.author,
                title = announcement.title,
                content = announcement.content,
                tags = announcement.tags,
                createdAt = announcement.createdAt.toString(),
                archivedAt = announcement.archivedAt?.toString(),
                level = announcement.level
            )
        }
    }
}

@Serializable
data object SelectedApplicationInfo : ComplexParameter<SelectedApplicationInfo.ViewModelParams> {
    @Parcelize
    data class ViewModelParams(
        val app: SelectedApp,
        val patches: PatchSelection? = null,
        val selectionPayloadJson: String? = null,
        val persistConfiguration: Boolean = true,
        val profileId: Int? = null,
        val returnToDashboard: Boolean = false,
        val requiresSourceSelection: Boolean = false,
        val batchQueue: Boolean = false,
        val sourceEntryKey: String? = null,
        val useMount: Boolean? = null,
    ) : Parcelable

    @Serializable
    object Main

    @Serializable
    data object PatchesSelector : ComplexParameter<PatchesSelector.ViewModelParams> {
        @Parcelize
        data class ViewModelParams(
            val app: SelectedApp,
            val currentSelection: PatchSelection?,
            val options: @RawValue Options,
            val preferredAppVersion: String? = null,
            val missingPatchNames: @RawValue List<String>? = null,
            val preferredBundleVersion: String? = null,
            val preferredBundleUid: Int? = null,
            val preferredBundleOverride: String? = null,
            val preferredBundleTargetsAllVersions: Boolean = false,
            val useMount: Boolean = false,
        ) : Parcelable
    }

    @Serializable
    data object RequiredOptions : ComplexParameter<PatchesSelector.ViewModelParams>
}

@Serializable
data object Patcher : ComplexParameter<Patcher.ViewModelParams> {
    @Parcelize
    data class ViewModelParams(
        val selectedApp: SelectedApp,
        val selectedPatches: PatchSelection,
        val options: @RawValue Options,
        val profileId: Int? = null,
        val profileInstallerToken: String? = null,
        val autoInstall: Boolean = false,
        val injectSignatureMetadata: Boolean = false,
        val rememberSignatureWorkflow: Boolean = false,
        val sourceEntryKey: String? = null,
        val useMount: Boolean = false,
    ) : Parcelable
}

@Serializable
object Settings {
    sealed interface Destination

    @Serializable
    data object Main : Destination

    @Serializable
    data object General : Destination

    @Serializable
    data object Advanced : Destination

    @Serializable
    data object Patcher : Destination

    @Serializable
    data object AdvancedSystem : Destination

    @Serializable
    data object Updates : Destination

    @Serializable
    data object Downloads : Destination

    @Serializable
    data object PatcherRuntimes : Destination

    @Serializable
    data object Storage : Destination

    @Serializable
    data object ImportExport : Destination

    @Serializable
    data object About : Destination

    @Serializable
    data object Changelogs : Destination

    @Serializable
    data object Contributors : Destination

    @Serializable
    data object Developer : Destination
}
