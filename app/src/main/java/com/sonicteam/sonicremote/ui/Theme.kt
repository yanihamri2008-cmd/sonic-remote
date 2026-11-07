package com.sonicteam.sonicremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SonicColors {
    val Background = Color(0xFF0C1019)
    val Surface = Color(0xFF151C2A)
    val SurfaceHigh = Color(0xFF1D2638)
    val Accent = Color(0xFF82D2AA)
    val Silver = Color(0xFFB8C0D0)
    val Muted = Color(0xFF8993A6)
    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC66B)
}

private val SonicScheme = darkColorScheme(
    primary = SonicColors.Accent,
    onPrimary = Color(0xFF06281A),
    secondary = SonicColors.Silver,
    background = SonicColors.Background,
    onBackground = Color(0xFFEAEFF7),
    surface = SonicColors.Surface,
    onSurface = Color(0xFFEAEFF7),
    surfaceVariant = SonicColors.SurfaceHigh,
    onSurfaceVariant = SonicColors.Silver,
    error = SonicColors.Danger,
    outline = Color(0xFF3A465E),
)

@Composable
fun SonicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SonicScheme, content = content)
}
