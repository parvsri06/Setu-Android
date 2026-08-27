package `in`.setu.relay.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.setu.relay.R
import `in`.setu.relay.relay.RelayState

/**
 * Home, restructured to `Setu-docs/design/06a-home-relay-running.png` and
 * `06b-home-relay-stopped.png`.
 *
 * The design is a fixed app bar, a scrolling body grouped under section labels,
 * and a fixed bottom bar. The bar is the reason for the split: relay control,
 * rescue mode and diagnostics stay reachable no matter how far the body has
 * scrolled, and the relay toggle in particular must never be something a user
 * has to go looking for.
 *
 * ### The two states are not a cosmetic difference
 *
 * When the relay is stopped, or Bluetooth or Location are off, the app is not
 * doing the one thing it exists to do. The design says that loudly: the status
 * card takes a warning border, names each specific problem, and offers a button
 * that fixes it. It also, immediately under the SOS control, promises that an
 * SOS still works — because the honest failure here is a user who reads
 * "Bluetooth is off" and concludes the emergency button is dead. It is not; the
 * message is held on the phone until a peer arrives.
 *
 * Measured from the artboards at exactly 2.0 px/dp on a 391 dp canvas: 20 dp
 * page margin, 88 dp SOS, 78 dp app bar, 64 dp bar buttons.
 */
@Composable
fun HomeScreen(
    state: RelayState,
    surveyCount: Int,
    relayWanted: Boolean,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleRelay: () -> Unit,
    onGo: (Screen) -> Unit,
) {
    val context = LocalContext.current
    val radioReady = state.bluetoothOn && state.locationServicesOn
    val healthy = relayWanted && permissionsGranted && radioReady

    Column(Modifier.fillMaxSize()) {

        // ------------------------------------------------------------ app bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = PageMargin, vertical = 16.dp)
                .defaultMinSize(minHeight = 78.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
                    .copy(fontFamily = FontFamily.SansSerif),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // The artboards draw the bar with the title alone, but Settings has
            // to be reachable from somewhere and nothing below the fold is drawn
            // either. Kept here, opposite the title, which is where it was asked
            // for. Flagged rather than silently dropped.
            Box(
                Modifier
                    .size(Setu.Touch)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onGo(Screen.Settings) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // --------------------------------------------------------------- body
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusCard(
                state = state,
                relayWanted = relayWanted,
                permissionsGranted = permissionsGranted,
                healthy = healthy,
                onFix = {
                    // Permissions are the app's to ask for; the Bluetooth and
                    // Location toggles are the system's, so the button hands off
                    // to whichever is actually blocking.
                    when {
                        !permissionsGranted -> onRequestPermissions()
                        !state.bluetoothOn -> openSystem(context, AndroidSettings.ACTION_BLUETOOTH_SETTINGS)
                        else -> openSystem(context, AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)
                    }
                },
            )

            if (state.rescueMode) {
                SetuCard {
                    Text(
                        stringResource(R.string.rescue_active),
                        color = Setu.colors.deliveredText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.rescue_calls, state.sosCalls.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ----------------------------------------------------- emergency
            SectionLabel(stringResource(R.string.home_section_emergency))

            BigButton(
                label = stringResource(R.string.home_sos),
                iconRes = R.drawable.ic_sos,
                container = Setu.colors.sos,
                content = Setu.colors.onSos,
                minHeight = Setu.SosTouch,
            ) { onGo(Screen.Sos) }

            // The most important sentence on the screen when the radio is down.
            if (!healthy) {
                Text(
                    stringResource(R.string.home_sos_still_works),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Setu.colors.warnText,
                )
            }

            BigButton(
                label = stringResource(R.string.home_safe),
                // Primary, never delivered. Green means a receipt came back;
                // this button only *starts* a check-in.
                iconRes = R.drawable.ic_safe,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                minHeight = Setu.SosTouch,
            ) { onGo(Screen.CheckIn) }

            QuietRow(
                label = stringResource(R.string.home_carrying, state.carrying),
                onClick = { onGo(Screen.Carrying) },
            )

            // ------------------------------------------------------- surveys
            SectionLabel(stringResource(R.string.home_section_surveys))
            DestinationCard(
                title = stringResource(R.string.survey_list_title),
                subtitle = stringResource(R.string.home_surveys, surveyCount),
                onClick = { onGo(Screen.Surveys) },
            )

            // ------------------------------------------------- announcements
            SectionLabel(stringResource(R.string.home_section_announcements))
            DestinationCard(
                title = stringResource(R.string.home_announce),
                subtitle = stringResource(R.string.announce_received, state.announcements),
                onClick = { onGo(Screen.Announce) },
            )

            // ---------------------------------------------------------- more
            SectionLabel(stringResource(R.string.home_section_more))
            QuietRow(
                label = stringResource(R.string.home_tools),
                onClick = { onGo(Screen.RescueTools) },
            )
            QuietRow(
                label = stringResource(R.string.home_share),
                onClick = { onGo(Screen.ShareApp) },
            )
            QuietRow(
                label = stringResource(R.string.home_settings),
                onClick = { onGo(Screen.Settings) },
            )
        }

        // --------------------------------------------------------- bottom bar
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = PageMargin, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Stopped is drawn as a secondary button rather than a quiet one:
            // when the relay is off it is the single thing most worth pressing,
            // and the design gives it the only outline in the bar.
            BarButton(
                label = stringResource(if (relayWanted) R.string.home_stop else R.string.home_start),
                emphasised = !relayWanted,
                modifier = Modifier.weight(1f),
                onClick = onToggleRelay,
            )
            BarButton(
                label = stringResource(R.string.home_rescue),
                modifier = Modifier.weight(1f),
            ) { onGo(Screen.Rescue) }
            BarButton(
                label = stringResource(R.string.home_diagnostics),
                modifier = Modifier.weight(1f),
            ) { onGo(Screen.Diagnostics) }
        }
    }
}

// ---------------------------------------------------------------- the card

/**
 * The mesh count, and everything standing in its way.
 *
 * Healthy, it is a plain card with a very large number — the single most
 * reassuring thing the app can show. Unhealthy, it takes the warning border and
 * lists each specific problem in the user's own terms, because "Bluetooth is
 * off" is actionable and "relay unavailable" is not.
 */
@Composable
private fun StatusCard(
    state: RelayState,
    relayWanted: Boolean,
    permissionsGranted: Boolean,
    healthy: Boolean,
    onFix: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (healthy) 1.dp else 2.dp,
                color = if (healthy) MaterialTheme.colorScheme.outline else Setu.colors.carriedText,
                shape = shape,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.neighbours > 0) {
            NearbyCount(state.neighbours)
        } else {
            Text(
                stringResource(R.string.home_nearby_none),
                style = MaterialTheme.typography.headlineMedium
                    .copy(fontFamily = FontFamily.SansSerif),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            stringResource(if (relayWanted) R.string.home_relay_on else R.string.home_relay_off),
            style = MaterialTheme.typography.titleMedium,
            color = if (relayWanted) MaterialTheme.colorScheme.onSurface else Setu.colors.muted,
        )

        val problems = buildList {
            if (!state.bluetoothOn) add(stringResource(R.string.home_bt_off))
            if (!state.locationServicesOn) add(stringResource(R.string.home_location_off))
        }

        if (problems.isNotEmpty() || !permissionsGranted) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            for (p in problems) {
                Text(
                    p,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Setu.colors.warnText,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            BigButton(
                label = stringResource(
                    if (permissionsGranted) R.string.home_fix_radio else R.string.perm_grant,
                ),
                iconRes = R.drawable.ic_relay,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onFix,
            )
        }
    }
}

/**
 * "4 phones nearby" with the number set very large.
 *
 * The count is enlarged inside the existing formatted string rather than by
 * splitting it in two. Word order moves between languages — the number is not
 * always first — so splitting would produce a correct-looking English layout
 * and broken Assamese. This finds the number in the formatted result and styles
 * that span, which works wherever the translator put it.
 */
@Composable
private fun NearbyCount(count: Int) {
    val sentence = stringResource(R.string.home_nearby, count)
    val digits = count.toString()
    val at = sentence.indexOf(digits)

    Text(
        text = if (at < 0) {
            buildAnnotatedString { append(sentence) }
        } else {
            buildAnnotatedString {
                append(sentence.substring(0, at))
                withStyle(SpanStyle(fontSize = 60.sp, fontWeight = FontWeight.Bold)) {
                    append(digits)
                }
                append(sentence.substring(at + digits.length))
            }
        },
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

// ------------------------------------------------------------------- pieces

/** A destination with a name and a count under it. */
@Composable
private fun DestinationCard(title: String, subtitle: String, onClick: () -> Unit) {
    SetuCard(Modifier.clickable(onClick = onClick)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge
                .copy(fontFamily = FontFamily.SansSerif),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A low-key line on the page ground. Still a full 64 dp target — wet fingers. */
@Composable
private fun QuietRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Setu.Touch)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge
                .copy(fontFamily = FontFamily.SansSerif),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One of the three fixed controls along the bottom. */
@Composable
private fun BarButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val edge = if (emphasised) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier
            .defaultMinSize(minHeight = Setu.Touch)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (emphasised) 2.dp else 1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (emphasised) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private val PageMargin = 20.dp

private fun openSystem(context: android.content.Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
