package app.universal.patch.manager.patcher.split

import org.junit.Assert.*
import org.junit.Test

class SplitMergeProgressTest {
    @Test fun strippingCountersSurviveTheToolsEventBridge() {
        val labels = mutableListOf<String>()
        val values = mutableListOf<Pair<Long, Long>>()
        val steps = mutableListOf<List<String>>()
        for (percent in listOf(0, 25, 53, 59, 99, 100)) {
            dispatchSplitMergeProgress(
                mapOf("message" to "Stripping native libraries",
                    "current" to percent, "total" to 100,
                    "subSteps" to listOf("Writing merged APK", "Stripping native libraries")),
                labels::add, steps::add, { current, total -> values += current to total }
            )
        }
        assertTrue(labels.isEmpty())
        assertEquals(listOf(0L, 25L, 53L, 59L, 99L, 100L), values.map { it.first })
        assertTrue(values.all { it.second == 100L })
        assertEquals(6, steps.size)
    }

    @Test fun legacyEventsAndClientsKeepTheirTextUpdates() {
        val labels = mutableListOf<String>()
        var numericCalls = 0
        dispatchSplitMergeProgress(mapOf("message" to "Stripping native libraries"),
            labels::add, {}, { _, _ -> numericCalls++ })
        dispatchSplitMergeProgress(mapOf("message" to "Stripping native libraries",
            "current" to 30, "total" to 100), labels::add, {}, null)
        dispatchSplitMergeProgress(mapOf("message" to "Finalizing merged APK"),
            labels::add, {}, { _, _ -> numericCalls++ })
        assertEquals(listOf("Stripping native libraries", "Stripping native libraries", "Finalizing merged APK"), labels)
        assertEquals(0, numericCalls)
    }

    @Test fun invalidTotalsDoNotInventACompletedPercentage() {
        var label: String? = null
        dispatchSplitMergeProgress(mapOf("message" to "Stripping native libraries",
            "current" to 25, "total" to 0), { label = it }, {},
            { _, _ -> fail("Zero totals must remain indeterminate") })
        assertEquals("Stripping native libraries", label)
    }
}
