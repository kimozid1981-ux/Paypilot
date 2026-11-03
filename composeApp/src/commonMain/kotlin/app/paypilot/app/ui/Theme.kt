package app.paypilot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object PP {
    val Ink = Color(0xFF0F1A17)
    val Ground = Color(0xFFF2F3EF)
    val Lime = Color(0xFFC6F432)
    val LimeSoft = Color(0xFFEAF7C4)
    val LimeText = Color(0xFF2E4A05)
    val Orange = Color(0xFFFFB27A)
    val OrangeSoft = Color(0xFFFFE6D5)
    val OrangeText = Color(0xFF8A3204)
    val Muted = Color(0xFF56615C)
    val MutedOnDark = Color(0xFFB9C4BE)
    val Line = Color(0xFFE3E5DF)
    val CardDark = Color(0xFF1C2A26)
}

private val Light = lightColorScheme(
    primary = PP.Ink, onPrimary = PP.Ground,
    secondary = PP.Lime, onSecondary = PP.Ink,
    background = PP.Ground, onBackground = PP.Ink,
    surface = Color.White, onSurface = PP.Ink,
    surfaceVariant = PP.Ground, onSurfaceVariant = PP.Muted,
    outline = PP.Line, error = PP.OrangeText,
)

private val Dark = darkColorScheme(
    primary = PP.Lime, onPrimary = PP.Ink,
    secondary = PP.Lime, onSecondary = PP.Ink,
    background = PP.Ink, onBackground = PP.Ground,
    surface = PP.CardDark, onSurface = PP.Ground,
    surfaceVariant = PP.CardDark, onSurfaceVariant = PP.MutedOnDark,
    outline = Color(0xFF3A4A44), error = PP.Orange,
)

@Composable
fun PayPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
        ),
        content = content,
    )
}
