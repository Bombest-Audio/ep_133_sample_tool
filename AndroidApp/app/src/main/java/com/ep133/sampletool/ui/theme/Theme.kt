package com.ep133.sampletool.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val TELightColorScheme = lightColorScheme(
    primary = TEColors.Orange,
    onPrimary = Color.White,
    primaryContainer = TEColors.OrangeContainer,
    onPrimaryContainer = TEColors.OrangeDark,
    secondary = TEColors.Teal,
    onSecondary = Color.White,
    secondaryContainer = TEColors.TealContainer,
    onSecondaryContainer = TEColors.TealDark,
    background = TEColors.Faceplate,
    onBackground = TEColors.Ink,
    surface = TEColors.FaceplateLighter,
    onSurface = TEColors.Ink,
    surfaceVariant = TEColors.FaceplateButton,
    onSurfaceVariant = TEColors.InkSecondary,
    outline = TEColors.Border,
    outlineVariant = TEColors.FaceplateDarker,
    inverseSurface = TEColors.PadBlack,
    inverseOnSurface = TEColors.InkOnDark,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val TEDarkColorScheme = darkColorScheme(
    primary = TEColors.OrangeLight,
    onPrimary = TEColors.OrangeDark,
    primaryContainer = TEColors.Orange,
    onPrimaryContainer = Color.White,
    secondary = TEColors.TealLight,
    onSecondary = TEColors.TealDark,
    secondaryContainer = TEColors.Teal,
    onSecondaryContainer = Color.White,
    background = Color(0xFF121314),
    onBackground = TEColors.InkOnDark,
    surface = Color(0xFF1E1F20),
    onSurface = TEColors.InkOnDark,
    surfaceVariant = Color(0xFF2A2B2C),
    onSurfaceVariant = TEColors.InkTertiary,
    outline = Color(0xFF555657),
    outlineVariant = Color(0xFF3A3B3C),
    inverseSurface = TEColors.FaceplateLighter,
    inverseOnSurface = TEColors.Ink,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun EP133Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Android 12+ dynamic color — blend system palette with TE accents
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val dynamic = if (darkTheme) {
                dynamicDarkColorScheme(ctx)
            } else {
                dynamicLightColorScheme(ctx)
            }
            // Override primary with TE orange — the brand must be present
            dynamic.copy(
                primary = TEColors.Orange,
                onPrimary = Color.White,
                secondary = TEColors.Teal,
                onSecondary = Color.White,
            )
        }
        darkTheme -> TEDarkColorScheme
        else -> TELightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EP133Typography,
        content = content,
    )
}
