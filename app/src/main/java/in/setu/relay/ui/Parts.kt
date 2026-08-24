package `in`.setu.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.wire.Status
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure

/**
 * The bridge arc doubles as the relay progress indicator, per docs/07-ui-spec.md.
 * It fills left to right as a message moves HELD -> CARRIED -> DELIVERED.
 *
 * It is a static drawing, redrawn only when the state actually changes. No
 * animation: the relay works with the screen off and the UI must never imply it
 * needs to be awake.
 */
@Composable
fun BridgeArc(
    progress: Float,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    // The deck is navy in the brand, but on the dark theme the card is navy too,
    // so it is drawn in the foreground colour there. The shape is what carries
    // the meaning; the exact hex does not.
    val deck = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width
        val h = size.height
        val deckY = h - 8f
        val stroke = 10f

        val arc = Path().apply {
            moveTo(w * 0.06f, deckY)
            cubicTo(w * 0.2f, deckY - h * 1.15f, w * 0.8f, deckY - h * 1.15f, w * 0.94f, deckY)
        }
        drawPath(arc, track, style = Stroke(width = stroke, cap = StrokeCap.Round))

        val clamped = progress.coerceIn(0f, 1f)
        if (clamped > 0f) {
            val measure = PathMeasure().apply { setPath(arc, false) }
            val filled = Path()
            measure.getSegment(0f, measure.length * clamped, filled, true)
            drawPath(filled, fill, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }

        // The deck. Always drawn: the bridge exists even when nothing crosses it.
        drawLine(
            color = deck,
            start = Offset(w * 0.02f, deckY),
            end = Offset(w * 0.98f, deckY),
            strokeWidth = 8f,
            cap = StrokeCap.Round,
        )
    }
}

/** Progress for the ladder. CARRIED deliberately stops short of the far bank. */
fun statusProgress(status: Int): Float = when (status) {
    Status.HELD -> 0.12f
    Status.CARRIED -> 0.6f
    Status.DELIVERED -> 1f
    else -> 0f
}

/**
 * Composable because the semantic colours differ between light and dark. Amber
 * for CARRIED and green only for DELIVERED is the honesty rule in
 * docs/07-ui-spec.md, so it lives in one place rather than at each call site.
 */
@Composable
fun statusColor(status: Int): Color = when (status) {
    Status.DELIVERED -> Setu.colors.deliveredText
    Status.CARRIED -> Setu.colors.carriedText     // amber, never green
    Status.EXPIRED -> Setu.colors.muted
    else -> Setu.colors.muted
}

fun statusIcon(status: Int): Int = when (status) {
    Status.DELIVERED -> R.drawable.ic_status_delivered
    Status.CARRIED -> R.drawable.ic_status_carried
    Status.EXPIRED -> R.drawable.ic_status_expired
    else -> R.drawable.ic_status_held
}

@Composable
fun statusWords(status: Int, carriers: Int): String = when (status) {
    Status.DELIVERED -> stringResource(R.string.status_delivered)
    Status.CARRIED -> stringResource(R.string.status_carried, carriers)
    Status.EXPIRED -> stringResource(R.string.status_expired)
    else -> stringResource(R.string.status_held)
}

/**
 * A big, unambiguous button. Never colour alone: every one carries an icon and
 * a word, so a non-reader can learn the shape and a colour-blind user is not
 * guessing.
 */
@Composable
fun BigButton(
    label: String,
    iconRes: Int,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = Setu.Touch,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(iconRes), contentDescription = null, tint = content, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, color = content, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SetuCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(Setu.Touch)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Start)
    }
}

@Composable
fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
