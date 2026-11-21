package app.universal.patch.manager.patcher.worker

import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.StepId
import org.junit.Assert.*
import org.junit.Test

class ReseamProgressFractionTest {
    @Test fun splitPhaseChangesAndComponentChangesDoNotRestartProgress() {
        val id = StepId.PrepareSplitApk
        val titles = listOf("Extracting split APKs", "Inspecting split APKs",
            "Preparing base.apk", "Preparing config.en.apk")
        val events = listOf(
            ProgressEvent.Progress(id, 0, 100, titles[0]),
            ProgressEvent.Progress(id, 100, 100, titles[0]),
            ProgressEvent.Progress(id, 0, 1, titles[1]),
            ProgressEvent.Progress(id, 0, 1, titles[2]),
            ProgressEvent.Progress(id, 1, 1, titles[2]),
            ProgressEvent.Progress(id, 0, 1, titles[3]),
            ProgressEvent.Progress(id, 1, 1, titles[3])
        )
        val fractions = events.map { reseamStageFraction(it, titles)!! }
        assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f / 3, fractions[1], 0.00001f)
        assertEquals(fractions[1], fractions[2])
        assertTrue(fractions.last() < 1f)
    }

    @Test fun outputIsNotCompleteUntilPublication() {
        val id = StepId.WriteAPK
        val events = listOf(
            ProgressEvent.Progress(id, message = "Writing patched APKs"),
            ProgressEvent.Progress(id, 1, 1, "Writing patched APKs"),
            ProgressEvent.Progress(id, 0, 2, "Preparing unsigned APKs"),
            ProgressEvent.Progress(id, 1, 2, "Preparing unsigned APKs"),
            ProgressEvent.Progress(id, 2, 2, "Preparing unsigned APKs"),
            ProgressEvent.Progress(id, 0, 100, "Finalizing output"),
            ProgressEvent.Progress(id, 100, 100, "Finalizing output")
        )
        val fractions = events.map { reseamStageFraction(it, emptyList())!! }
        assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f / 3, fractions[1], 0.00001f)
        assertEquals(2f / 3, fractions[4], 0.00001f)
        assertTrue(fractions.last() < 1f)
    }

    @Test fun strippingUsesItsOwnShareAndFinalizingCannotRegress() {
        val titles = listOf("Writing patched APKs", "Preparing unsigned APKs", "Stripping native libraries", "Finalizing output")
        val fractions = titles.flatMap { title -> listOf(0L, 1L, 2L).map { current ->
            reseamStageFraction(ProgressEvent.Progress(StepId.WriteAPK, current, 2, title, titles), emptyList())!!
        } }
        assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0.5f, fractions[5], 0.00001f)
        assertEquals(0.75f, fractions[8], 0.00001f)
        assertTrue(fractions.last() < 1f)
    }

    @Test fun unavailableTotalsAndUnrelatedEventsDoNotInventProgress() {
        assertEquals(0f, reseamStageFraction(
            ProgressEvent.Progress(StepId.PrepareSplitApk, 0, 0, "Extracting split APKs"), emptyList())!!, 0f)
        assertNull(reseamStageFraction(
            ProgressEvent.Progress(StepId.DownloadAPK, 1, 2), emptyList()))
        assertNull(reseamStageFraction(
            ProgressEvent.Progress(StepId.PrepareSplitApk, 0, 1, "Preparing missing.apk"), emptyList()))
    }
}
