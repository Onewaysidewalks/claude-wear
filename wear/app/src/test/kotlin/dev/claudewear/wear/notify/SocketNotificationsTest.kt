package dev.claudewear.wear.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dev.claudewear.protocol.AskOption
import dev.claudewear.protocol.AskQuestion
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.protocol.TurnReason
import dev.claudewear.wear.MainActivity
import dev.claudewear.wear.ui.PendingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Three chats blocked at once is the case this whole layer exists for.
 *
 * Vibration can only say what kind of thing wants you; it cannot say which chat, and with
 * two sessions waiting that is the only question worth answering. So: one card per chat,
 * grouped, each leading with the chat's name, and a reply that names the exact request it
 * answers — otherwise a dictated word with three cards up is a coin flip.
 */
@RunWith(RobolectricTestRunner::class)
class SocketNotificationsTest {

    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private val requests = mutableMapOf<String, PendingRequest>()
    private lateinit var notifications: SocketNotifications

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Granted, as MainActivity asks for it on first launch. The declined case has a test
        // of its own, because losing the cards must not cost anything else.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = context.getSystemService(NotificationManager::class.java)
        notifications = SocketNotifications(context) { requests[it] }
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun cardFor(sessionId: String): Notification? =
        shadowOf(manager).getNotification(notificationId(sessionId))

    private fun turn(
        sessionId: String,
        name: String,
        reason: TurnReason,
        requestId: String?,
        state: SessionState = SessionState.AWAITING,
    ) = TurnEvent(
        sessionId = sessionId,
        seq = 1,
        state = state,
        reason = reason,
        requestId = requestId,
        sessionName = name,
        summary = "may I run npm test?",
    )

    /** Non-null: every card under test posts the actions it is asserted on. */
    private fun action(notification: Notification, index: Int): NotificationCompat.Action =
        NotificationCompat.getAction(notification, index)!!

    private fun ask(sessionId: String, requestId: String) = PendingRequest.Ask(
        sessionId = sessionId,
        requestId = requestId,
        questions = listOf(
            AskQuestion(
                question = "How should I format the output?",
                header = "Format",
                options = listOf(AskOption("Summary", null), AskOption("Full report", null)),
                multiSelect = false,
            ),
        ),
    )

    @Test
    fun threeWaitingChatsAreThreeCardsAndASummary() {
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))
        notifications.onTurn(turn("s_2", "docs", TurnReason.ASK, "req_2"))
        notifications.onTurn(turn("s_3", "bridge", TurnReason.RESULT, null, state = SessionState.IDLE))

        // Three cards you swipe between, not one that overwrote the other two.
        assertEquals(4, posted().size)
        assertEquals(
            listOf("linter", "docs", "bridge"),
            listOf("s_1", "s_2", "s_3").map { cardFor(it)?.extras?.getString(Notification.EXTRA_TITLE) },
        )
        // The name leads every card, because "may I run npm test?" is not answerable when
        // you do not know which of your repos is asking.
        val summary = shadowOf(manager).getNotification(SUMMARY_ID)
        assertEquals("3 chats need you", summary?.extras?.getString(Notification.EXTRA_TITLE))
        assertTrue(posted().all { it.group == TURN_GROUP })
    }

    @Test
    fun aQuestionOffersItsOwnOptionsInTheShade() {
        requests["req_2"] = ask("s_2", "req_2")
        notifications.onTurn(turn("s_2", "docs", TurnReason.ASK, "req_2"))

        val action = action(cardFor("s_2")!!, 0)
        assertEquals("Answer", action.title)
        // Tappable chips beat a dictated sentence when you are walking, and the option
        // labels are the only chips that can be right.
        val input = action.remoteInputs!!.single()
        assertEquals(Replies.KEY_TEXT, input.resultKey)
        assertEquals(listOf("Summary", "Full report"), input.choices!!.map { it.toString() })
        assertTrue(input.allowFreeFormInput)

        // And it names the exact request, so an answer cannot land on the wrong card.
        val fired = shadowOf(action.actionIntent).savedIntent
        assertEquals(Replies.ACTION_REPLY, fired.action)
        assertEquals("s_2", fired.getStringExtra(Replies.EXTRA_SESSION_ID))
        assertEquals("req_2", fired.getStringExtra(Replies.EXTRA_REQUEST_ID))
        assertNull(fired.getStringExtra(Replies.EXTRA_DECISION))
    }

    @Test
    fun aPermissionOffersAllowAndDenyWithReasons() {
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))
        val card = cardFor("s_1")!!

        val allow = action(card, 0)
        assertEquals("Allow", allow.title)
        assertNull(allow.remoteInputs)
        assertEquals(
            PermissionDecision.ALLOW.name,
            shadowOf(allow.actionIntent).savedIntent.getStringExtra(Replies.EXTRA_DECISION),
        )

        val deny = action(card, 1)
        assertEquals("Deny", deny.title)
        // A deny reason is visible to Claude, so it adapts instead of retrying blindly —
        // which is why it is worth a chip in the shade and not only in the app.
        assertEquals(4, deny.remoteInputs!!.single().choices!!.size)
        assertEquals(
            PermissionDecision.DENY.name,
            shadowOf(deny.actionIntent).savedIntent.getStringExtra(Replies.EXTRA_DECISION),
        )
    }

    @Test
    fun anIdleChatOffersTheNextPromptRatherThanAnAnswer() {
        notifications.onTurn(turn("s_3", "bridge", TurnReason.RESULT, null, state = SessionState.IDLE))

        val action = action(cardFor("s_3")!!, 0)
        assertEquals("Reply", action.title)
        // Nothing is blocked, so there is no request to name and the text is a new prompt.
        assertNull(shadowOf(action.actionIntent).savedIntent.getStringExtra(Replies.EXTRA_REQUEST_ID))
    }

    @Test
    fun tappingTheCardOpensThatChatAndThatRequest() {
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))

        val opened = shadowOf(cardFor("s_1")!!.contentIntent).savedIntent
        assertEquals("s_1", opened.getStringExtra(MainActivity.EXTRA_SESSION_ID))
        assertEquals("req_1", opened.getStringExtra(MainActivity.EXTRA_REQUEST_ID))
    }

    @Test
    fun aResolvedRequestCancelsItsCardAndLeavesTheOthers() {
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))
        notifications.onTurn(turn("s_2", "docs", TurnReason.ASK, "req_2"))

        // Answered from the CLI, a phone, or another watch.
        notifications.onResolved("s_1", "req_1")

        assertNull(cardFor("s_1"))
        assertNotNull(cardFor("s_2"))
        assertEquals("1 chat needs you", shadowOf(manager).getNotification(SUMMARY_ID)?.extras?.getString(Notification.EXTRA_TITLE))

        notifications.onResolved("s_2", "req_2")
        assertEquals(0, posted().size)
    }

    @Test
    fun aStaleResolutionLeavesTheCardTheChatIsActuallyShowing() {
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))
        // The agent moved on to a second question in the same chat before the first
        // resolution came back; cancelling here would take down a live card.
        notifications.onTurn(turn("s_1", "linter", TurnReason.ASK, "req_2"))

        notifications.onResolved("s_1", "req_1")

        assertNotNull(cardFor("s_1"))
    }

    @Test
    fun aDeclinedNotificationPermissionCostsTheCardAndNothingElse() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // No throw, and the wrist still buzzes: the socket, the list and the vibration are
        // all still doing their jobs. You simply lose the thing you could have tapped.
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))

        assertEquals(0, posted().size)
    }

    @Test
    fun aChatThatIsWorkingAgainStopsAsking() {
        notifications.onTurn(turn("s_1", "linter", TurnReason.PERMISSION, "req_1"))
        notifications.onTurn(turn("s_1", "linter", TurnReason.STARTED, null, state = SessionState.WORKING))

        assertNull(cardFor("s_1"))
        assertEquals(0, posted().size)
    }

    @Test
    fun answeringAnIdleChatTakesItsCardDownToo() {
        // An idle card has no blocked request behind it, so it is the one whose bookkeeping
        // is easiest to get wrong — nothing ever resolves it, only the next turn does.
        notifications.onTurn(turn("s_1", "linter", TurnReason.RESULT, null, state = SessionState.IDLE))
        notifications.onTurn(turn("s_1", "linter", TurnReason.STARTED, null, state = SessionState.WORKING))

        assertNull(cardFor("s_1"))
        assertEquals(0, posted().size)
    }
}
