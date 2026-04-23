package de.traewelling.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Träwelling brand colors
val TraewellingRed    = Color(0xFFC72730)
val TraewellingRedDark = Color(0xFFA51F27)
val TraewellingBlue   = Color(0xFF3498db)
val TraewellingDark   = Color(0xFF2c3e50)
val TraewellingSurface = Color(0xFFF5F5F5)

private val LightColorScheme = lightColorScheme(
    primary          = TraewellingRed,
    onPrimary        = Color.White,
    primaryContainer = TraewellingRedDark,
    secondary        = TraewellingBlue,
    onSecondary      = Color.White,
    background       = Color.White,
    surface          = TraewellingSurface,
    onBackground     = TraewellingDark,
    onSurface        = TraewellingDark
)

@Composable
fun TraewellingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography  = Typography(),
        content     = content
    )
}
