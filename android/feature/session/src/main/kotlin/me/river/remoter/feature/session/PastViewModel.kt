package me.river.remoter.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.PastAnswer
import me.river.remoter.core.net.PastHit
import me.river.remoter.core.net.PastScope
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.folderName
import me.river.remoter.core.net.toAppError
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class PastGroup(val label: String, val hits: ImmutableList<PastHit>)

sealed interface Browse {
    data object Loading : Browse
    data class Failed(val error: AppError) : Browse
    data class Loaded(
        val hits: ImmutableList<PastHit>,
        val searched: Int,
        val more: Boolean,
        val loadingMore: Boolean = false,
        val moreFailed: Boolean = false,
    ) : Browse
}

sealed interface Matches {
    data object Searching : Matches
    data class Failed(val error: AppError) : Matches
    data class Found(val hits: ImmutableList<PastHit>, val more: Boolean) : Matches
}

enum class StepState { Reading, Missed, Found }

data class AskStep(val title: String, val detail: String, val state: StepState)

sealed interface Asking {
    val question: String
    val steps: ImmutableList<AskStep>

    data class Running(override val question: String, override val steps: ImmutableList<AskStep>) : Asking
    data class Answered(
        override val question: String,
        override val steps: ImmutableList<AskStep>,
        val found: PastAnswer?,
        val also: ImmutableList<PastHit>,
    ) : Asking
    data class Failed(override val question: String, override val steps: ImmutableList<AskStep>) : Asking
}

data class PastUi(
    val path: String,
    val hostname: String = "the laptop",
    val scope: PastScope = PastScope.Project,
    val query: String = "",
    val browse: Browse = Browse.Loading,
    // null while the query is empty
    val matches: Matches? = null,
    // over the matches while set, a new query drops it
    val asking: Asking? = null,
    val selected: PastHit? = null,
    val offline: Boolean = false,
    // settings can turn asking off, then it's keywords only
    val canAsk: Boolean = true,
    val nowMs: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val folder get() = folderName(path)
    val terms get() = query.trim().split(' ').filter { it.isNotBlank() }
}

@HiltViewModel(assistedFactory = PastViewModel.Factory::class)
class PastViewModel @AssistedInject constructor(
    @Assisted private val path: String,
    private val api: RemoterApi,
    private val clock: Clock,
    private val monitor: ConnectionMonitor,
    private val store: LocalStore,
) : ViewModel() {
    @AssistedFactory interface Factory {
        fun create(path: String): PastViewModel
    }

    private val _ui = MutableStateFlow(PastUi(path, hostname = host(), nowMs = clock.nowMs()))

    // tests pin it
    var zone: ZoneId
        get() = _ui.value.zone
        set(v) = _ui.update { it.copy(zone = v) }
    val ui: StateFlow<PastUi> = _ui.asStateFlow()

    private var browseJob: Job? = null
    private var findJob: Job? = null
    private var askJob: Job? = null

    private fun host() = monitor.health.value?.hostname ?: store.state.value?.laptop?.hostname ?: "the laptop"

    init {
        browse()
        viewModelScope.launch {
            monitor.link.collect { l ->
                _ui.update { it.copy(offline = l !is Link.Up && l != Link.Reconnecting, hostname = host()) }
            }
        }
        viewModelScope.launch {
            store.state.collect { s ->
                val can = s?.prefs?.askClaude ?: true
                if (!can) askJob?.cancel()
                _ui.update { it.copy(canAsk = can, asking = if (can) it.asking else null) }
            }
        }
    }

    private fun browse() {
        browseJob?.cancel()
        val scope = _ui.value.scope
        _ui.update { it.copy(browse = Browse.Loading, nowMs = clock.nowMs()) }
        browseJob = viewModelScope.launch {
            val next = try {
                val r = api.findPast(path, "", scope)
                Browse.Loaded(r.hits.toImmutableList(), r.searched, r.more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Browse.Failed(e.toAppError(clock.nowMs()))
            }
            _ui.update { if (it.scope == scope) it.copy(browse = next) else it }
        }
    }

    fun retryBrowse() = browse()

    // the list asks for this a few rows before its end
    fun loadMore() {
        val b = _ui.value.browse as? Browse.Loaded ?: return
        if (!b.more || b.loadingMore) return
        val before = b.hits.lastOrNull()?.conversation?.updated ?: return
        val scope = _ui.value.scope
        _ui.update { it.copy(browse = b.copy(loadingMore = true, moreFailed = false)) }
        browseJob = viewModelScope.launch {
            try {
                val r = api.findPast(path, "", scope, before)
                _ui.update {
                    val cur = it.browse as? Browse.Loaded ?: return@update it
                    if (it.scope != scope) return@update it
                    // a session that changed between pages would show twice
                    val seen = cur.hits.map { h -> h.conversation.id }.toSet()
                    it.copy(browse = cur.copy(hits = (cur.hits + r.hits.filter { h -> h.conversation.id !in seen }).toImmutableList(), more = r.more, loadingMore = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { (it.browse as? Browse.Loaded)?.let { cur -> it.copy(browse = cur.copy(loadingMore = false, moreFailed = true)) } ?: it }
            }
        }
    }

    fun setScope(scope: PastScope) {
        if (scope == _ui.value.scope) return
        askJob?.cancel()
        _ui.update { it.copy(scope = scope, asking = null, selected = null) }
        browse()
        if (_ui.value.query.isNotBlank()) find(0)
    }

    // any edit drops an answer, it was about the old words
    fun setQuery(q: String) {
        if (q == _ui.value.query) return
        askJob?.cancel()
        _ui.update { it.copy(query = q, asking = null, selected = null) }
        if (q.isBlank()) {
            findJob?.cancel()
            _ui.update { it.copy(matches = null) }
            return
        }
        find(DEBOUNCE_MS)
    }

    fun retryFind() = find(0)

    // searching shows from the keystroke, so "nothing matches" never flashes before the answer
    private fun find(wait: Long) {
        findJob?.cancel()
        val q = _ui.value.query.trim()
        val scope = _ui.value.scope
        _ui.update { it.copy(matches = Matches.Searching, nowMs = clock.nowMs()) }
        findJob = viewModelScope.launch {
            delay(wait)
            val next = try {
                val r = api.findPast(path, q, scope)
                Matches.Found(r.hits.toImmutableList(), r.more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Matches.Failed(e.toAppError(clock.nowMs()))
            }
            _ui.update { if (it.query.trim() == q && it.scope == scope) it.copy(matches = next) else it }
        }
    }

    // a miss in this folder goes on to every folder by itself, that's where it usually is
    fun ask() {
        val q = _ui.value.query.trim()
        if (q.isEmpty() || !_ui.value.canAsk) return
        askJob?.cancel()
        val host = _ui.value.hostname
        val widen = _ui.value.scope == PastScope.Project
        val everywhere = AskStep("Everything on $host", "Reading every session", StepState.Reading)
        val first = if (widen) AskStep(folderName(path), "Reading its sessions", StepState.Reading) else everywhere
        _ui.update { it.copy(asking = Asking.Running(q, persistentListOf(first)), selected = null) }
        askJob = viewModelScope.launch {
            var steps = listOf(first)
            try {
                val r1 = api.askPast(path, q, if (widen) PastScope.Project else PastScope.All)
                if (r1.found != null || !widen) {
                    steps = listOf(first.settled(r1.found != null, r1.searched, !widen))
                    _ui.update { it.copy(asking = Asking.Answered(q, steps.toImmutableList(), r1.found, r1.also.toImmutableList())) }
                    return@launch
                }
                steps = listOf(first.settled(false, r1.searched, false), everywhere)
                _ui.update { it.copy(asking = Asking.Running(q, steps.toImmutableList())) }
                val r2 = api.askPast(path, q, PastScope.All)
                steps = listOf(steps[0], everywhere.settled(r2.found != null, r2.searched, true))
                val seen = setOfNotNull(r2.found?.hit?.conversation?.id)
                val also = (r2.also + r1.also).filter { it.conversation.id !in seen }.distinctBy { it.conversation.id }.take(ALSO_MAX)
                _ui.update { it.copy(asking = Asking.Answered(q, steps.toImmutableList(), r2.found, also.toImmutableList())) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(asking = Asking.Failed(q, steps.filter { s -> s.state != StepState.Reading }.toImmutableList())) }
            }
        }
    }

    fun clearAsk() {
        askJob?.cancel()
        _ui.update { it.copy(asking = null) }
    }

    fun select(hit: PastHit) = _ui.update {
        it.copy(selected = if (it.selected?.conversation?.id == hit.conversation.id) null else hit)
    }

    fun deselect() = _ui.update { it.copy(selected = null) }

    private fun AskStep.settled(found: Boolean, searched: Int, everywhere: Boolean) = copy(
        state = if (found) StepState.Found else StepState.Missed,
        detail = when {
            found -> "Found it"
            searched == 0 -> "No sessions yet"
            searched == 1 -> "Not in its one session"
            everywhere -> "Nothing fits in $searched sessions"
            else -> "Not in any of its $searched sessions"
        },
    )

    companion object {
        const val DEBOUNCE_MS = 300L
        const val ALSO_MAX = 3
    }
}

fun pastGroups(hits: List<PastHit>, nowMs: Long, zone: ZoneId): ImmutableList<PastGroup> =
    hits.groupBy { dayLabel(it.conversation.updated, nowMs, zone) }
        .map { (label, list) -> PastGroup(label, list.toImmutableList()) }
        .toImmutableList()

/** "Today", "Yesterday", "Monday" within the week, then "3 Oct". */
fun dayLabel(ms: Long, nowMs: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    val label = pastWhen(ms, nowMs, zone)
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        // "Monday 18:40" without the time
        days <= 6L -> label.substringBefore(' ')
        else -> label
    }
}
