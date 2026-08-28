package `in`.setu.relay.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import `in`.setu.relay.R
import java.io.File

/**
 * Hands the app itself to a phone standing next to you, over Bluetooth, with no
 * internet.
 *
 * ### Why this reuses Android's own Bluetooth transfer
 *
 * The obvious build is a custom RFCOMM socket: a server on one phone, a client
 * on the other, chunk the APK across. It is also the wrong build. It needs the
 * two phones paired, needs a discovery UI, needs its own resume and error
 * handling, and every OEM Bluetooth stack breaks it differently — several
 * hundred lines that duplicate something already on every handset.
 *
 * Android's Bluetooth OPP share does all of that: the sender picks a device, the
 * receiver gets an accept prompt, transfer and progress are handled by the
 * system. So this screen does the two things the system cannot do for itself —
 * find this app's own APK, and make the receiving phone discoverable — and hands
 * the rest to `ACTION_SEND`.
 *
 * A 1.2 MB APK over Bluetooth is roughly 10–15 seconds. That number is the
 * argument for the whole size budget in docs/08: a 30 MB app would be minutes of
 * two people standing still holding phones, in a disaster.
 */
@Composable
fun ShareAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val sizeText = remember { apkSizeText(context) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.share_title), onBack)

        Text(stringResource(R.string.share_body), style = MaterialTheme.typography.bodyLarge)

        SetuCard {
            Text(
                stringResource(R.string.share_size, sizeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.share_steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BigButton(
            label = stringResource(R.string.share_send),
            iconRes = R.drawable.ic_relay,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
        ) { failed = !sendApk(context) }

        if (failed) {
            Text(
                stringResource(R.string.share_failed),
                color = Setu.colors.warnText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SetuCard {
            Text(
                stringResource(R.string.share_receive_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BigButton(
            label = stringResource(R.string.share_receive),
            iconRes = R.drawable.ic_carry,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
        ) { makeDiscoverable(context) }
    }
}

/**
 * Shares this app's own installed APK.
 *
 * `sourceDir` is the installed APK, which lives in a directory no other app may
 * read, so it is copied into cache and handed over through a FileProvider — a
 * `file://` URI would throw FileUriExposedException on anything since Android 7.
 */
private fun sendApk(context: Context): Boolean {
    val source = File(context.applicationInfo.sourceDir)
    if (!source.exists()) return false

    val outDir = File(context.cacheDir, "share").apply { mkdirs() }
    val copy = File(outDir, "Setu.apk")
    val copied = runCatching { source.copyTo(copy, overwrite = true) }.isSuccess
    if (!copied) return false

    val uri: Uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
    }.getOrNull() ?: return false

    val send = Intent(Intent.ACTION_SEND).apply {
        // The generic APK type is what the Bluetooth share target listens for.
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Try the system Bluetooth share directly so the user lands on the device
    // picker rather than a chooser full of messaging apps. If this handset names
    // its Bluetooth package differently, fall back to the ordinary chooser.
    val bluetoothDirect = Intent(send).setPackage("com.android.bluetooth")
    if (runCatching { context.startActivity(bluetoothDirect) }.isSuccess) return true

    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser) }.isSuccess
}

/** Two minutes of visibility is enough for a transfer and short enough to be safe. */
private fun makeDiscoverable(context: Context) {
    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun apkSizeText(context: Context): String {
    val bytes = runCatching { File(context.applicationInfo.sourceDir).length() }.getOrDefault(0L)
    return if (bytes <= 0) "—" else String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
}
