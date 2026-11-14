package app.universal.patch.manager.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppDisplayLabelTest {
    @Test fun packageIdIsNotApplicationMetadata() {
        assertNull(actualAppMetadataLabel("com.example.app", "com.example.app"))
    }

    @Test fun generatedApplicationClassIsNotApplicationMetadata() {
        assertNull(actualAppMetadataLabel("com.example.app.MainApplication", "com.example.app"))
    }

    @Test fun packageWithUnderscoresIsNotApplicationMetadata() {
        assertNull(actualAppMetadataLabel("com.example.some_app.MainApplication", "com.example.some_app"))
    }

    @Test fun actualNamesKeepPriorityOverOnlineNames() {
        assertEquals("My local app", actualAppMetadataLabel("My local app", "com.example.app"))
        assertEquals("Weather Application", actualAppMetadataLabel("Weather Application", "com.example.app"))
    }

    @Test fun generatedClassInAnotherNamespaceIsNotApplicationMetadata() {
        assertNull(actualAppMetadataLabel("org.other.MainApplication", "com.example.app"))
    }
}
