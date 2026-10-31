package app.universal.patch.manager.util

import android.app.Notification
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import app.universal.patch.manager.R
import java.text.NumberFormat

/**
 * Uses the same decorated progress layout on every Android version.
 * ProgressStyle owns its renderer and
 * cannot hide its end marker or position the percentage above the bar.
 */
fun Notification.Builder.applyProgressNotification(
    context: Context,
    contentTitle: CharSequence,
    contentText: CharSequence?,
    max: Int,
    current: Int,
    indeterminate: Boolean,
    ongoing: Boolean = true
): Notification.Builder {
    val safeMax = max.coerceAtLeast(0)
    val safeCurrent = current.coerceIn(0, safeMax)
    val unknown = indeterminate || safeMax == 0
    val percentage = if (unknown) null else NumberFormat.getPercentInstance().format(
        (safeCurrent.toLong() * 100L / safeMax).toDouble() / 100.0
    )

    setContentTitle(contentTitle)
    setContentText(contentText)
    setCategory(Notification.CATEGORY_PROGRESS)
    setOnlyAlertOnce(true)
    setOngoing(ongoing)
    setShowWhen(false)
    // DecoratedCustomViewStyle renders the framework progress bar in addition to
    // the custom content when these fields are populated. Only the custom view
    // should draw progress, including its indeterminate state.
    setProgress(0, 0, false)

    fun content(expanded: Boolean) = RemoteViews(
        context.packageName,
        if (expanded) R.layout.notification_progress_expanded else R.layout.notification_progress
    ).apply {
        setTextViewText(R.id.notification_title, contentTitle)
        setTextViewText(R.id.notification_detail, contentText)
        setTextViewText(R.id.notification_percentage, percentage)
        setViewVisibility(R.id.notification_percentage, if (percentage == null) View.GONE else View.VISIBLE)
        setProgressBar(R.id.notification_progress, safeMax, safeCurrent, unknown)
    }

    setStyle(Notification.DecoratedCustomViewStyle())
    setCustomContentView(content(expanded = false))
    setCustomBigContentView(content(expanded = true))
    if (Build.VERSION.SDK_INT >= 36) {
        setShortCriticalText(percentage)
    }
    return this
}
