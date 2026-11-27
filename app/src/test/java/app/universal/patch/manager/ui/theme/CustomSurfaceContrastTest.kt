package app.universal.patch.manager.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomSurfaceContrastTest {
    @Test
    fun `custom light and dark palettes keep text readable on expressive rows`() {
        val light = lightColorScheme(
            surface = Color(0xFF848484), onSurface = Color.Black,
            surfaceContainerHigh = Color(0xFF686868), onSurfaceVariant = Color.White,
            surfaceContainerLow = Color(0xFF8B8B8B)
        )
        val dark = darkColorScheme(
            surface = Color(0xFF707070), onSurface = Color.White,
            surfaceContainerHigh = Color(0xFF8C8C8C), onSurfaceVariant = Color.Black,
            surfaceContainerLow = Color(0xFF686868)
        )
        for (palette in listOf(light, dark)) {
            val resolved = palette.withReadableCustomSurfaces()
            for (foreground in listOf(resolved.onSurface, resolved.onSurfaceVariant)) {
                for (tone in listOf(resolved.surfaceVariant, resolved.surfaceBright, resolved.surfaceDim,
                    resolved.surfaceContainerLowest, resolved.surfaceContainerLow, resolved.surfaceContainer,
                    resolved.surfaceContainerHigh, resolved.surfaceContainerHighest)) {
                    val fg = foreground.luminance() + 0.05f
                    val bg = tone.luminance() + 0.05f
                    assertTrue(maxOf(fg, bg) / minOf(fg, bg) >= 4.5f)
                }
            }
            assertEquals(palette.surfaceContainerLow, resolved.surfaceContainerLow)
            assertEquals(palette.primary, resolved.primary)
        }
    }
}
