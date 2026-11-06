package app.universal.patch.manager.patcher.worker

import app.universal.patch.manager.util.applyManagerNotificationIcon
import android.app.Notification
import android.app.Notification.InboxStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.work.WorkerParameters
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.worker.Worker
import app.universal.patch.manager.receiver.BundleUpdateNotificationDismissReceiver
import app.universal.patch.manager.util.BundleDeepLinkIntent
import app.universal.patch.manager.util.EXTRA_MANUAL_BUNDLE_UIDS
import app.universal.patch.manager.util.MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID
import app.universal.patch.manager.util.applyProgressNotification
import app.universal.patch.manager.util.permission.hasNotificationPermission
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.min

class BundleUpdateNotificationWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<BundleUpdateNotificationWorker.Args>(context, parameters), KoinComponent {
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val runtimePluginRepository: app.universal.patch.manager.domain.repository.PatcherRuntimePluginRepository by inject()
    // Periodic and immediate workers may overlap, including during cancellation cleanup.
    private val progressNotificationTag = "bundle-update-progress:$id"

    class Args

    private val bundleNotificationChannel = NotificationChannel(
        "background-bundle-update-channel",
        applicationContext.getString(R.string.notification_channel_bundle_updates_name),
        NotificationManager.IMPORTANCE_HIGH
    )

    override suspend fun doWork(): Result {
        val wakeLock = runCatching {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()

        return try {
            bundleNotificationChannel.description =
                applicationContext.getString(R.string.notification_channel_bundle_updates_description)

            val canNotify = applicationContext.hasNotificationPermission()
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(bundleNotificationChannel)
            // Remove notifications left by versions that used a shared, untagged ID.
            notificationManager.cancel(BUNDLE_PROGRESS_NOTIFICATION_ID)

            // The application reloads patch bundles asynchronously on process start.
            // Do not snapshot the repository's initial empty state on a cold worker run.
            // Runtime checks share the bundle worker's periodic schedule and network policy.
            try {
                runtimePluginRepository.updateCheck()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("RuntimeUpdates", "Runtime update check failed", error)
            }
            patchBundleRepository.awaitReady()

            fun buildPendingIntent(
                requestCode: Int,
                bundleUid: Int?,
                dismissalMarkers: Array<String> = emptyArray()
            ): PendingIntent {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    if (requestCode == BUNDLE_PROGRESS_NOTIFICATION_ID) {
                        action = progressNotificationTag
                    }
                    BundleDeepLinkIntent.addBundleUid(this, bundleUid)
                    if (dismissalMarkers.isNotEmpty()) {
                        putExtra(
                            BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS,
                            dismissalMarkers
                        )
                    }
                }
                return PendingIntent.getActivity(
                    applicationContext,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val autoUpdateTargets = patchBundleRepository.sources.first()
                .filterIsInstance<RemotePatchBundle>()
                .filter { bundle ->
                    bundle.updatesAutomatically &&
                        bundle.searchUpdate &&
                        bundle.enabled &&
                        bundle.state is PatchBundleSource.State.Available
                }

            val totalAutoUpdates = autoUpdateTargets.size
            val seenUids = LinkedHashSet<Int>()
            val updatedBundles = LinkedHashMap<Int, BundleUpdateNotificationEntry>()
            val updatedAny = if (totalAutoUpdates > 0) {
                patchBundleRepository.updateNow(
                    allowUnsafeNetwork = false,
                    onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                        val shouldNotify = bytesRead > 0L || (bytesTotal ?: 0L) > 0L
                        if (!shouldNotify) return@updateNow
                        if (!canNotify) return@updateNow

                        seenUids.add(bundle.uid)
                        val currentIndex = seenUids.size.coerceAtMost(totalAutoUpdates)
                        val progressText = applicationContext.getString(
                            R.string.bundle_updates_notification_progress,
                            currentIndex,
                            totalAutoUpdates,
                            bundle.displayTitle
                        )
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = bundleNotificationTitle(totalAutoUpdates.coerceAtLeast(1)),
                            description = progressText,
                            pendingIntent = buildPendingIntent(
                                BUNDLE_PROGRESS_NOTIFICATION_ID,
                                bundle.uid
                            ),
                            ongoing = true,
                            progress = ProgressInfo(bytesRead, bytesTotal)
                        )
                        notificationManager.notify(progressNotificationTag, BUNDLE_PROGRESS_NOTIFICATION_ID, notification)
                    },
                    onBundleUpdated = { bundle, updatedName, updatedVersion ->
                        val resolvedName = bundle.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: updatedName?.takeIf { it.isNotBlank() }
                            ?: bundle.displayTitle
                        updatedBundles[bundle.uid] = BundleUpdateNotificationEntry(
                            uid = bundle.uid,
                            name = resolvedName,
                            version = updatedVersion
                        )
                    },
                    predicate = { bundle ->
                        bundle.updatesAutomatically &&
                            bundle.searchUpdate &&
                            bundle.enabled &&
                            bundle.state is PatchBundleSource.State.Available
                    }
                )
            } else {
                false
            }

            // Downloads are finished; unrelated manual checks may still wait on the network.
            notificationManager.cancel(progressNotificationTag, BUNDLE_PROGRESS_NOTIFICATION_ID)

            val manualUpdates = LinkedHashMap<Int, BundleUpdateNotificationEntry>()
            patchBundleRepository.fetchUpdatesAndNotify(
                applicationContext,
                predicate = { bundle -> !bundle.updatesAutomatically },
                onAlreadyNotified = { bundle, bundleVersion, notificationIdentity ->
                    if (canNotify && !isManualUpdateDismissed(bundle.uid, notificationIdentity)) {
                        manualUpdates[bundle.uid] = BundleUpdateNotificationEntry(
                            uid = bundle.uid,
                            name = bundle.displayTitle,
                            version = bundleVersion,
                            notificationIdentity = notificationIdentity
                        )
                    }
                }
            ) { bundle, bundleVersion, notificationIdentity ->
                if (!canNotify) {
                    false
                } else {
                    manualUpdateDismissalMarker(bundle.uid, notificationIdentity)?.let { marker ->
                        BundleUpdateNotificationDismissReceiver.clearDismissedMarkers(
                            applicationContext,
                            setOf(marker)
                        )
                    }
                    manualUpdates[bundle.uid] = BundleUpdateNotificationEntry(
                        uid = bundle.uid,
                        name = bundle.displayTitle,
                        version = bundleVersion,
                        notificationIdentity = notificationIdentity
                    )
                    true
                }
            }

            if (canNotify) {
                val sourceOrder = patchBundleRepository.sources.first().map { it.uid }
                val orderedUpdatedBundles = updatedBundles.values.orderBySource(sourceOrder)
                patchBundleRepository.withManualUpdateSnapshot { liveManualUpdates ->
                    manualUpdates.entries.removeAll { (uid, entry) ->
                        val liveIdentity = liveManualUpdates[uid]?.notificationIdentity
                            ?: return@removeAll true
                        normalizedManualUpdateIdentity(entry.notificationIdentity) !=
                            normalizedManualUpdateIdentity(liveIdentity)
                    }
                    val orderedManualUpdates = manualUpdates.values.orderBySource(sourceOrder)
                    if (orderedManualUpdates.isNotEmpty()) {
                        val dismissalMarkers = orderedManualUpdates.dismissalMarkers()
                        val sections = listOf(
                            BundleNotificationSection(
                                header = bundleNotificationAvailable(orderedManualUpdates.size),
                                entries = orderedManualUpdates
                            )
                        )
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = bundleNotificationTitle(orderedManualUpdates.size),
                            description = sections.toNotificationText(),
                            pendingIntent = buildPendingIntent(
                                BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID,
                                if (orderedManualUpdates.size == 1) orderedManualUpdates.first().uid else null,
                                dismissalMarkers
                            ),
                            ongoing = false,
                            progress = null,
                            sections = sections,
                            dismissalMarkers = dismissalMarkers,
                            manualBundleUids = orderedManualUpdates.map { it.uid }.toIntArray()
                        )
                        notificationManager.notify(BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID, notification)
                    } else {
                        notificationManager.cancel(BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID)
                    }
                }

                when {
                    updatedAny -> {
                        val sections = if (orderedUpdatedBundles.isNotEmpty()) {
                            listOf(
                                BundleNotificationSection(
                                    header = bundleNotificationUpdated(orderedUpdatedBundles.size),
                                    entries = orderedUpdatedBundles
                                )
                            )
                        } else {
                            emptyList()
                        }
                        val description = sections.toNotificationText()
                            .ifBlank {
                                applicationContext.getString(R.string.bundle_updates_notification_completed)
                            }
                        val notification = buildNotification(
                            channelId = bundleNotificationChannel.id,
                            title = bundleNotificationTitle(orderedUpdatedBundles.size.coerceAtLeast(1)),
                            description = description,
                            pendingIntent = buildPendingIntent(
                                BUNDLE_AUTO_RESULT_NOTIFICATION_ID,
                                if (orderedUpdatedBundles.size == 1) orderedUpdatedBundles.first().uid else null
                            ),
                            ongoing = false,
                            progress = null,
                            sections = sections
                        )
                        notificationManager.cancel(progressNotificationTag, BUNDLE_PROGRESS_NOTIFICATION_ID)
                        notificationManager.notify(BUNDLE_AUTO_RESULT_NOTIFICATION_ID, notification)
                    }
                    else -> {
                        notificationManager.cancel(progressNotificationTag, BUNDLE_PROGRESS_NOTIFICATION_ID)
                    }
                }
            } else {
                notificationManager.cancel(progressNotificationTag, BUNDLE_PROGRESS_NOTIFICATION_ID)
                notificationManager.cancel(BUNDLE_AUTO_RESULT_NOTIFICATION_ID)
                notificationManager.cancel(BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID)
            }

            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("BundleAutoUpdateWorker", "Error during work: ${e.message}")
            Result.failure()
        } finally {
            runCatching {
                val notificationManager =
                    applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(progressNotificationTag, BUNDLE_PROGRESS_NOTIFICATION_ID)
            }
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private companion object {
        private const val BUNDLE_PROGRESS_NOTIFICATION_ID = 9001
        private const val BUNDLE_AUTO_RESULT_NOTIFICATION_ID = 9100
        private const val BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID = MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID
        private const val WAKE_LOCK_TAG = "upm:bundle_update_worker"
        private const val WAKE_LOCK_TIMEOUT_MS = 20L * 60L * 1000L
    }

    private data class ProgressInfo(
        val bytesRead: Long,
        val bytesTotal: Long?
    )

    private data class BundleUpdateNotificationEntry(
        val uid: Int,
        val name: String,
        val version: String,
        val notificationIdentity: String = version
    ) {
        val displayLine: String
            get() = listOf(
                name.trim(),
                version.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { version -> if (version.startsWith("v", ignoreCase = true)) version else "v$version" }
            )
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" ")
    }

    private data class BundleNotificationSection(
        val header: String,
        val entries: List<BundleUpdateNotificationEntry>
    )

    private fun buildNotification(
        channelId: String,
        title: String,
        description: String,
        pendingIntent: PendingIntent,
        ongoing: Boolean,
        progress: ProgressInfo?,
        sections: List<BundleNotificationSection> = emptyList(),
        dismissalMarkers: Array<String> = emptyArray(),
        manualBundleUids: IntArray? = null
    ): Notification {
        val builder = Notification.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(description)
            .applyManagerNotificationIcon(applicationContext)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        manualBundleUids?.let { uids ->
            builder.addExtras(Bundle().apply {
                putIntArray(EXTRA_MANUAL_BUNDLE_UIDS, uids)
                putStringArray(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS, dismissalMarkers)
            })
        }
        buildDismissPendingIntent(dismissalMarkers)?.let(builder::setDeleteIntent)

        if (progress != null) {
            // Bound orphaned notifications if the process dies before finally runs.
            builder.setTimeoutAfter(WAKE_LOCK_TIMEOUT_MS)
            val total = progress.bytesTotal?.takeIf { it > 0L }
            if (total == null) {
                builder.applyProgressNotification(
                    context = applicationContext,
                    contentTitle = title,
                    contentText = description,
                    max = 0,
                    current = 0,
                    indeterminate = true,
                    ongoing = ongoing
                )
            } else {
                val max = min(total, Int.MAX_VALUE.toLong()).toInt()
                val current = min(progress.bytesRead, max.toLong()).toInt()
                builder.applyProgressNotification(
                    context = applicationContext,
                    contentTitle = title,
                    contentText = description,
                    max = max,
                    current = current,
                    indeterminate = false,
                    ongoing = ongoing
                )
            }
        } else {
            builder.setProgress(0, 0, false)
        }

        if (sections.isNotEmpty()) {
            val style = InboxStyle()
                .setBigContentTitle(title)
            sections.forEach { section ->
                style.addLine(section.header)
                section.entries.forEach { entry ->
                    style.addLine("• ${entry.displayLine}")
                }
            }
            builder.setStyle(style)
        }

        return builder.build()
    }

    private fun bundleNotificationTitle(count: Int): String =
        applicationContext.resources.getQuantityString(
            R.plurals.bundle_updates_notification_title_found_quantity,
            count,
            count
        )

    private fun bundleNotificationAvailable(count: Int): String =
        applicationContext.resources.getQuantityString(
            R.plurals.bundle_updates_notification_available_quantity,
            count,
            count
        )

    private fun bundleNotificationUpdated(count: Int): String =
        applicationContext.resources.getQuantityString(
            R.plurals.bundle_updates_notification_updated_quantity,
            count,
            count
        )

    private fun Collection<BundleUpdateNotificationEntry>.orderBySource(
        sourceOrder: List<Int>
    ): List<BundleUpdateNotificationEntry> {
        val order = sourceOrder.withIndex().associate { it.value to it.index }
        return sortedWith(
            compareBy<BundleUpdateNotificationEntry> { order[it.uid] ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
                .thenBy { it.uid }
        )
    }

    private fun buildDismissPendingIntent(markers: Array<String>): PendingIntent? {
        if (markers.isEmpty()) return null
        val intent = Intent(
            applicationContext,
            BundleUpdateNotificationDismissReceiver::class.java
        ).apply {
            action = BundleUpdateNotificationDismissReceiver.ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED
            putExtra(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS, markers)
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            BUNDLE_MANUAL_UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun List<BundleUpdateNotificationEntry>.dismissalMarkers(): Array<String> =
        mapNotNull { manualUpdateDismissalMarker(it.uid, it.notificationIdentity) }
            .distinct()
            .toTypedArray()

    private fun isManualUpdateDismissed(uid: Int, notificationIdentity: String): Boolean {
        val marker = manualUpdateDismissalMarker(uid, notificationIdentity) ?: return false
        return BundleUpdateNotificationDismissReceiver.dismissedMarkers(applicationContext).contains(marker)
    }

    private fun List<BundleNotificationSection>.toNotificationText(): String =
        flatMap { section ->
            buildList {
                add(section.header)
                section.entries.forEach { entry -> add("• ${entry.displayLine}") }
            }
        }.joinToString("\n")

    private fun manualUpdateDismissalMarker(uid: Int, notificationIdentity: String): String? {
        val normalizedIdentity = normalizedManualUpdateIdentity(notificationIdentity) ?: return null
        return "$uid:$normalizedIdentity"
    }

    private fun normalizedManualUpdateIdentity(notificationIdentity: String): String? {
        val trimmedIdentity = notificationIdentity.trim().takeIf { it.isNotBlank() } ?: return null
        val normalizedVersion = trimmedIdentity
            .substringBefore('|')
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .trim()
            .lowercase()
            .takeIf { it.isNotBlank() } ?: return null
        val artifactIdentity = trimmedIdentity
            .substringAfter('|', missingDelimiterValue = "")
            .trim()
        val normalizedArtifactIdentity = when {
            artifactIdentity.isBlank() -> ""
            artifactIdentity.startsWith("sha256:", ignoreCase = true) -> artifactIdentity.lowercase()
            artifactIdentity.startsWith("url:", ignoreCase = true) ->
                "url:${artifactIdentity.substring(4)}"
            else -> artifactIdentity
        }
        val normalizedIdentity = if (normalizedArtifactIdentity.isBlank()) {
            normalizedVersion
        } else {
            "$normalizedVersion|$normalizedArtifactIdentity"
        }
        return normalizedIdentity
    }
}
