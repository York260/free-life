package com.freelife.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 色盤設計:深色是主角(像戰情室的 HUD),深海軍藍底 + 青色主色,琥珀色代表小任務與提醒,紅色只留給衝突與逾時。
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD8F0),
    onPrimary = Color(0xFF00323C),
    primaryContainer = Color(0xFF0F4A5A),
    onPrimaryContainer = Color(0xFFBFF3FF),
    secondary = Color(0xFF9DB2FF),
    onSecondary = Color(0xFF0F1D55),
    secondaryContainer = Color(0xFF26346E),
    onSecondaryContainer = Color(0xFFDCE2FF),
    tertiary = Color(0xFFFFC857),
    onTertiary = Color(0xFF3B2A00),
    tertiaryContainer = Color(0xFF4A3A10),
    onTertiaryContainer = Color(0xFFFFE9B0),
    background = Color(0xFF0A1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF111B2E),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1B2942),
    onSurfaceVariant = Color(0xFFA4B4CC),
    outline = Color(0xFF3F5373),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0008),
    errorContainer = Color(0xFF5A1D22),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00718A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8F1FB),
    onPrimaryContainer = Color(0xFF001F27),
    secondary = Color(0xFF4A5DB0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2FF),
    onSecondaryContainer = Color(0xFF00105B),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE3B0),
    onTertiaryContainer = Color(0xFF2B1A00),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0E1726),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1726),
    surfaceVariant = Color(0xFFE3EAF3),
    onSurfaceVariant = Color(0xFF4A5A70),
    outline = Color(0xFF8497B0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun FreeLifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
