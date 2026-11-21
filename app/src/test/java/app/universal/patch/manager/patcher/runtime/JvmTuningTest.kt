package app.universal.patch.manager.patcher.runtime

import kotlin.test.*

class JvmTuningTest {
    @Test fun aotWorksOnBothAbis() {
        val flags = listOf("-XX:AOTMode=record")
        assertTrue(JvmTuning.options(true, flags).containsAll(flags))
        assertTrue(JvmTuning.options(true, flags).none { it.contains("TieredStopAtLevel") })
        assertTrue(JvmTuning.options(false, flags).containsAll(flags))
    }

    @Test fun thirtyTwoBitUsesSerialGcAndCapsTheCodeCache() {
        assertEquals(listOf("-Djava.awt.headless=true", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=64m"), JvmTuning.options(false))
    }

    @Test fun sixtyFourBitUsesCompactHeadersOnJdk25() {
        assertEquals(listOf("-Djava.awt.headless=true", "-XX:TieredStopAtLevel=1", "-XX:+UseCompactObjectHeaders"), JvmTuning.options(true))
        assertTrue(JvmTuning.options(false).none { it.contains("CompactObjectHeaders") })
    }
}
