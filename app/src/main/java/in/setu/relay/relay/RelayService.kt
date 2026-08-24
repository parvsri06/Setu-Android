package `in`.setu.relay.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import `in`.setu.relay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The persistent foreground service that keeps the relay alive with the screen
 * off. Type `connectedDevice` per docs/03-relay-algorithm.md.
 *
 * OEM battery managers on Xiaomi, Oppo, Vivo and Samsung kill background
 * services regardless of foreground status. That is the biggest unknown in this
 * build, so every start and every destroy is logged with a restart counter —
 * that log is field-test data.
 */
class RelayService : Service() {

    private lateinit var engine: RelayEngine
    private var notifier: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val starts = prefs.getInt(KEY_STARTS, 0) + 1
        prefs.edit().putInt(KEY_STARTS, starts).apply()
        Log.i(TAG, "RelayService onCreate, lifetime start #$starts")

        createChannel()
        engine = RelayEngine.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification(engine.state.value))
        engine.start()
        engine.publish()
        running = true

        notifier?.cancel()
        notifier = scope.launch {
            engine.state.collectLatest { state ->
                val mgr = getSystemService(NotificationManager::class.java)
                mgr?.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        // START_STICKY asks the platform to bring the service back if it is
        // killed for memory. It does not defend against an OEM battery manager;
        // the first-run screen walks the user to the exemption for that.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "RelayService onDestroy — if this was not user-initiated, an OEM battery manager killed it")
        running = false
        notifier?.cancel()
        engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(state: RelayState): Notification {
        // Resolved through the launcher intent rather than by class reference:
        // dependency direction in docs/01-architecture.md is strictly downward,
        // and `relay` must not import `ui`.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val text = getString(
            R.string.notif_relay_text,
            state.neighbours,
            state.carrying,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_relay_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_setu_bridge)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "SetuService"
        private const val CHANNEL_ID = "setu_relay"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "setu_service"
        private const val KEY_STARTS = "lifetime_starts"

        const val ACTION_STOP = "in.setu.relay.STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RelayService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restartCount(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STARTS, 0)
    }
}
