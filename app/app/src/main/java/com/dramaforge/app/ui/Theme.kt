package com.dramaforge.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Direction artistique DramaForge : sombre premium, accent violet, or pour les badges. */
val Ink = Color(0xFF0E0B16)
val Violet = Color(0xFF6C2BD9)
val Gold = Color(0xFFFFD54F)
val Surface = Color(0xFF17122A)
val OnSurfaceMuted = Color(0xFFB4ABC9)

private val scheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color.White,
    surface = Surface,
    onSurface = Color.White,
    surfaceVariant = Surface,
    onSurfaceVariant = OnSurfaceMuted,
)

@Composable
fun DramaForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
