package de.traewelling.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── Brand Colors ────────────────────────────────────────────────────────────

val TraewellingRed       = Color(0xFFC72730)
val TraewellingRedDark   = Color(0xFFA51F27)

// ─── Extended Palette ────────────────────────────────────────────────────────

// Primary: Deep Indigo – for TopAppBar, active accents
val DeepIndigo           = Color(0xFF1A237E)
val IndigoLight          = Color(0xFF534BAE)

// Secondary: Teal – for Start/End badges, progress
val TealAccent           = Color(0xFF00897B)
val TealLight            = Color(0xFFB2DFDB)
val TealDark             = Color(0xFF00695C)

// Amber: for personal markers (Einstieg/Ziel)
val AmberAccent          = Color(0xFFFF8F00)
val AmberLight           = Color(0xFFFFF8E1)
val AmberDark            = Color(0xFFE65100)

// Success: Green – for on-time / early
val SuccessGreen         = Color(0xFF2E7D32)
val SuccessGreenLight    = Color(0xFFE8F5E9)

// Warning: Deep Orange – for delays
val WarningOrange        = Color(0xFFE65100)
val WarningOrangeLight   = Color(0xFFFBE9E7)

// Error: Red – for cancellations
val ErrorRed             = Color(0xFFC62828)
val ErrorRedLight        = Color(0xFFFFEBEE)

// Surface/Background
val SurfaceBlue          = Color(0xFFFAFBFF)
val SurfaceCard          = Color(0xFFFFFFFF)

// ─── Transport-Type Colors ───────────────────────────────────────────────────

object TransportColors {
    val ICE       = Color(0xFF9B1B30)   // Wine red (DB Fernverkehr)
    val IC        = Color(0xFF9B1B30)
    val National  = Color(0xFF9B1B30)
    val RE        = Color(0xFF0064B0)   // DB Blue
    val RB        = Color(0xFF0064B0)
    val Regional  = Color(0xFF0064B0)
    val SBahn     = Color(0xFF408335)   // S-Bahn Green
    val UBahn     = Color(0xFF0054A6)   // U-Bahn Blue
    val Tram      = Color(0xFFCE1417)   // Tram Red
    val Bus       = Color(0xFFA5107F)   // Bus Purple
    val Ferry     = Color(0xFF009FE3)   // Water Blue
    val Default   = Color(0xFF546E7A)   // Blue Grey

    fun forCategory(category: String?): Color = when (category) {
        "nationalExpress" -> ICE
        "national"        -> IC
        "regionalExp"     -> RE
        "regional"        -> Regional
        "suburban"        -> SBahn
        "subway"          -> UBahn
        "tram"            -> Tram
        "bus"             -> Bus
        "ferry"           -> Ferry
        else              -> Default
    }

    fun textForCategory(_category: String?): Color = Color.White
}

// ─── Light Color Scheme ──────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary            = DeepIndigo,
    onPrimary          = Color.White,
    primaryContainer   = IndigoLight,
    onPrimaryContainer = Color.White,
    secondary          = TealAccent,
    onSecondary        = Color.White,
    secondaryContainer = TealLight,
    onSecondaryContainer = TealDark,
    tertiary           = AmberAccent,
    onTertiary         = Color.White,
    tertiaryContainer  = AmberLight,
    onTertiaryContainer = AmberDark,
    error              = ErrorRed,
    onError            = Color.White,
    errorContainer     = ErrorRedLight,
    onErrorContainer   = ErrorRed,
    background         = SurfaceBlue,
    onBackground       = Color(0xFF1C1B1F),
    surface            = SurfaceCard,
    onSurface          = Color(0xFF1C1B1F),
    surfaceVariant     = Color(0xFFE7E0EC),
    onSurfaceVariant   = Color(0xFF49454F)
)

@Composable
fun TraewellingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography  = Typography(),
        content     = content
    )
}
