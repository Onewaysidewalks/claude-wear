package dev.claudewear.wear.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.wear.service.ConnectionService

/**
 * What a tap or a dictated word from the notification shade means, and how it finds its way
 * back to the one live connection.
 *
 * Every reply names both the chat and the exact request it answers. With three sessions
 * blocked at once, a reply that only names the chat is a coin flip — and the request id is
 * also what makes an answer to a card somebody already dealt with fail cleanly instead of
 * resolving the wrong thing.
 *
 * The intents target [ConnectionService] rather than a receiver because the service is what
 * owns the socket: if the process was reclaimed between the buzz and the reply, starting it
 * is exactly what has to happen anyway, and the reply rides along.
 */
object Replies {

    const val ACTION_REPLY = "dev.claudewear.wear.REPLY"

    const val EXTRA_SESSION_ID = "dev.claudewear.wear.sessionId"
    const val EXTRA_REQUEST_ID = "dev.claudewear.wear.requestId"
    const val EXTRA_DECISION = "dev.claudewear.wear.decision"

    /** The `RemoteInput` result key: dictation, the keyboard, or a tapped canned choice. */
    const val KEY_TEXT = "dev.claudewear.wear.text"

    /**
     * One reply.
     *
     * [requestId] is null for a chat that is merely idle — there is nothing blocked, so the
     * text is a new prompt rather than an answer to anything.
     */
    data class Reply(
        val sessionId: String,
        val requestId: String?,
        val decision: PermissionDecision?,
        val text: String?,
    )

    fun intent(
        context: Context,
        sessionId: String,
        requestId: String?,
        decision: PermissionDecision? = null,
    ): Intent = Intent(context, ConnectionService::class.java)
        .setAction(ACTION_REPLY)
        .putExtra(EXTRA_SESSION_ID, sessionId)
        .putExtra(EXTRA_REQUEST_ID, requestId)
        .putExtra(EXTRA_DECISION, decision?.name)

    /**
     * Mutable, because that is what a `RemoteInput` action requires: the system fills the
     * dictated text into the intent it fires. The intent is explicit — a named component in
     * this app — so nothing outside can aim it somewhere else.
     */
    fun pendingIntent(
        context: Context,
        sessionId: String,
        requestId: String?,
        decision: PermissionDecision? = null,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode(sessionId, requestId, decision),
        intent(context, sessionId, requestId, decision),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Distinct per chat, per request and per button. `FLAG_UPDATE_CURRENT` matches on the
     * request code, so sharing one would quietly hand Allow the extras of the last Deny.
     */
    private fun requestCode(sessionId: String, requestId: String?, decision: PermissionDecision?): Int =
        listOf(sessionId, requestId.orEmpty(), decision?.name.orEmpty()).joinToString("|").hashCode()

    fun remoteInput(label: String, choices: List<String> = emptyList()): RemoteInput =
        RemoteInput.Builder(KEY_TEXT)
            .setLabel(label)
            // Canned choices first, then the mic and the keyboard behind them — Wear renders
            // them as chips, and a tap is cheaper than a sentence when you are walking.
            .setChoices(choices.toTypedArray())
            .setAllowFreeFormInput(true)
            .build()

    /** Null for anything that is not one of ours, so the service can ignore it as noise. */
    fun read(intent: Intent?): Reply? {
        if (intent == null || intent.action != ACTION_REPLY) return null
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return null
        val spoken = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        return Reply(
            sessionId = sessionId,
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID),
            decision = intent.getStringExtra(EXTRA_DECISION)?.let { name ->
                PermissionDecision.entries.find { it.name == name }
            },
            text = spoken?.trim()?.ifEmpty { null },
        )
    }
}
