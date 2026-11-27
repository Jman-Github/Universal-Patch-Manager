package app.universal.patch.manager.patcher.runtime.morphe

enum class MorpheBytecodeMode(
    val runtimeValue: String,
) {
    FAST("STRIP_FAST"),
    SAFE("STRIP_SAFE");

    companion object {
        fun fromRuntimeValue(value: String?): MorpheBytecodeMode =
            entries.firstOrNull { it.runtimeValue.equals(value, ignoreCase = true) } ?: FAST
    }
}
