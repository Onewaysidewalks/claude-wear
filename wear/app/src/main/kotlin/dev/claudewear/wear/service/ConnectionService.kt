package dev.claudewear.wear.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.claudewear.wear.MainActivity
import dev.claudewear.wear.R
import dev.claudewear.wear.data.TokenStore
import dev.claudewear.wear.notify.SocketNotifications
import dev.claudewear.wear.transport.WebSocketTransport
import dev.claudewear.wear.ui.SessionsClient
import dev.claudewear.wear.ui.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "ConnectionService"
private const val CHANNEL_ID = "connection"
private const val NOTIFICATION_ID = 1

/**
 * Holds the WebSocket for as long as you are paired.
 *
 * A watch aggressively sleeps whatever is not in front of you, and an agent that has been
 * blocked on `canUseTool` for twenty minutes is precisely the case where nothing is. So the
 * socket lives in a foreground service with an
 * [Ongoing Activity](https://developer.android.com/training/wearables/ongoing-activity),
 * which is both what keeps it alive through Doze and what makes the cost visible: there is
 * a chip on your watch face saying this app is connected, and you can stop it.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: SessionsClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService, so the promotion has to happen now, before
        // anything below decides there is nothing to do. Opening the app re-starts a
        // service that is already connected, so promote with what it knows rather than
        // flashing the chip back to "connecting…" and leaving it there.
        promote(client?.state?.value ?: WatchState())

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val tokens = TokenStore(this)
        val baseUrl = tokens.baseUrl
        val token = tokens.token
        if (baseUrl == null || token == null) {
            Log.i(TAG, "not paired; nothing to connect to")
            stopSelf()
            return START_NOT_STICKY
        }

        if (client == null) connect(baseUrl, token, tokens.deviceId ?: "unknown")
        // Restarted after the system reclaims the process: being back on the socket without
        // being asked is the whole contract this service signs.
        return START_STICKY
    }

    private fun connect(baseUrl: String, token: String, deviceId: String) {
        val live = SessionsClient(
            transport = WebSocketTransport(baseUrl, token),
            notifications = SocketNotifications(this),
            scope = scope,
            deviceId = deviceId,
            deviceName = Build.MODEL ?: "watch",
        )
        client = live
        ActiveConnection.publish(live)
        live.start()
        scope.launch { live.state.collectLatest { promote(it) } }
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        ActiveConnection.publish(null)
        scope.cancel()
        super.onDestroy()
    }

    // --- the ongoing activity ---------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bridge connection",
            // Low: this chip only says "the socket is up". The buzz that means it is your
            // turn is a different thing, and it should not have to compete with this one.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the watch is holding a connection to your bridge." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promote(state: WatchState) {
        // `dataSync` is the honest type for holding a socket open. Android 14 puts a daily
        // ceiling on it; that is a battery-pass concern for M5, and the first thing to
        // measure there is whether this should be running at all while nothing is blocked.
        startForeground(NOTIFICATION_ID, build(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun build(state: WatchState): Notification {
        val status = summarise(state)
        val touch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(touch)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(touch)
            .setStatus(Status.Builder().addTemplate(status).build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    /** One line, for a chip you read sideways while doing something else. */
    private fun summarise(state: WatchState): String {
        val awaiting = state.awaiting
        return when {
            // Who needs you leads, even over "offline": a blocked agent is the news.
            awaiting.size == 1 -> "${awaiting.single().name} needs you"
            awaiting.size > 1 -> "${awaiting.size} chats need you"
            state.connection == WatchState.Connection.CONNECTING -> "connecting…"
            state.connection == WatchState.Connection.OFFLINE -> "offline — retrying"
            state.sessions.isEmpty() -> "connected, no chats"
            state.sessions.size == 1 -> "watching 1 chat"
            else -> "watching ${state.sessions.size} chats"
        }
    }

    companion object {
        private const val ACTION_STOP = "dev.claudewear.wear.STOP"

        /** Idempotent: a second start on a live service just re-reads the token store. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        /** On unpair. The token is gone, so a socket that stayed up could only fail. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, ConnectionService::class.java).setAction(ACTION_STOP),
            )
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
