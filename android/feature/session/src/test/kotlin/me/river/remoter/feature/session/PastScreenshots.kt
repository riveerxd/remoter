package me.river.remoter.feature.session

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.PastScope
import me.river.remoter.core.testing.FixtureBackend
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

// Wednesday 14 Oct 2026, 16:30 UTC
private const val NOW = 1_791_995_400_000L
private const val FOLDER = "Projects/remoter"

internal fun pastStates(): List<Pair<String, PastUi>> {
    val api = FixtureBackend(nowMs = { NOW }).apply { askMs = 0 }
    val base = PastUi(FOLDER, hostname = "r1v3r", nowMs = NOW, zone = ZoneOffset.UTC)
    return runBlocking {
        val page = api.findPast(FOLDER, "", PastScope.Project)
        val browse = base.copy(browse = Browse.Loaded(page.hits.toImmutableList(), page.searched, page.more))
        val hits = api.findPast(FOLDER, "banner", PastScope.Project)
        val find = browse.copy(query = "banner", matches = Matches.Found(hits.hits.toImmutableList(), hits.more))
        val q = "the one where we got the relay round trip under 40 ms"
        val miss = api.askPast(FOLDER, q, PastScope.Project)
        val all = api.askPast(FOLDER, q, PastScope.All)
        val missed = AskStep("remoter", "Not in any of its ${miss.searched} sessions", StepState.Missed)
        val asking = browse.copy(
            query = q,
            asking = Asking.Running(q, persistentListOf(missed, AskStep("Everything on r1v3r", "Reading every session", StepState.Reading))),
        )
        val answered = browse.copy(
            query = q,
            asking = Asking.Answered(
                q,
                persistentListOf(missed, AskStep("Everything on r1v3r", "Found it", StepState.Found)),
                all.found, all.also.toImmutableList(),
            ),
        )
        val open = page.hits.first { it.conversation.open }
        listOf(
            "browse" to browse,
            "picked" to browse.copy(selected = page.hits[1]),
            "picked_open" to browse.copy(selected = open),
            "find" to find,
            "find_none" to browse.copy(query = "kubernetes", matches = Matches.Found(persistentListOf(), false)),
            "asking" to asking,
            "answer_elsewhere" to answered,
            "ask_failed" to browse.copy(query = q, asking = Asking.Failed(q, persistentListOf(missed))),
            "loading" to base,
            "error" to base.copy(browse = Browse.Failed(AppError.Unreachable)),
            "empty" to base.copy(browse = Browse.Loaded(persistentListOf(), 0, false)),
        )
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PastScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun browse() = one("browse")
    @Test fun picked() = one("picked")
    @Test fun picked_open() = one("picked_open")
    @Test fun find() = one("find")
    @Test fun find_none() = one("find_none")
    @Test fun asking() = one("asking")
    @Test fun answer_elsewhere() = one("answer_elsewhere")
    @Test fun ask_failed() = one("ask_failed")
    @Test fun loading() = one("loading")
    @Test fun error() = one("error")
    @Test fun empty() = one("empty")

    private fun one(name: String) {
        val u = pastStates().first { it.first == name }.second
        compose.shot("past_$name", v) { PastContent(u, PastCallbacks()) }
    }
}
