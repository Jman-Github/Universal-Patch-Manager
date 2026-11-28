package app.universal.patch.manager.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CornerBasedShape
import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsEndWidth
import androidx.compose.foundation.layout.windowInsetsStartWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import app.universal.patch.manager.R
import kotlinx.serialization.Serializable

internal val LocalExpressiveDesign = staticCompositionLocalOf { false }

@Composable
internal fun CornerBasedShape.withClassicCorner(radius: Dp): CornerBasedShape =
    if (LocalExpressiveDesign.current) this else RoundedCornerShape(radius)

/**
 * The floating patch-selection action tray and its settings previews. It is opaque so the chips
 * stay readable over the tabs beneath it; classic uses its elevated surface tone.
 */
@Composable
internal fun actionTrayColor(): Color = if (LocalExpressiveDesign.current) {
    MaterialTheme.colorScheme.surfaceContainerHighest
} else {
    MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
}

/** Resting cards are flat in the expressive design; floating surfaces keep their shadow. */
@Composable
internal fun Dp.restingShadow(): Dp = if (LocalExpressiveDesign.current) 0.dp else this

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
    extraLargeIncreased = RoundedCornerShape(40.dp),
    extraExtraLarge = RoundedCornerShape(48.dp)
)

private val ExpressiveTypography = Typography.copy(
    displayLarge = Typography.displayLargeEmphasized,
    displayMedium = Typography.displayMediumEmphasized,
    displaySmall = Typography.displaySmallEmphasized,
    headlineLarge = Typography.headlineLargeEmphasized,
    headlineMedium = Typography.headlineMediumEmphasized,
    headlineSmall = Typography.headlineSmallEmphasized,
    titleLarge = Typography.titleLargeEmphasized,
    titleMedium = Typography.titleMediumEmphasized,
    titleSmall = Typography.titleSmallEmphasized,
    labelSmall = Typography.labelSmallEmphasized,
    labelMedium = Typography.labelMediumEmphasized,
    labelLarge = Typography.labelLargeEmphasized
)

private val DarkColorScheme = darkColorScheme(
    primary = rv_theme_dark_primary,
    onPrimary = rv_theme_dark_onPrimary,
    primaryContainer = rv_theme_dark_primaryContainer,
    onPrimaryContainer = rv_theme_dark_onPrimaryContainer,
    secondary = rv_theme_dark_secondary,
    onSecondary = rv_theme_dark_onSecondary,
    secondaryContainer = rv_theme_dark_secondaryContainer,
    onSecondaryContainer = rv_theme_dark_onSecondaryContainer,
    tertiary = rv_theme_dark_tertiary,
    onTertiary = rv_theme_dark_onTertiary,
    tertiaryContainer = rv_theme_dark_tertiaryContainer,
    onTertiaryContainer = rv_theme_dark_onTertiaryContainer,
    error = rv_theme_dark_error,
    errorContainer = rv_theme_dark_errorContainer,
    onError = rv_theme_dark_onError,
    onErrorContainer = rv_theme_dark_onErrorContainer,
    background = rv_theme_dark_background,
    onBackground = rv_theme_dark_onBackground,
    surface = rv_theme_dark_surface,
    onSurface = rv_theme_dark_onSurface,
    surfaceVariant = rv_theme_dark_surfaceVariant,
    onSurfaceVariant = rv_theme_dark_onSurfaceVariant,
    outline = rv_theme_dark_outline,
    inverseOnSurface = rv_theme_dark_inverseOnSurface,
    inverseSurface = rv_theme_dark_inverseSurface,
    inversePrimary = rv_theme_dark_inversePrimary,
    surfaceTint = rv_theme_dark_surfaceTint,
    outlineVariant = rv_theme_dark_outlineVariant,
    scrim = rv_theme_dark_scrim,
    surfaceDim = rv_theme_dark_surfaceDim,
    surfaceBright = rv_theme_dark_surfaceBright,
    surfaceContainerLowest = rv_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = rv_theme_dark_surfaceContainerLow,
    surfaceContainer = rv_theme_dark_surfaceContainer,
    surfaceContainerHigh = rv_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = rv_theme_dark_surfaceContainerHighest,
)

private val LightColorScheme = lightColorScheme(
    primary = rv_theme_light_primary,
    onPrimary = rv_theme_light_onPrimary,
    primaryContainer = rv_theme_light_primaryContainer,
    onPrimaryContainer = rv_theme_light_onPrimaryContainer,
    secondary = rv_theme_light_secondary,
    onSecondary = rv_theme_light_onSecondary,
    secondaryContainer = rv_theme_light_secondaryContainer,
    onSecondaryContainer = rv_theme_light_onSecondaryContainer,
    tertiary = rv_theme_light_tertiary,
    onTertiary = rv_theme_light_onTertiary,
    tertiaryContainer = rv_theme_light_tertiaryContainer,
    onTertiaryContainer = rv_theme_light_onTertiaryContainer,
    error = rv_theme_light_error,
    errorContainer = rv_theme_light_errorContainer,
    onError = rv_theme_light_onError,
    onErrorContainer = rv_theme_light_onErrorContainer,
    background = rv_theme_light_background,
    onBackground = rv_theme_light_onBackground,
    surface = rv_theme_light_surface,
    onSurface = rv_theme_light_onSurface,
    surfaceVariant = rv_theme_light_surfaceVariant,
    onSurfaceVariant = rv_theme_light_onSurfaceVariant,
    outline = rv_theme_light_outline,
    inverseOnSurface = rv_theme_light_inverseOnSurface,
    inverseSurface = rv_theme_light_inverseSurface,
    inversePrimary = rv_theme_light_inversePrimary,
    surfaceTint = rv_theme_light_surfaceTint,
    outlineVariant = rv_theme_light_outlineVariant,
    scrim = rv_theme_light_scrim,
    surfaceDim = rv_theme_light_surfaceDim,
    surfaceBright = rv_theme_light_surfaceBright,
    surfaceContainerLowest = rv_theme_light_surfaceContainerLowest,
    surfaceContainerLow = rv_theme_light_surfaceContainerLow,
    surfaceContainer = rv_theme_light_surfaceContainer,
    surfaceContainerHigh = rv_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = rv_theme_light_surfaceContainerHighest,
)

@Composable
fun UpmTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    pureBlackTheme: Boolean,
    accentColorHex: String? = null,
    themeColorHex: String? = null,
    hasCustomBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val expressive = LocalExpressiveDesign.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            compatibleDynamicColorScheme(context, darkTheme)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val schemeWithAccent = parseCustomColor(accentColorHex)?.let {
        applyCustomAccent(baseScheme, it, darkTheme)
    } ?: baseScheme

    val customThemeColor = parseCustomColor(themeColorHex)
    val schemeWithThemeColor = customThemeColor?.let {
        applyCustomThemeColor(schemeWithAccent, it, darkTheme)
    } ?: schemeWithAccent
    val finalScheme = if (darkTheme && pureBlackTheme) {
        val pureBlack = Color.Black
        schemeWithThemeColor.copy(
            background = pureBlack,
            onBackground = schemeWithAccent.onBackground,
            surface = pureBlack,
            onSurface = schemeWithAccent.onSurface,
            surfaceVariant = Color(0xFF161616),
            surfaceDim = pureBlack,
            surfaceBright = Color(0xFF202020),
            surfaceContainerLowest = pureBlack,
            surfaceContainerLow = Color(0xFF050505),
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerHigh = Color(0xFF101010),
            surfaceContainerHighest = Color(0xFF161616)
        )
    } else {
        schemeWithThemeColor
    }
    val surfaceScheme = if (expressive && customThemeColor != null) {
        finalScheme.withReadableCustomSurfaces()
    } else finalScheme
    val resolvedScheme = if (hasCustomBackground) {
        surfaceScheme.copy(background = Color.Transparent)
    } else surfaceScheme
    val systemBarBackground = finalScheme.surface
    val useDarkSystemBarIcons = systemBarBackground.contrastingForeground() == Color.Black

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as Activity

            WindowCompat.setDecorFitsSystemWindows(activity.window, false)

            activity.window.statusBarColor = Color.Transparent.toArgb()
            activity.window.navigationBarColor = Color.Transparent.toArgb()

            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = useDarkSystemBarIcons
                isAppearanceLightNavigationBars = useDarkSystemBarIcons
            }
        }
    }

    val body: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (hasCustomBackground) {
                Spacer(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(systemBarBackground)
                )
                Spacer(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(systemBarBackground)
                )
                Spacer(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .windowInsetsStartWidth(WindowInsets.navigationBars)
                        .background(systemBarBackground)
                )
                Spacer(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .windowInsetsEndWidth(WindowInsets.navigationBars)
                        .background(systemBarBackground)
                )
            }
        }
    }
    if (expressive) {
        MaterialExpressiveTheme(
            colorScheme = resolvedScheme, shapes = ExpressiveShapes, typography = ExpressiveTypography,
            content = body
        )
    } else {
        MaterialTheme(colorScheme = resolvedScheme, typography = Typography, content = body)
    }
}

@Serializable
enum class Theme(val displayName: Int) {
    SYSTEM(R.string.system),
    LIGHT(R.string.light),
    DARK(R.string.dark);
}

private fun parseCustomColor(hex: String?): Color? {
    val normalized = hex?.trim()
    if (normalized.isNullOrEmpty()) return null
    return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}

private fun applyCustomAccent(
    colorScheme: ColorScheme,
    accent: Color,
    darkTheme: Boolean
): ColorScheme {
    val primary = accent
    val primaryContainer = accent.adjustLightness(if (darkTheme) 0.25f else -0.25f)
    val secondary = accent.adjustLightness(if (darkTheme) 0.15f else -0.15f)
    val secondaryContainer = accent.adjustLightness(if (darkTheme) 0.35f else -0.35f)
    val tertiary = accent.adjustLightness(if (darkTheme) -0.1f else 0.1f)
    val tertiaryContainer = accent.adjustLightness(if (darkTheme) 0.4f else -0.4f)
    return colorScheme.copy(
        primary = primary,
        onPrimary = primary.contrastingForeground(),
        primaryContainer = primaryContainer,
        onPrimaryContainer = primaryContainer.contrastingForeground(),
        secondary = secondary,
        onSecondary = secondary.contrastingForeground(),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = secondaryContainer.contrastingForeground(),
        tertiary = tertiary,
        onTertiary = tertiary.contrastingForeground(),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = tertiaryContainer.contrastingForeground(),
        surfaceTint = primary,
        inversePrimary = primary.adjustLightness(if (darkTheme) -0.4f else 0.4f)
    )
}

private fun applyCustomThemeColor(
    colorScheme: ColorScheme,
    themeColor: Color,
    darkTheme: Boolean
): ColorScheme {
    val background = themeColor.adjustLightness(if (darkTheme) -0.45f else 0.55f)
    val surface = themeColor.adjustLightness(if (darkTheme) -0.35f else 0.45f)
    val surfaceVariant = themeColor.adjustLightness(if (darkTheme) -0.25f else 0.35f)
    val containerLowest = themeColor.adjustLightness(if (darkTheme) -0.4f else 0.5f)
    val containerLow = themeColor.adjustLightness(if (darkTheme) -0.38f else 0.48f)
    val container = themeColor.adjustLightness(if (darkTheme) -0.32f else 0.42f)
    val containerHigh = themeColor.adjustLightness(if (darkTheme) -0.24f else 0.34f)
    val containerHighest = themeColor.adjustLightness(if (darkTheme) -0.18f else 0.28f)
    val surfaceBright = themeColor.adjustLightness(if (darkTheme) -0.1f else 0.12f)
    val surfaceDim = themeColor.adjustLightness(if (darkTheme) -0.5f else 0.6f)
    val onBackground = background.contrastingForeground()
    val onSurface = surface.contrastingForeground()
    val onSurfaceVariant = surfaceVariant.contrastingForeground()

    return colorScheme.copy(
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = themeColor,
        surfaceContainerLowest = containerLowest,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim
    )
}

// Material components reuse onSurface and onSurfaceVariant across the container tones.
internal fun ColorScheme.withReadableCustomSurfaces(): ColorScheme {
    val foreground = onSurface.luminance() + 0.05f
    fun readable(tone: Color): Color {
        val background = tone.luminance() + 0.05f
        val contrast = maxOf(foreground, background) / minOf(foreground, background)
        return if (contrast >= 4.5f) tone else surface
    }
    return copy(
        onSurfaceVariant = onSurface,
        surfaceVariant = readable(surfaceVariant),
        surfaceDim = readable(surfaceDim),
        surfaceBright = readable(surfaceBright),
        surfaceContainerLowest = readable(surfaceContainerLowest),
        surfaceContainerLow = readable(surfaceContainerLow),
        surfaceContainer = readable(surfaceContainer),
        surfaceContainerHigh = readable(surfaceContainerHigh),
        surfaceContainerHighest = readable(surfaceContainerHighest)
    )
}

private fun Color.adjustLightness(delta: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[2] = (hsl[2] + delta).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

internal fun Color.contrastingForeground(): Color {
    val background = toArgb() or 0xFF000000.toInt()
    val blackContrast = ColorUtils.calculateContrast(AndroidColor.BLACK, background)
    val whiteContrast = ColorUtils.calculateContrast(AndroidColor.WHITE, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}
