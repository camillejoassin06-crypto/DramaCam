package com.jedrama.fork.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Direction artistique : fond encre profonde, accent orange chaud pour les
 * actions principales, or pour les badges. Le STYLE_LOCK des images generees
 * (violet/or) reste immuable : il garantit la coherence des series deja rendues.
 */
val Ink = Color(0xFF0C0A09)
val Orange = Color(0xFFF4490B)
val Gold = Color(0xFFFFC53D)
val Surface = Color(0xFF17120E)
val OnSurfaceMuted = Color(0xFFBDB2A6)

private val scheme = darkColorScheme(
    primary = Orange,
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
fun JeDramaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

