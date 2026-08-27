package `in`.setu.relay.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import `in`.setu.relay.R

/**
 * Tells a rescuer that someone is calling for help.
 *
 * A relay that silently accumulates SOS messages is useless to a responder who
 * is not staring at the screen. This is the difference between the app being a
 * transport and the app being usable at the receiving end.
 *
 * Deliberately loud: a separate high-importance channel with sound and
 * vibration, distinct from the quiet, permanent relay-service notification. It
 * exists **only** on a phone holding the rescuer key — an ordinary phone relays
 * the same SOS and stays silent, because it can neither read it nor act on it,
 * and waking every stranger in the mesh for every call would get the app
 * uninstalled.
 */
object RescueAlert {

    const val CHANNEL_ID = "setu_rescue"
    private const val NOTIFICATION_ID_BASE = 9000

    fun raise(context: Context, msgIdHex: String, hopCount: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        createChannel(manager, context)

        val open = Intent(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            msgIdHex.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = context.getString(R.string.rescue_alert_text, hopCount)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(context.getString(R.string.rescue_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        // One notification per message rather than one that replaces the last:
        // three people calling for help must not collapse into a single line.
        runCatching {
            manager.notify(NOTIFICATION_ID_BASE + (msgIdHex.hashCode() and 0xFFF), notification)
        }
    }

    private fun createChannel(manager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.rescue_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.rescue_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }
}
