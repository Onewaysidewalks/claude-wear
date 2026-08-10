package dev.claudewear.wear.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.protocol.TurnReason
import dev.claudewear.wear.MainActivity
import dev.claudewear.wear.R
import dev.claudewear.wear.ui.DENY_REASONS
import dev.claudewear.wear.ui.PendingRequest

private const val TAG = "SocketNotifications"

/** Everything a blocked chat posts lives here, away from the connection chip's channel. */
internal const val TURN_CHANNEL_ID = "turns"

/** One card per chat, gathered under a summary rather than overwriting each other. */
internal const val TURN_GROUP = "dev.claudewear.wear.turns"

/** Outside the range [notificationId] produces, and never the connection chip's own id. */
internal const val SUMMARY_ID = 0x6FFFFFFF

/**
 * Stable per chat, so a second question in the same chat updates its card instead of
 * stacking a new one, and so a `resolved` can cancel exactly the card it belongs to.
 */
internal fun notificationId(sessionId: String): Int = 0x70000000 or (sessionId.hashCode() and 0x0FFFFFFF)

/**
 * Buzzes the wrist and posts the card you answer from.
 *
 * One notification per chat, in a group with a summary: three chats waiting on you are
 * three cards you swipe between, not one that overwrites the other two. Every card leads
 * with the chat's name, because "may I run npm test?" is not answerable when you do not
 * know which of your repos is asking.
 *
 * The reply action carries the `sessionId` **and** the `requestId`, so a dictated answer
 * from the shade resolves the specific request it was attached to. Without that, replying
 * with three chats waiting is a coin flip.
 *
 * [lookup] resolves a request id to what the agent is actually blocked on. A turn event is
 * enough to build a card from, but not enough to offer the *options* of a question as
 * tappable chips — and a chip is what makes this answerable without looking.
 */
class SocketNotifications(
    private val context: Context,
    private val lookup: (requestId: String) -> PendingRequest? = { null },
) : NotificationTransport {

    private val notifications = NotificationManagerCompat.from(context)

    /** What each chat's card is currently asking about, so `resolved` can cancel the right one. */
    private val showing = mutableMapOf<String, String?>()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    init {
        createChannel()
    }

    override fun onTurn(event: TurnEvent) {
        val pattern = patternFor(event)
        if (pattern == null) {
            // Working again, closed, or failed: whatever this chat was asking, it is not
            // asking now.
            clear(event.sessionId)
            return
        }
        Log.i(TAG, "turn: ${event.sessionName} — ${event.summary}")
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        post(event)
    }

    override fun onResolved(sessionId: String, requestId: String) {
        if (showing[sessionId] != requestId) return
        clear(sessionId)
    }

    // --- the cards --------------------------------------------------------------

    private fun post(event: TurnEvent) {
        val builder = NotificationCompat.Builder(context, TURN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // The name leads. Everything else on this card is only answerable once you know
            // which chat is asking.
            .setContentTitle(event.sessionName)
            .setContentText(event.summary)
            .setContentIntent(open(event.sessionId, event.requestId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(TURN_GROUP)

        for (action in actionsFor(event)) builder.addAction(action)

        showing[event.sessionId] = event.requestId
        notify(notificationId(event.sessionId), builder)
        notify(SUMMARY_ID, summary())
    }

    /**
     * What you can do without opening the app. A question offers its own options as choices;
     * a permission offers the two answers it has, with reasons behind Deny because a deny
     * message is visible to Claude and an agent that knows why adapts instead of retrying.
     */
    private fun actionsFor(event: TurnEvent): List<NotificationCompat.Action> {
        val requestId = event.requestId
        val request = requestId?.let(lookup)
        return when {
            request is PendingRequest.Ask -> listOf(
                reply(
                    label = "Answer",
                    sessionId = event.sessionId,
                    requestId = requestId,
                    choices = request.questions.firstOrNull()?.options?.map { it.label }.orEmpty(),
                ),
            )

            event.reason == TurnReason.PERMISSION && requestId != null -> listOf(
                action("Allow", event.sessionId, requestId, PermissionDecision.ALLOW),
                reply(
                    label = "Deny",
                    sessionId = event.sessionId,
                    requestId = requestId,
                    choices = DENY_REASONS,
                    decision = PermissionDecision.DENY,
                ),
            )

            // Nothing is blocked: it is simply your turn, so the text is the next prompt.
            requestId == null -> listOf(reply("Reply", event.sessionId, requestId = null))

            // A question whose payload has not landed yet — a turn can beat its own `ask`
            // in. Free text still answers it, and tapping the card opens the real one.
            else -> listOf(reply("Answer", event.sessionId, requestId))
        }
    }

    private fun reply(
        label: String,
        sessionId: String,
        requestId: String?,
        choices: List<String> = emptyList(),
        decision: PermissionDecision? = null,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.ic_notification,
        label,
        Replies.pendingIntent(context, sessionId, requestId, decision),
    )
        .addRemoteInput(Replies.remoteInput(label, choices))
        .build()

    private fun action(
        label: String,
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.ic_notification,
        label,
        Replies.pendingIntent(context, sessionId, requestId, decision),
    ).build()

    private fun summary(): NotificationCompat.Builder = NotificationCompat.Builder(context, TURN_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            if (showing.size == 1) "1 chat needs you" else "${showing.size} chats need you",
        )
        .setGroup(TURN_GROUP)
        .setGroupSummary(true)
        .setAutoCancel(true)

    private fun clear(sessionId: String) {
        // containsKey, not `remove() == null`: an idle chat's card has no requestId, so a
        // null value means "a card with nothing blocked behind it", not "no card".
        if (!showing.containsKey(sessionId)) return
        showing.remove(sessionId)
        notifications.cancel(notificationId(sessionId))
        if (showing.isEmpty()) notifications.cancel(SUMMARY_ID) else notify(SUMMARY_ID, summary())
    }

    /**
     * A declined `POST_NOTIFICATIONS` is survivable and must not take the connection with it:
     * the socket is still up, the list still says who needs you, and the wrist still buzzes.
     * You just lose the card you could have answered from.
     */
    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            Log.i(TAG, "notifications are not permitted; the buzz is all this can do")
            return
        }
        runCatching { notifications.notify(id, builder.build()) }
            .onFailure { Log.w(TAG, "could not post a notification", it) }
    }

    private fun open(sessionId: String, requestId: String?): PendingIntent = PendingIntent.getActivity(
        context,
        "open|$sessionId|$requestId".hashCode(),
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .putExtra(MainActivity.EXTRA_REQUEST_ID, requestId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(
            TURN_CHANNEL_ID,
            "Your turn",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A chat is blocked on you: a question, a permission, or a finished turn."
            // The buzz is ours: a distinct pattern per kind is how two chats wanting you at
            // once feel different, and the channel's own vibration would flatten that.
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * A distinct pattern per event kind, because two chats wanting you at once feel the same
     * otherwise. Kind is all vibration can carry; *which* chat is the notification's job.
     */
    private fun patternFor(event: TurnEvent): LongArray? = when {
        event.state != SessionState.AWAITING && event.state != SessionState.IDLE -> null
        event.reason == TurnReason.ASK -> longArrayOf(0, 60, 80, 60, 80, 60)
        event.reason == TurnReason.PERMISSION -> longArrayOf(0, 200, 120, 200)
        else -> longArrayOf(0, 120)
    }
}
