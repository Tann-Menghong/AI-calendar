package com.aicalendar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Saffron = Color(0xFFB4531A)
private val SaffronLight = Color(0xFFFFDBC8)
private val DeepTeal = Color(0xFF00696E)
private val TealLight = Color(0xFF9DF0F5)
private val Indigo = Color(0xFF4B5C92)
private val IndigoLight = Color(0xFFDCE1FF)

private val LightColors = lightColorScheme(
    primary = Saffron,
    onPrimary = Color.White,
    primaryContainer = SaffronLight,
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = DeepTeal,
    onSecondary = Color.White,
    secondaryContainer = TealLight,
    onSecondaryContainer = Color(0xFF002022),
    tertiary = Indigo,
    onTertiary = Color.White,
    tertiaryContainer = IndigoLight,
    onTertiaryContainer = Color(0xFF03164B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB694),
    onPrimary = Color(0xFF5F1A00),
    primaryContainer = Color(0xFF882900),
    onPrimaryContainer = SaffronLight,
    secondary = Color(0xFF81D4D9),
    onSecondary = Color(0xFF00373A),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = TealLight,
    tertiary = Color(0xFFB4C5FF),
    onTertiary = Color(0xFF1C2D5F),
    tertiaryContainer = Color(0xFF334478),
    onTertiaryContainer = IndigoLight,
)

/**
 * Khmer script stacks diacritics above and subscript consonants below the baseline,
 * so the default line heights clip it. Every style therefore gets explicit, roomier
 * line height with font padding kept on — this is what makes ភាសាខ្មែរ render intact
 * next to Latin text.
 */
private fun khmerFriendly(base: Typography): Typography {
    val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    fun androidx.compose.ui.text.TextStyle.roomy(multiplier: Float = 1.45f) = copy(
        lineHeight = (fontSize.value * multiplier).sp,
        lineHeightStyle = trim,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )

    return base.copy(
        displayLarge = base.displayLarge.roomy(1.25f),
        displayMedium = base.displayMedium.roomy(1.25f),
        displaySmall = base.displaySmall.roomy(1.3f),
        headlineLarge = base.headlineLarge.roomy(1.35f),
        headlineMedium = base.headlineMedium.roomy(1.35f),
        headlineSmall = base.headlineSmall.roomy(1.4f),
        titleLarge = base.titleLarge.roomy(1.4f),
        titleMedium = base.titleMedium.roomy(1.5f),
        titleSmall = base.titleSmall.roomy(1.5f),
        bodyLarge = base.bodyLarge.roomy(1.6f),
        bodyMedium = base.bodyMedium.roomy(1.6f),
        bodySmall = base.bodySmall.roomy(1.6f),
        labelLarge = base.labelLarge.roomy(1.5f),
        labelMedium = base.labelMedium.roomy(1.5f),
        labelSmall = base.labelSmall.roomy(1.5f),
    )
}

@Composable
fun AiCalendarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = khmerFriendly(Typography()),
        content = content,
    )
}
