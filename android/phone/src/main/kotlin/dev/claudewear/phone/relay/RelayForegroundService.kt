package dev.claudewear.phone.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.claudewear.phone.HermesPhoneApp
import dev.claudewear.phone.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the process open while the relay has a turn in flight, then goes away. The notification
 * is the price Android charges for that; it says what is happening and nothing else.
 */
class RelayForegroundService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification("Relaying for the watch"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification("Relaying for the watch"))
        }
        val app = application as HermesPhoneApp
        watcher?.cancel()
        watcher = app.scope.launch {
            app.relay.inFlight.collectLatest { n ->
                if (n == 0) {
                    delay(15_000) // give the watch a moment to send a follow-up before we drop
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watch relay", NotificationManager.IMPORTANCE_MIN))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Hermes")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTIFICATION_ID = 41

        fun ensureRunning(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
