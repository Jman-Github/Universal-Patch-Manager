package app.universal.patch.manager.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppNameParserTest {
    @Test fun readsRegistryMetadata() {
        assertEquals("YouTube" to "https://play-lh.googleusercontent.com/icon.png", parseRegistryAppMetadata(
            """{"package_name":"com.google.android.youtube","app_name":"YouTube","icon_url":"https://play-lh.googleusercontent.com/icon.png"}""",
            "com.google.android.youtube"))
    }

    @Test fun incompleteRegistryMetadataAllowsStoreFallback() {
        assertEquals(null to null, parseRegistryAppMetadata(
            """{"package_name":"org.example.app","app_name":"org.example.app","icon_url":null}""", "org.example.app"))
        assertEquals("Example" to null, parseRegistryAppMetadata(
            """{"package_name":"org.example.app","app_name":"Example","icon_url":"file:///data/icon.png"}""", "org.example.app"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsRegistryMetadataForAnotherApp() {
        parseRegistryAppMetadata("""{"package_name":"org.other.app","app_name":"Other"}""", "org.example.app")
    }

    @Test fun readsStructuredPlayNameFromGraphs() {
        val html = """<script type="application/ld+json">{"@graph":[{"@type":"WebSite","name":"Store"},{"@type":["Thing","SoftwareApplication"],"name":"YouTube"}]}</script>"""
        assertEquals("YouTube", parseStoreAppName(html, "play"))
    }

    @Test fun fallsBackToPlayTitleWithReorderedAttributes() {
        assertEquals("YouTube", parseStoreAppName("<meta content='YouTube - Apps on Google Play' property='og:title'>", "play"))
    }

    @Test fun ignoresConsentPagesAndUnrelatedStructuredData() {
        assertNull(parseStoreAppName("<meta property='og:title' content='Before you continue'><script type='application/ld+json'>{\"@type\":\"WebSite\",\"name\":\"Google\"}</script>", "play"))
    }

    @Test fun malformedJsonStillAllowsValidTitle() {
        assertEquals("YouTube", parseStoreAppName("<script type='application/ld+json'>{broken}</script><meta property='og:title' content='YouTube - Apps on Google Play'>", "play"))
    }

    @Test fun readsFdroidTitle() {
        assertEquals("NewPipe", parseStoreAppName("<meta property='og:title' content='NewPipe | F-Droid - Free and Open Source Android App Repository'>", "fdroid"))
    }

    @Test fun izzyNameRequiresMatchingPackageIdentity() {
        val html = "<h2>Example</h2><tr><td><b>AppID:</b></td><td>org.example.app</td></tr>"
        assertEquals("Example", parseStoreAppName(html, "izzy", "org.example.app"))
        assertNull(parseStoreAppName(html, "izzy", "org.other.app"))
    }

    @Test fun rejectsNonPackageFallbackTextAndPathInjection() {
        assertTrue(appNamePackagePattern.matches("com.google.android.youtube"))
        assertFalse(appNamePackagePattern.matches("Not installed"))
        assertFalse(appNamePackagePattern.matches("com.example/../../app"))
    }

    @Test fun readsPlayStructuredIconObjects() {
        val html = """<script type="application/ld+json">{"@type":"SoftwareApplication","name":"YouTube","image":[{"url":"https://play-lh.googleusercontent.com/icon.png"}]}</script>"""
        assertEquals("https://play-lh.googleusercontent.com/icon.png", parseStoreAppIconUrl(html, "play"))
    }

    @Test fun skipsUnusableEntriesInStructuredIconLists() {
        val html = """<script type="application/ld+json">{"@type":"SoftwareApplication","name":"Example","image":["http://play-lh.googleusercontent.com/old.png","https://play-lh.googleusercontent.com/valid.png"]}</script>"""
        assertEquals("https://play-lh.googleusercontent.com/valid.png", parseStoreAppIconUrl(html, "play"))
    }

    @Test fun triesContentUrlWhenImageObjectUrlIsUnusable() {
        val html = """<script type="application/ld+json">{"@type":"SoftwareApplication","name":"Example","image":{"url":"file:///icon.png","contentUrl":"https://play-lh.googleusercontent.com/valid.png"}}</script>"""
        assertEquals("https://play-lh.googleusercontent.com/valid.png", parseStoreAppIconUrl(html, "play"))
    }

    @Test fun readsPlayOpenGraphIconAndDecodesQuerySeparators() {
        val html = "<meta property='og:title' content='Example - Apps on Google Play'><meta content='https://play-lh.googleusercontent.com/icon.png?a=1&amp;b=2' property='og:image'>"
        assertEquals("https://play-lh.googleusercontent.com/icon.png?a=1&b=2", parseStoreAppIconUrl(html, "play"))
    }

    @Test fun readsRelativeFdroidPackageIcons() {
        val html = "<meta property='og:title' content='Example | F-Droid - Free and Open Source Android App Repository'><img class='package-icon' src='/repo/icon.png'>"
        assertEquals("https://f-droid.org/repo/icon.png", parseStoreAppIconUrl(html, "fdroid"))
    }

    @Test fun readsRelativeIzzyIconsOnlyForMatchingPackage() {
        val html = "<h2>Example</h2><tr><td><b>AppID:</b></td><td>org.example.app</td></tr><img class='appicon' src='/fdroid/repo/icon.png'>"
        assertEquals("https://apt.izzysoft.de/fdroid/repo/icon.png", parseStoreAppIconUrl(html, "izzy", "org.example.app"))
        assertNull(parseStoreAppIconUrl(html, "izzy", "org.other.app"))
    }

    @Test fun doesNotUseGenericStoreImagesFromConsentPages() {
        assertNull(parseStoreAppIconUrl("<meta property='og:title' content='Before you continue'><meta property='og:image' content='https://play-lh.googleusercontent.com/logo.png'>", "play"))
    }

    @Test fun rejectsNonProviderAndNonHttpsIconUrls() {
        for (url in listOf("file:///data/icon.png", "http://play-lh.googleusercontent.com/icon.png",
            "https://googleusercontent.com.example.org/icon.png", "https://user@play-lh.googleusercontent.com/icon.png")) {
            assertNull(safeStoreAppIconUrl(url, "play"))
        }
    }
}
