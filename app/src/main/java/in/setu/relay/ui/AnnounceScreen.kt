package `in`.setu.relay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.Prefs
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.wire.Announcement
import `in`.setu.relay.wire.Codec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Information travelling outward: warnings, routes, distributions, corrections.
 *
 * Everyone sees the list. Only someone holding the authority seed can add to it,
 * and every entry is labelled with whether its signature verified — an
 * unverified announcement is shown, because in a blackout it may still be true,
 * but it never gets to look official.
 */
@Composable
fun AnnounceScreen(engine: RelayEngine, prefs: Prefs, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<RelayEngine.VerifiedAnnouncement>>(emptyList()) }
    var body by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf(prefs.authoritySeedHex) }
    var severity by remember { mutableStateOf(Announcement.Category.GENERAL) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            items = engine.announcements()
            delay(5_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.announce_title), onBack)

        // ------------------------------------------------------------ compose
        if (prefs.rescuerKeyHex.isNotEmpty()) {
            Text(
                stringResource(R.string.announce_compose),
                style = MaterialTheme.typography.titleMedium,
            )
            SetuCard {
                Text(
                    stringResource(R.string.announce_seed_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it; error = null },
                    label = { Text(stringResource(R.string.announce_seed_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it; error = null },
                    label = { Text(stringResource(R.string.announce_body_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in listOf(
                        Announcement.Category.WARNING,
                        Announcement.Category.EVACUATION,
                        Announcement.Category.RELIEF,
                    )) {
                        Text(
                            stringResource(categoryLabel(s)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (severity == s) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Setu.colors.muted
                            },
                            modifier = Modifier.clickableRow { severity = s },
                        )
                    }
                }
                error?.let {
                    Text(it, color = Setu.colors.sosText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            BigButton(
                label = stringResource(R.string.announce_send),
                iconRes = R.drawable.ic_relay,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
            ) {
                val bytes = runCatching { Codec.unhex(seed.trim()) }.getOrNull()
                when {
                    bytes == null || bytes.size != 32 ->
                        error = "The authority seed must be 64 hex characters."

                    body.isBlank() -> error = "Nothing to announce."

                    else -> {
                        engine.publishAnnouncement(
                            authoritySeed = bytes,
                            body = body.trim(),
                            severity = Announcement.Category.let { _ ->
                                if (severity == Announcement.Category.EVACUATION) 2 else 1
                            },
                            category = severity,
                            lat = Double.NaN,
                            lon = Double.NaN,
                            radiusMetres = 0,
                        )
                        prefs.authoritySeedHex = seed.trim()
                        body = ""
                        error = null
                    }
                }
            }
        }

        // --------------------------------------------------------------- list
        Text(
            stringResource(R.string.announce_received, items.size),
            style = MaterialTheme.typography.titleMedium,
        )
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.announce_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (v in items) {
            val a = v.announcement
            SetuCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(categoryLabel(a.category)),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (a.severity >= 2) Setu.colors.sosText else Setu.colors.warnText,
                    )
                    // The single most important word on this screen. An
                    // unverified announcement is exactly how a rumour would
                    // enter the mesh, so it is labelled, not hidden.
                    Text(
                        stringResource(
                            if (v.verified) R.string.announce_verified else R.string.announce_unverified,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (v.verified) Setu.colors.deliveredText else Setu.colors.sosText,
                    )
                }
                Text(a.body, style = MaterialTheme.typography.bodyLarge)
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                Text(
                    SimpleDateFormat("dd MMM HH:mm", locale)
                        .format(Date(a.issuedAt * 1000L)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!v.verified) {
                    Text(
                        stringResource(R.string.announce_unverified_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Setu.colors.sosText,
                    )
                }
            }
        }
    }
}

private fun categoryLabel(c: Int): Int = when (c) {
    Announcement.Category.WARNING -> R.string.announce_cat_warning
    Announcement.Category.EVACUATION -> R.string.announce_cat_evacuation
    Announcement.Category.RELIEF -> R.string.announce_cat_relief
    Announcement.Category.TRANSPORT -> R.string.announce_cat_transport
    Announcement.Category.RUMOUR_CORRECTION -> R.string.announce_cat_correction
    else -> R.string.announce_cat_general
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
