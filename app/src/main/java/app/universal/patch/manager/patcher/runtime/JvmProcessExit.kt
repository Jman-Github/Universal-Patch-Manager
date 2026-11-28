package app.universal.patch.manager.patcher.runtime

// Adapted from Morphe Manager's HostEvent.JvmEnded, see third-party/NOTICE.txt.
// https://github.com/MorpheApp/morphe-manager/pull/1474

internal data class JvmProcessExit(val status: Int, val output: List<String>) {
    val heapNotReserved get() = output.any { "Could not reserve enough space" in it }

    fun describe(context: String): String {
        val reason = when (status) {
            in Int.MIN_VALUE until 0 -> "wait failed (errno ${-status})"
            159 -> "made a system call Android blocks (signal 31)"
            132, 134, 135, 136, 139 -> "crashed (signal ${status - 128})"
            in 129..255 -> "was killed (signal ${status - 128})"
            else -> "exited with status $status"
        }
        return "$context ($reason)" + if (output.isEmpty()) "" else ":\n" + output.joinToString("\n")
    }

    companion object {
        fun fromEvent(event: Map<String, Any?>): JvmProcessExit? {
            if (event["type"] != "jvm_ended") return null
            val status = event["status"] as? Number ?: return null
            val output = (event["output"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            return JvmProcessExit(status.toInt(), output)
        }
    }
}
