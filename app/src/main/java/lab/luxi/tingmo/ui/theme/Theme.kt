package lab.luxi.tingmo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LuxiGreen = Color(0xFF0D5E42)
val LuxiGreenLight = Color(0xFF147A56)
val LuxiCyan = Color(0xFF00D2FF)
val LuxiWhite = Color(0xFFF5F7FA)
val LuxiDark = Color(0xFF121A17)
val LuxiSurfaceDark = Color(0xFF1A2420)

private val LightColors = lightColorScheme(
    primary = LuxiGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EDE3),
    onPrimaryContainer = LuxiDark,
    secondary = LuxiCyan,
    onSecondary = LuxiDark,
    background = LuxiWhite,
    onBackground = LuxiDark,
    surface = Color.White,
    onSurface = LuxiDark,
    surfaceVariant = Color(0xFFE6EEE9),
    outline = Color(0xFF8AA396),
)

private val DarkColors = darkColorScheme(
    primary = LuxiGreenLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3F33),
    onPrimaryContainer = LuxiWhite,
    secondary = LuxiCyan,
    onSecondary = LuxiDark,
    background = LuxiDark,
    onBackground = LuxiWhite,
    surface = LuxiSurfaceDark,
    onSurface = LuxiWhite,
    surfaceVariant = Color(0xFF24302B),
    outline = Color(0xFF6F857A),
)

@Composable
fun TingmoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
