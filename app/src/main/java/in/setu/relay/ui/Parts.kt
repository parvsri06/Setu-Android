package `in`.setu.relay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.setu.relay.R
import `in`.setu.relay.wire.Status

/**
 * Shared components, measured off `Setu-docs/design/00b-components.png`.
 *
 * Every number below was sampled from the sheet rather than guessed. The scale
 * anchor is the SOS control: 174 px on an artboard where it is labelled 88 dp,
 * giving 1.977 px/dp, which the two buttons labelled "64 dp" then confirm.
 *
 * | Element | Measured | Used |
 * |---|---|---|
 * | button corner | 11.1–11.6 dp | 12 dp |
 * | card corner | 8.1–8.6 dp | 8 dp |
 * | pill corner | 5.6 dp | 6 dp |
 * | pill height | 38.4–39.4 dp | 40 dp |
 * | card padding | 16.2 dp | 16 dp |
 * | arc stroke | 7.1 dp | 7 dp |
 * | icon | stated on the sheet | 24 dp |
 *
 * Fonts are the platform families. Nothing is bundled: an APK that has to move
 * phone-to-phone over Bluetooth in a disaster cannot afford a typeface, and
 * every device already has a sans and a mono.
 */
private val ButtonRadius = 12.dp
private val CardRadius = 8.dp
private val PillRadius = 6.dp
private val PillHeight = 40.dp
private val CardPadding = 16.dp
private val IconSize = 24.dp
private val ArcStroke = 7.dp
private val ArcHeight = 72.dp
private val HairlineWidth = 1.dp

/**
 * The bridge arc doubles as the relay progress indicator, per docs/07-ui-spec.md.
 * It fills left to right as a message moves HELD -> CARRIED -> DELIVERED.
 *
 * It is a static drawing, redrawn only when the state actually changes. No
 * animation: the relay works with the screen off and the UI must never imply it
 * needs to be awake.
 *
 * The deck line the earlier version drew is gone — the design sheet shows the
 * arc alone, and the unfilled remainder in the outline colour is what reads as
 * "distance still to cross". Stroke width now comes from dp rather than raw
 * canvas pixels, so it is the same physical thickness on every density.
 */
@Composable
fun BridgeArc(
    progress: Float,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(ArcHeight)) {
        val w = size.width
        val h = size.height
        val baseY = h - ArcStroke.toPx()
        val stroke = ArcStroke.toPx()

        val arc = Path().apply {
            moveTo(w * 0.06f, baseY)
            cubicTo(w * 0.2f, baseY - h * 1.15f, w * 0.8f, baseY - h * 1.15f, w * 0.94f, baseY)
        }
        drawPath(arc, track, style = Stroke(width = stroke, cap = StrokeCap.Round))

        val clamped = progress.coerceIn(0f, 1f)
        if (clamped > 0f) {
            val measure = PathMeasure().apply { setPath(arc, false) }
            val filled = Path()
            measure.getSegment(0f, measure.length * clamped, filled, true)
            drawPath(filled, fill, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
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
 *
 * Defaults to the design's 72 dp primary height. Callers that need a row keep
 * passing [Setu.Touch] and SOS keeps passing [Setu.SosTouch], so no screen
 * changes; the label simply grows with the control, which is how the sheet
 * draws SOS larger than the buttons beneath it without a second component.
 */
@Composable
fun BigButton(
    label: String,
    iconRes: Int,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = Setu.Primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(ButtonRadius))
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(IconSize),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = content,
                textAlign = TextAlign.Center,
                style = if (minHeight >= Setu.SosTouch) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.labelLarge
                }.copy(fontFamily = FontFamily.SansSerif),
            )
        }
    }
}

/**
 * A raised panel.
 *
 * Painted with `surfaceVariant`, which is where the design's `surface-raised`
 * token lands — `surface` is the page ground, so a card drawn with it would
 * vanish into the background. The hairline outline is what separates the two in
 * the sheet, since there is no elevation shadow anywhere in this design.
 */
@Composable
fun SetuCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier
            .fillMaxWidth()
            .border(HairlineWidth, MaterialTheme.colorScheme.outline, RoundedCornerShape(CardRadius)),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
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
                    .clip(RoundedCornerShape(ButtonRadius))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "←",
                    style = MaterialTheme.typography.headlineMedium
                        .copy(fontFamily = FontFamily.SansSerif),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium
                .copy(fontFamily = FontFamily.SansSerif),
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * A label on the left, its value on the right.
 *
 * The value is monospace. These are almost always identifiers, counts and
 * timestamps — the things tokens.md reserves monospace for — and a fixed pitch
 * stops a column of key ids and hop counts jittering as the digits change.
 */
@Composable
fun LabelValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium
                .copy(fontFamily = FontFamily.SansSerif),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
                .copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------
// Components the sheet introduces
// ---------------------------------------------------------------------------

/**
 * The small monospace heading above a group — BUTTONS, STATUS PILLS, CARDS & ROWS.
 *
 * Monospace and letter-spaced so it reads as structural furniture rather than
 * as content. It is never the only thing telling a user what a group is.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 1.2.sp,
        ),
    )
}

/**
 * A compact state chip: Draft, Saved on this phone, Carried by another phone.
 *
 * Three forms, and which one a state gets is a meaning decision, not a styling
 * one:
 *
 * - **filled** — the state is a fact the mesh confirmed. Reserved for CARRIED
 *   and DELIVERED, so a filled pill always means something actually happened.
 * - **outlined** — a local, neutral fact: a draft, something waiting to send.
 * - **dashed** — incomplete. Something is missing and the user can still fix it,
 *   which is why it is drawn as an unfinished edge rather than a solid one.
 *
 * @param fill null draws the outlined form
 * @param dashed draws the incomplete form; ignored when [fill] is set
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    textColour: Color? = null,
    dashed: Boolean = false,
) {
    val outline = textColour ?: MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(PillRadius)
    var box = modifier
        .defaultMinSize(minHeight = PillHeight)
        .clip(shape)

    box = when {
        fill != null -> box.background(fill)
        dashed -> box.dashedBorder(outline, PillRadius)
        else -> box
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(HairlineWidth, MaterialTheme.colorScheme.outline, shape)
    }

    Box(
        box.padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                fill != null -> contentColourFor(fill)
                else -> textColour ?: MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * Whether an announcement carried a valid authority signature.
 *
 * Verified is filled and settled; unverified is a dashed outline in the warning
 * colour, which is the point — an unverified announcement is shown rather than
 * hidden, because in a blackout it may still be true, but it must never be able
 * to pass for an official one. See `wire/Announcement.kt`.
 */
@Composable
fun AuthorityBadge(verified: Boolean, modifier: Modifier = Modifier) {
    if (verified) {
        StatusPill(
            text = stringResource(R.string.announce_verified),
            modifier = modifier,
            fill = MaterialTheme.colorScheme.primary,
        )
    } else {
        StatusPill(
            text = stringResource(R.string.announce_unverified),
            modifier = modifier,
            textColour = Setu.colors.warnText,
            dashed = true,
        )
    }
}

/**
 * "Step 3 of 6" with the current section named, over a segmented bar.
 *
 * Segments rather than a continuous track: a survey has discrete steps, and a
 * smooth bar would suggest partial progress within one that the form does not
 * actually measure.
 */
@Composable
fun StepIndicator(
    step: Int,
    total: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.survey_step_of, step, total),
                style = MaterialTheme.typography.bodyLarge
                    .copy(fontFamily = FontFamily.SansSerif),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge
                    .copy(fontFamily = FontFamily.SansSerif),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 1..total) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (i <= step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

/**
 * White or near-black, whichever the fill can carry.
 *
 * Every `on-` token in the design is one or the other, so rather than thread a
 * content colour through every pill this picks the readable one from the fill's
 * luminance — which keeps a caller from accidentally pairing two colours the
 * design never puts together.
 */
private fun contentColourFor(fill: Color): Color =
    if (fill.luminanceApprox() > 0.5f) Color(0xFF15181A) else Color(0xFFFFFFFF)

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The unfinished edge used by the incomplete pill and the unverified badge. */
private fun Modifier.dashedBorder(colour: Color, radius: androidx.compose.ui.unit.Dp): Modifier =
    this.drawBehind {
        drawRoundRect(
            color = colour,
            cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                    0f,
                ),
            ),
        )
    }
