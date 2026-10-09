package app.officehours.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF0F6E56)
private val GreenLight = Color(0xFF8FD8BD)
private val Ink = Color(0xFF14221E)
private val Paper = Color(0xFFF3F5F4)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F0E4),
    onPrimaryContainer = Color(0xFF03301F),
    secondary = Color(0xFF4A635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DC),
    onSecondaryContainer = Color(0xFF062018),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7F0EC),
    onSurfaceVariant = Color(0xFF5C6B66),
    outline = Color(0xFF8A9A94),
    outlineVariant = Color(0xFFD4DEDA),
    error = Color(0xFF9F2D2D),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF0D5440),
    onPrimaryContainer = Color(0xFFB0F1D7),
    secondary = Color(0xFFB1CCC0),
    onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF334B42),
    onSecondaryContainer = Color(0xFFCDE8DC),
    background = Color(0xFF0E1412),
    onBackground = Color(0xFFE0E4E1),
    surface = Color(0xFF161D1A),
    onSurface = Color(0xFFE0E4E1),
    surfaceVariant = Color(0xFF26312D),
    onSurfaceVariant = Color(0xFFB7C4BE),
    outline = Color(0xFF808E89),
    outlineVariant = Color(0xFF3F4A46),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Colours that mean something: on track, getting tight, short, and done. */
@Immutable
data class StatusColors(
    val good: Color,
    val onGood: Color,
    val goodContainer: Color,
    val warn: Color,
    val onWarn: Color,
    val warnContainer: Color,
    val bad: Color,
    val onBad: Color,
    val badContainer: Color,
    val leave: Color,
    val leaveContainer: Color,
    val holiday: Color,
    val holidayContainer: Color,
)

private val LightStatus = StatusColors(
    good = Color(0xFF1B7F5E),
    onGood = Color.White,
    goodContainer = Color(0xFFD6F2E6),
    warn = Color(0xFFB26A00),
    onWarn = Color.White,
    warnContainer = Color(0xFFFFE9C5),
    bad = Color(0xFFB3261E),
    onBad = Color.White,
    badContainer = Color(0xFFFFDAD6),
    leave = Color(0xFF5B4FBF),
    leaveContainer = Color(0xFFE5E1FF),
    holiday = Color(0xFF8A5A00),
    holidayContainer = Color(0xFFFFE6B8),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF7FD9B4),
    onGood = Color(0xFF00382A),
    goodContainer = Color(0xFF1C4A3C),
    warn = Color(0xFFFFC56B),
    onWarn = Color(0xFF442B00),
    warnContainer = Color(0xFF5C4100),
    bad = Color(0xFFFFB4AB),
    onBad = Color(0xFF690005),
    badContainer = Color(0xFF7A2A26),
    leave = Color(0xFFC6BFFF),
    leaveContainer = Color(0xFF3F3480),
    holiday = Color(0xFFFFD58A),
    holidayContainer = Color(0xFF5E4300),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object OfficeStyle {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}

private val OfficeTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun OfficeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(colorScheme = scheme, typography = OfficeTypography, content = content)
    }
}
