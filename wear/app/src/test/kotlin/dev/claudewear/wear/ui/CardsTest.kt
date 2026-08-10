package dev.claudewear.wear.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import dev.claudewear.protocol.AskOption
import dev.claudewear.protocol.AskQuestion
import dev.claudewear.protocol.PermissionRule
import dev.claudewear.protocol.PermissionSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the two cards actually do when you tap them.
 *
 * The screenshots say what they look like; this says what comes out the other side, which is
 * the part the bridge and then the SDK have to be able to use. The answers map in particular
 * has a shape neither side is free to invent: question text as the key, the chosen label as
 * the value, a multiSelect's labels joined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSSmallRound)
class CardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val format = AskQuestion(
        question = "How should I format the output?",
        header = "Format",
        options = listOf(AskOption("Summary", "A few sentences"), AskOption("Full report", null)),
        multiSelect = false,
    )

    private val sections = AskQuestion(
        question = "Which sections?",
        header = "Sections",
        options = listOf(AskOption("Intro", null), AskOption("Findings", null), AskOption("Appendix", null)),
        multiSelect = true,
    )

    /**
     * Scroll the chip into the list, then fire its click action.
     *
     * The action rather than an injected touch: a `ScalingLazyColumn` composes a row slightly
     * before it is fully inside the bezel, and a synthesised tap at the centre of a row that
     * is still half outside it lands nowhere in particular. That a chip is legible and
     * reachable is what the screenshots are for; this test is about what the tap produces.
     */
    private fun tap(text: String) {
        compose.scrollUntilVisible(text)
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun ask(vararg questions: AskQuestion) = PendingRequest.Ask("s_1", "req_1", questions.toList())

    private fun permission(suggestions: List<PermissionSuggestion> = emptyList()) = PendingRequest.Permission(
        sessionId = "s_1",
        requestId = "req_1",
        tool = "Bash",
        display = "rm -rf ./build && npm ci",
        suggestions = suggestions,
    )

    private fun question(
        request: PendingRequest.Ask,
        spoken: String = "",
        onAnswer: (Map<String, String>) -> Unit = {},
        onRespond: (String) -> Unit = {},
    ) = compose.setContent {
        QuestionScreen(
            request = request,
            sessionName = "linter",
            onAnswer = onAnswer,
            onRespond = onRespond,
            // The recognizer, stubbed. The real one only calls back with something worth
            // sending, which is why no screen guards against a blank.
            onDictate = { _, onResult -> if (spoken.isNotEmpty()) onResult(spoken) },
        )
    }

    @Test
    fun oneQuestionAnswersOnTheTap() {
        var answers: Map<String, String>? = null
        question(ask(format), onAnswer = { answers = it })

        tap("Summary")

        // No confirm step: a one-question card that makes you tap Send is a tap that buys
        // nothing, and this product is about answering from a wrist.
        assertEquals(mapOf(format.question to "Summary"), answers)
    }

    @Test
    fun aMultiSelectKeepsPickingAndJoinsTheLabels() {
        var answers: Map<String, String>? = null
        question(ask(sections), onAnswer = { answers = it })

        // Scrolled to each time: a round 227dp screen holds about four rows, and tapping one
        // moves the rest.
        tap("Intro")
        tap("Findings")
        tap("Appendix")
        // Tapping something you already picked can only mean you did not mean it.
        tap("Appendix")
        assertNull("a multiSelect cannot know you have finished picking", answers)

        tap("Send")

        assertEquals(mapOf(sections.question to "Intro, Findings"), answers)
    }

    @Test
    fun dictationIsTheAnswerRatherThanTheWordOther() {
        var answers: Map<String, String>? = null
        question(ask(format), spoken = "as a table, sorted by file", onAnswer = { answers = it })

        tap("Something else…")

        // What you said goes in as the value. Claude Desktop's "Other" works the same way:
        // Claude receives the answer, not the fact that you picked the escape hatch.
        assertEquals(mapOf(format.question to "as a table, sorted by file"), answers)
    }

    @Test
    fun sayingSomethingElseDismissesTheQuestionsEntirely() {
        var response: String? = null
        var answers: Map<String, String>? = null
        question(
            ask(format, sections),
            spoken = "neither — look at the failing test first",
            onAnswer = { answers = it },
            onRespond = { response = it },
        )

        tap("Say something else")

        assertEquals("neither — look at the failing test first", response)
        assertNull("dismissing is not an answer to anything", answers)
    }

    @Test
    fun thePermissionCardShowsTheActualCommand() {
        compose.setContent {
            PermissionScreen(
                request = permission(),
                sessionName = "linter",
                onAllow = {},
                onAlways = {},
                onDeny = {},
                onDictate = { _, _ -> },
            )
        }

        // Verbatim and unshortened. Approving what you cannot see is the failure mode this
        // product designs against, and a small screen is where it goes wrong.
        compose.onNodeWithText("rm -rf ./build && npm ci").assertIsDisplayed()
    }

    @Test
    fun alwaysAllowAppearsOnlyWhenThereIsARuleToWrite() {
        compose.setContent {
            PermissionScreen(
                request = permission(),
                sessionName = "linter",
                onAllow = {},
                onAlways = {},
                onDeny = {},
                onDictate = { _, _ -> },
            )
        }

        // No localSettings suggestion means nothing would be remembered, and a button that
        // claims to remember and does not is worse than no button.
        compose.onNodeWithText("Always allow").assertDoesNotExist()
    }

    @Test
    fun alwaysAllowNamesTheRuleItWouldWrite() {
        var always = false
        compose.setContent {
            PermissionScreen(
                request = permission(
                    listOf(
                        PermissionSuggestion(
                            type = "addRules",
                            behavior = "allow",
                            destination = "localSettings",
                            rules = listOf(PermissionRule("Bash", "npm ci:*")),
                        ),
                    ),
                ),
                sessionName = "linter",
                onAllow = {},
                onAlways = { always = true },
                onDeny = {},
                onDictate = { _, _ -> },
            )
        }

        compose.scrollUntilVisible("npm ci:*")
        tap("Always allow")
        assertEquals(true, always)
    }

    @Test
    fun denyingOffersAReasonClaudeWillSee() {
        var reason: String? = null
        var denied = false
        compose.setContent {
            PermissionScreen(
                request = permission(),
                sessionName = "linter",
                onAllow = {},
                onAlways = {},
                onDeny = {
                    denied = true
                    reason = it
                },
                onDictate = { _, _ -> },
            )
        }

        tap("Deny")
        tap("wrong directory")

        assertEquals(true, denied)
        // The reason is visible to Claude, so the agent adapts instead of retrying blindly.
        assertEquals("wrong directory", reason)
    }
}
