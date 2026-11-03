package app.paypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionCard(modifier: Modifier = Modifier, dark: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dark) PP.Ink else MaterialTheme.colorScheme.surface,
            contentColor = if (dark) PP.Ground else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun Pill(text: String, background: Color, foreground: Color) {
    Text(
        text,
        modifier = Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun Amount(text: String, size: Int = 16, color: Color = Color.Unspecified) {
    Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = size.sp, color = color)
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, onDark: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = if (onDark) PP.MutedOnDark else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun RowSpread(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Percent-encodes text for sms:, mailto: and wa.me links. */
fun urlEncode(text: String): String = text.encodeToByteArray().joinToString("") { b ->
    val c = (b.toInt() and 0xff)
    val ch = c.toChar()
    if (c < 128 && (ch.isLetterOrDigit() || ch in "-._~")) ch.toString() else "%" + c.toString(16).uppercase().padStart(2, '0')
}
