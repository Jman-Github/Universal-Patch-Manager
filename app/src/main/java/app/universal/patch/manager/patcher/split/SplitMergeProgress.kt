package app.universal.patch.manager.patcher.split

/** Preserve numeric stripping updates from the shared tools host. */
internal fun dispatchSplitMergeProgress(
    event: Map<*, *>,
    onProgress: (String) -> Unit,
    onSubSteps: (List<String>) -> Unit,
    onStripProgress: ((Long, Long) -> Unit)?
) {
    val message = event["message"] as? String
    val current = (event["current"] as? Number)?.toLong()
    val total = (event["total"] as? Number)?.toLong()
    if (message == "Stripping native libraries" && current != null &&
        total != null && total > 0L && onStripProgress != null
    ) {
        onStripProgress(current.coerceIn(0L, total), total)
    } else {
        message?.let(onProgress)
    }
    (event["subSteps"] as? List<*>)?.let { steps ->
        onSubSteps(steps.mapNotNull { it as? String })
    }
}
