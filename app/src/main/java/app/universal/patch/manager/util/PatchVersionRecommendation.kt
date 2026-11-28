package app.universal.patch.manager.util

internal fun selectSuggestedPatchVersion(versions: Map<String, Int>): String? {
    if (versions.isEmpty()) return null
    if (versions.keys.size < 2) return versions.keys.firstOrNull()
    return versions.entries.maxWithOrNull { a, b ->
        val count = a.value.compareTo(b.value)
        if (count != 0) count else compareVersionStrings(a.key, b.key)
    }?.key
}

private fun compareVersionStrings(first: String, second: String): Int {
    val aParts = first.split(Regex("[^0-9]+"))
        .filter { it.isNotBlank() }
        .map { it.toBigInteger() }
    val bParts = second.split(Regex("[^0-9]+"))
        .filter { it.isNotBlank() }
        .map { it.toBigInteger() }
    val max = maxOf(aParts.size, bParts.size)
    for (index in 0 until max) {
        val a = aParts.getOrElse(index) { java.math.BigInteger.ZERO }
        val b = bParts.getOrElse(index) { java.math.BigInteger.ZERO }
        if (a != b) return a.compareTo(b)
    }
    return first.compareTo(second, ignoreCase = true)
}

