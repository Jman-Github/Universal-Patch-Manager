package app.universal.patch.manager.ui.screen

import androidx.compose.ui.text.SpanStyle
import app.universal.patch.manager.ui.model.matchesSettingsSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchHighlightTextTest {
    @Test
    fun `separate and reordered words highlight their original label positions`() {
        val label = "Reset dashboard layout"
        val query = "  LAYOUT   reset "
        assertTrue(matchesSettingsSearch(label, query))
        val result = buildHighlightedText(label, query, SpanStyle())
        assertEquals(label, result.text)
        assertEquals(setOf("Reset", "layout"), result.spanStyles.map {
            result.text.substring(it.start, it.end)
        }.toSet())
    }

    @Test
    fun `Turkish search highlights use offsets in the displayed label`() {
        val label = "İndirilenler"
        for (query in listOf("ler", "indirilenler", "İndirilenler")) {
            assertTrue(matchesSettingsSearch(label, query))
            val result = buildHighlightedText(label, query, SpanStyle())
            assertEquals(label, result.text)
            val span = result.spanStyles.single()
            assertEquals(label.length - query.length, span.start)
            assertEquals(label.length, span.end)
        }
    }
}
