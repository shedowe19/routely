package de.traewelling.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ─── Brand Colors ────────────────────────────────────────────────────────────

val TraewellingRed       = Color(0xFFC72730)
val TraewellingRedDark   = Color(0xFFA51F27)

// ─── Extended Palette ────────────────────────────────────────────────────────

// Primary: Deep Indigo – brand surfaces and active accents
val DeepIndigo           = Color(0xFF241A56)
val IndigoLight          = Color(0xFFB4A6FF)

// Secondary: Teal – start markers, progress, positive motion
val TealAccent           = Color(0xFF2BB3A0)
val TealLight            = Color(0xFFBFF2E8)
val TealDark             = Color(0xFF0B6B5E)

// Amber: destination markers and points
val AmberAccent          = Color(0xFFFFB74D)
val AmberLight           = Color(0xFFFFE0B5)
val AmberDark            = Color(0xFF8A4F00)

// Success: green – on time / early
val SuccessGreen         = Color(0xFF6FE0A6)
val SuccessGreenLight    = Color(0xFFD7F7E5)

// Warning: orange – delays
val WarningOrange        = Color(0xFFFFB37A)
val WarningOrangeLight   = Color(0xFFFFE5D5)

// Error: red – cancellations
val ErrorRed             = Color(0xFFFFB4AB)
val ErrorRedLight        = Color(0xFFFFDAD6)

// Surface/Background (light fallbacks used by legacy screens)
val SurfaceBlue          = Color(0xFFF7F7FC)
val SurfaceCard          = Color(0xFFFFFFFF)

// ─── Success color resolved per theme ───────────────────────────────────────

private val LocalSuccessColor = staticCompositionLocalOf { Color(0xFF1B7F4B) }

val MaterialTheme.successColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalSuccessColor.current

// ─── Transport-Type Colors ───────────────────────────────────────────────────

object TransportColors {
    val ICE       = Color(0xFFA31E39)   // Wine red (DB Fernverkehr)
    val IC        = Color(0xFFA31E39)
    val National  = Color(0xFFA31E39)
    val RE        = Color(0xFF14618F)   // DB Blue
    val RB        = Color(0xFF14618F)
    val Regional  = Color(0xFF14618F)
    val SBahn     = Color(0xFF2F7D32)   // S-Bahn Green
    val UBahn     = Color(0xFF1E5FA8)   // U-Bahn Blue
    val Tram      = Color(0xFFC62828)   // Tram Red
    val Bus       = Color(0xFFA3156E)   // Bus Purple
    val Ferry     = Color(0xFF0077A8)   // Water Blue
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
}

// ─── Typography ──────────────────────────────────────────────────────────────

private val RoutelyTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    )
)

private val RoutelyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

// ─── Light Color Scheme ──────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary              = Color(0xFF4B3BB5),
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFE6E0FF),
    onPrimaryContainer   = Color(0xFF1B1350),
    secondary            = Color(0xFF0F7669),
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFC3F2E9),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary             = Color(0xFF8A4F00),
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFFFDDB6),
    onTertiaryContainer  = Color(0xFF2C1700),
    error                = Color(0xFFB3261E),
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002),
    background           = Color(0xFFF7F7FC),
    onBackground         = Color(0xFF1B1A22),
    surface              = Color(0xFFFFFFFF),
    onSurface            = Color(0xFF1B1A22),
    surfaceVariant       = Color(0xFFE6E2EF),
    onSurfaceVariant     = Color(0xFF5B5768),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFF5F4FA),
    surfaceContainer        = Color(0xFFEFEDF6),
    surfaceContainerHigh    = Color(0xFFE8E5F1),
    surfaceContainerHighest = Color(0xFFE1DEEC),
    outline              = Color(0xFF8B8798),
    outlineVariant       = Color(0xFFD6D2E0),
    inverseSurface       = Color(0xFF302E36),
    inverseOnSurface     = Color(0xFFF3EFF7),
    inversePrimary       = Color(0xFFC3B7FF),
    surfaceTint          = Color(0xFF4B3BB5),
    scrim                = Color.Black
)

// ─── Dark Color Scheme ───────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary              = Color(0xFFB4A6FF),
    onPrimary            = Color(0xFF241A56),
    primaryContainer     = Color(0xFF3A2F73),
    onPrimaryContainer   = Color(0xFFE6E0FF),
    secondary            = Color(0xFF6FD6C2),
    onSecondary          = Color(0xFF003731),
    secondaryContainer   = Color(0xFF0C4A43),
    onSecondaryContainer = Color(0xFFBFF2E8),
    tertiary             = Color(0xFFFFC46B),
    onTertiary           = Color(0xFF3D2A00),
    tertiaryContainer    = Color(0xFF5A3D00),
    onTertiaryContainer  = Color(0xFFFFE0B5),
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005),
    errorContainer       = Color(0xFF93000A),
    onErrorContainer     = Color(0xFFFFDAD6),
    background           = Color(0xFF0B0D13),
    onBackground         = Color(0xFFECEBF2),
    surface              = Color(0xFF0F1118),
    onSurface            = Color(0xFFECEBF2),
    surfaceVariant       = Color(0xFF262A38),
    onSurfaceVariant     = Color(0xFFA9A6B8),
    surfaceContainerLowest  = Color(0xFF07090E),
    surfaceContainerLow     = Color(0xFF12151E),
    surfaceContainer        = Color(0xFF171A24),
    surfaceContainerHigh    = Color(0xFF1B2030),
    surfaceContainerHighest = Color(0xFF202534),
    outline              = Color(0xFF77738A),
    outlineVariant       = Color(0xFF313546),
    inverseSurface       = Color(0xFFE5E1E9),
    inverseOnSurface     = Color(0xFF302E36),
    inversePrimary       = Color(0xFF4B3BB5),
    surfaceTint          = Color(0xFFB4A6FF),
    scrim                = Color.Black
)

// ─── AMOLED Color Scheme ─────────────────────────────────────────────────────

private val AmoledColorScheme = darkColorScheme(
    primary              = Color(0xFFB4A6FF),
    onPrimary            = Color(0xFF241A56),
    primaryContainer     = Color(0xFF352B69),
    onPrimaryContainer   = Color(0xFFE6E0FF),
    secondary            = Color(0xFF6FD6C2),
    onSecondary          = Color(0xFF003731),
    secondaryContainer   = Color(0xFF0C4A43),
    onSecondaryContainer = Color(0xFFBFF2E8),
    tertiary             = Color(0xFFFFC46B),
    onTertiary           = Color(0xFF3D2A00),
    tertiaryContainer    = Color(0xFF5A3D00),
    onTertiaryContainer  = Color(0xFFFFE0B5),
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005),
    errorContainer       = Color(0xFF93000A),
    onErrorContainer     = Color(0xFFFFDAD6),
    background           = Color.Black,
    onBackground         = Color(0xFFECEBF2),
    surface              = Color.Black,
    onSurface            = Color(0xFFECEBF2),
    surfaceVariant       = Color(0xFF1B1F2B),
    onSurfaceVariant     = Color(0xFFA9A6B8),
    surfaceContainerLowest  = Color.Black,
    surfaceContainerLow     = Color(0xFF0A0C12),
    surfaceContainer        = Color(0xFF10131B),
    surfaceContainerHigh    = Color(0xFF161A24),
    surfaceContainerHighest = Color(0xFF1B202D),
    outline              = Color(0xFF77738A),
    outlineVariant       = Color(0xFF2A2E3E),
    inverseSurface       = Color(0xFFE5E1E9),
    inverseOnSurface     = Color(0xFF302E36),
    inversePrimary       = Color(0xFF4B3BB5),
    surfaceTint          = Color(0xFFB4A6FF),
    scrim                = Color.Black
)

@Composable
fun TraewellingTheme(theme: String = "LIGHT", content: @Composable () -> Unit) {
    val colorScheme = when (theme) {
        "DARK" -> DarkColorScheme
        "AMOLED" -> AmoledColorScheme
        else -> LightColorScheme
    }
    val darkTheme = theme == "DARK" || theme == "AMOLED"
    val success = if (darkTheme) Color(0xFF6FE0A6) else Color(0xFF1B7F4B)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalSuccessColor provides success) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = RoutelyTypography,
            shapes      = RoutelyShapes,
            content     = content
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
