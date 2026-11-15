package app.universal.patch.manager.ui.model

import app.universal.patch.manager.patcher.StepId
import java.util.Locale

/** Aggregate child work instead of reusing counters from unrelated phases. */
internal fun expandableStepProgressFraction(stepId: StepId, details: List<StepDetail>): Double? {
    val visible = details.filterNot { it.skipped }
    if (visible.isEmpty()) return null
    if (stepId == StepId.PrepareSplitApk &&
        visible.any { it.title == "Extracting split APKs" } &&
        visible.any { it.title == "Inspecting split APKs" }
    ) {
        val extraction = visible.first { it.title == "Extracting split APKs" }.completionUnits().first
        val inspection = visible.first { it.title == "Inspecting split APKs" }.completionUnits().first
        val components = visible.filter { it.title.startsWith("Preparing ") }
        val prepared = components.map { it.completionUnits().first }.average().takeIf { it.isFinite() } ?: 0.0
        return (extraction + inspection + prepared) / 3.0
    }
    val units = visible.map { it.completionUnits() }
    return units.sumOf { it.first } / units.sumOf { it.second }.toDouble()
}

private fun StepDetail.completionUnits(): Pair<Double, Int> {
    val visibleChildren = children.filterNot { it.skipped }
    if (visibleChildren.isNotEmpty()) {
        val units = visibleChildren.map { it.completionUnits() }
        val total = units.sumOf { it.second }
        return (if (state == State.COMPLETED) total.toDouble() else units.sumOf { it.first }) to total
    }
    val fraction = when (state) {
        State.COMPLETED -> 1.0
        State.RUNNING -> progress?.let { (current, total) ->
            total?.takeIf { it > 0L }?.let { (current.toDouble() / it).coerceIn(0.0, 1.0) }
        } ?: 0.0
        else -> 0.0
    }
    return fraction to 1
}

internal fun isComponentCountProgress(title: String): Boolean =
    title == "Inspecting split APKs" || title == "Preparing split APKs" ||
        title == "Writing patched APKs" || title == "Preparing unsigned APKs" ||
        (title.startsWith("Preparing ") && title.endsWith(".apk", ignoreCase = true))

internal fun isPercentageProgress(title: String): Boolean = title == "Stripping native libraries"

internal fun StepDetail.displayProgress(): Pair<Float?, String?> =
    if (state == State.COMPLETED && !skipped && isPercentageProgress(title)) {
        1f to "100%"
    } else {
        progress.asDisplayProgress(isComponentCountProgress(title), isPercentageProgress(title))
    }

private val Long.megaBytes get() = "%.1f".format(locale = Locale.ROOT, toDouble() / 1_000_000)

internal fun Pair<Long, Long?>?.asDisplayProgress(
    componentCount: Boolean = false, percentage: Boolean = false
): Pair<Float?, String?> =
    this?.let { (current, total) ->
        val fraction = total?.takeIf { it > 0L }?.let { (current.toFloat() / it).coerceIn(0f, 1f) }
        val text = if (percentage) {
            total?.takeIf { it > 0L }?.let {
                (current.coerceIn(0L, it).toDouble() * 100 / it).toInt().toString() + "%"
            }
        } else if (componentCount) {
            if (total != null && total > 0L) "$current/$total" else "$current"
        } else {
            if (total != null && total > 0L) "${current.megaBytes}/${total.megaBytes} MB" else "${current.megaBytes} MB"
        }
        // Component counters advance only when an APK finishes. Keep activity visible
        // while the first APK is still being processed instead of drawing a 0% ring.
        (if ((componentCount || percentage) && current == 0L) null else fraction) to text
    } ?: (null to null)
