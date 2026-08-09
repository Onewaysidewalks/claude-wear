package dev.claudewear.wear.notify

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.protocol.TurnReason

private const val TAG = "SocketNotifications"

/**
 * Buzzes the wrist when a turn arrives over the socket.
 *
 * M0 is the vibration only. The grouped, per-session notification with a `RemoteInput`
 * reply action is M3 — that is the "buzz and answer without looking" path and it needs the
 * whole story (one notification id derived from sessionId, the session name leading the
 * card, the requestId in the PendingIntent extras) rather than half of it.
 */
class SocketNotifications(context: Context) : NotificationTransport {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    override fun onTurn(event: TurnEvent) {
        val pattern = patternFor(event) ?: return
        Log.i(TAG, "turn: ${event.sessionName} — ${event.summary}")
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /**
     * A distinct pattern per event kind, because two sessions wanting you at once feel the
     * same otherwise. Kind is all vibration can carry; *which* session is the notification's
     * job, and that is M3.
     */
    private fun patternFor(event: TurnEvent): LongArray? = when {
        event.state != SessionState.AWAITING && event.state != SessionState.IDLE -> null
        event.reason == TurnReason.ASK -> longArrayOf(0, 60, 80, 60, 80, 60)
        event.reason == TurnReason.PERMISSION -> longArrayOf(0, 200, 120, 200)
        else -> longArrayOf(0, 120)
    }
}
