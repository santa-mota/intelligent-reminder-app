package com.santamota.reminder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * Dark-first Gemini-style palette built from [AppColors] tokens.
 */
private val Dark = darkColorScheme(
    primary = AppColors.PrimaryBlue,
    onPrimary = AppColors.Ink,
    primaryContainer = AppColors.Slate,
    onPrimaryContainer = AppColors.Mist,
    secondary = AppColors.SoftBlue,
    background = AppColors.Ink,
    onBackground = AppColors.Mist,
    surface = AppColors.NearInk,
    onSurface = AppColors.Mist,
    surfaceVariant = AppColors.Slate,
    onSurfaceVariant = AppColors.Fog,
    outline = AppColors.Steel,
)

private val Light = lightColorScheme()

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun IntelligentReminderTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (forceDark || isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
