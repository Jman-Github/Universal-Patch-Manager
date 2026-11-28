package app.universal.patch.manager.ui.model

private val settingsSearchWhitespace = Regex("\\s+")

internal fun settingsSearchTerms(query: String): List<String> =
    query.trim().split(settingsSearchWhitespace).filter(String::isNotBlank)

/** Match every word, regardless of its position in the setting's labels. */
internal fun matchesSettingsSearch(text: String, query: String): Boolean {
    val terms = settingsSearchTerms(query)
    if (terms.isEmpty()) return false
    return terms.all { text.contains(it, ignoreCase = true) }
}
