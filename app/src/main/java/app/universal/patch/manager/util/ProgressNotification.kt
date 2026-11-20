package app.universal.patch.manager.util

import android.app.Notification
import android.os.Build
import android.text.BidiFormatter
import android.text.TextUtils
import java.text.NumberFormat

/** Lets System UI lay out progress within its own notification bounds. */
fun Notification.Builder.applyProgressNotification(
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

    // Some callers pass progress as text too. Keep only the actual operation detail.
    val detail = contentText?.takeUnless {
        it.isBlank() || it.toString().matches(
            Regex("""[\p{N}.,\s\p{Z}\p{Cf}]+[%\u066A\uFF05][\s\p{Z}\p{Cf}]*""")
        )
    }

    // The compact system template can hide the body while showing a progress bar.
    val title = if (percentage == null) {
        contentTitle
    } else {
        val bidi = BidiFormatter.getInstance()
        TextUtils.concat(bidi.unicodeWrap(percentage), " · ", bidi.unicodeWrap(contentTitle))
    }
    setContentTitle(title)
    setContentText(detail)
    setCategory(Notification.CATEGORY_PROGRESS)
    setOnlyAlertOnce(true)
    setOngoing(ongoing)
    setShowWhen(false)
    setProgress(safeMax, safeCurrent, unknown)
    detail?.let { setStyle(Notification.BigTextStyle().bigText(it)) }
    if (Build.VERSION.SDK_INT >= 36) {
        setShortCriticalText(percentage)
    }
    return this
}
