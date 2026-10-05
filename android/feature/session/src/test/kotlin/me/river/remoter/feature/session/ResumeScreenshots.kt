package me.river.remoter.feature.session

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.StuckReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val banner = Conversation("c82d8b5c-edd4-453e-8d59-4748ff325c03", "fix the banner", "the banner still overlaps the sheet, can you check why it floats over the list", 0, 0, "main", open = false)
private val rows = persistentListOf(
    PastRow(banner, "yesterday 18:40"),
    PastRow(Conversation("1170e57c-4a8e-4fb2-9437-21f0fdc2cef3", "remoter", null, 0, 0, null, open = true), "today 11:58"),
    PastRow(Conversation("a0b4910a-dc2c-41b3-81c1-b8c9fd626592", "tidy the settings screen", "the danger zone still sits too close to the toggles", 0, 0, "main", open = false), "Monday 09:12"),
    PastRow(Conversation("b0b4910a-dc2c-41b3-81c1-b8c9fd626592", "release signing", null, 0, 0, "main", open = false), "28 Sep"),
)

private fun ui(past: PastState, resume: Conversation? = null, expanded: Boolean = false, handoff: Boolean = false) = StartUi(
    open = true,
    target = StartTarget("Projects/remoter", "remoter", isGit = true),
    form = StartForm(name = if (resume != null) resume.title else "remoter", resume = resume, handoff = handoff),
    hostname = "r1v3r",
    typicalStartS = 6,
    past = past,
    pastExpanded = expanded,
)

internal val resumeStates: List<Pair<String, StartUi>> = listOf(
    "list" to ui(PastState.Loaded(rows)),
    "picked" to ui(PastState.Loaded(rows), resume = banner),
    "expanded" to ui(PastState.Loaded(rows), expanded = true),
    "failed" to ui(PastState.Failed),
    "open_refused" to ui(PastState.Loaded(rows), resume = banner).copy(state = StartState.NotAccepted(AppError.ConversationOpen, null)),
    "handoff_picked" to ui(PastState.Loaded(rows), resume = banner, handoff = true),
    "handoff_open" to ui(PastState.Loaded(rows), resume = rows[1].conversation, handoff = true),
    "handoff_starting" to ui(PastState.Loaded(rows), resume = banner, handoff = true).copy(
        state = StartState.Starting(
            SessionId("rc-01k6b7y3m4n5p6q7r8s9t0v1w2"),
            persistentListOf(
                Step("Fingerprint", true), Step("Accepted by r1v3r", true), Step("Opening the terminal", true),
                Step("Writing the handoff", false), Step("Launching Claude", false), Step("Connecting Remote Control", false),
            ),
            sinceMs = 0, streamReconnecting = false, slow = false,
        ),
        elapsedS = 14,
    ),
    "handoff_failed" to ui(PastState.Loaded(rows), resume = banner, handoff = true).copy(
        state = StartState.Stuck(SessionId("rc-01k6b7y3m4n5p6q7r8s9t0v1w2"), StuckReason.HandoffFailed, persistentListOf("Writing a handoff from the earlier session...", "claude -p exited with 1")),
    ),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ResumeScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun list() = one("list")
    @Test fun picked() = one("picked")
    @Test fun expanded() = one("expanded")
    @Test fun failed() = one("failed")
    @Test fun open_refused() = one("open_refused")
    @Test fun handoff_picked() = one("handoff_picked")
    @Test fun handoff_open() = one("handoff_open")
    @Test fun handoff_starting() = one("handoff_starting")
    @Test fun handoff_failed() = one("handoff_failed")

    private fun one(name: String) {
        val u = resumeStates.first { it.first == name }.second
        compose.shot("start_resume_$name", v) { SheetBody(u) }
    }
}
