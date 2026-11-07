package app.universal.patch.manager.ui.model

private val settingsSearchWhitespace = Regex("\\s+")

/** Match every word, regardless of its position in the setting's labels. */
internal fun matchesSettingsSearch(text: String, query: String): Boolean {
    val terms = query.trim().lowercase().split(settingsSearchWhitespace).filter(String::isNotBlank)
    if (terms.isEmpty()) return false
    val normalizedText = text.lowercase()
    return terms.all { normalizedText.contains(it) }
}
