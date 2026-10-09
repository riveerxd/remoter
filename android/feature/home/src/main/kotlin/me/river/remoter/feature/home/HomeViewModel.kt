package me.river.remoter.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.net.Account
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.HomeSnapshot
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.RecentEntry
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.Resources
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.folderName
import me.river.remoter.feature.session.SessionsHub
import javax.inject.Inject
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// lastUsedMs: when a session last started there, only recent rows know it
data class FolderItem(val path: String, val name: String, val isGit: Boolean, val lastUsedMs: Long? = null)

data class HomeUi(
    val hostname: String = "the laptop",
    val link: Link = Link.Reconnecting,
    val account: Account? = null,
    val battery: Int? = null,
    val onAc: Boolean? = null,
    val lastSeenMs: Long? = null,
    val pinned: ImmutableList<FolderItem> = persistentListOf(),
    val recent: ImmutableList<FolderItem> = persistentListOf(),
    val suggestions: ImmutableList<FolderItem> = persistentListOf(),
    val sessions: ImmutableList<SessionSummary> = persistentListOf(),
    val ending: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val retrying: Boolean = false,
    // bumped per retry that finds the laptop still down, shakes the button
    val stillDown: Int = 0,
    val nowMs: Long = 0,
    val serverFp: String? = null,
    val lastRequestId: String? = null,
    val hidden: Set<String> = emptySet(),
    val undoUnpin: Pair<Int, String>? = null,
    val direct: Boolean = false,
    // null from a laptop too old to send it
    val resources: Resources? = null,
)

private const val RetryHoldMs = 600L

// past the monitor's worst case: a slow probe plus retries at 1, 2 and 4 s
private const val RetryTimeoutMs = 30_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: RemoterApi,
    private val monitor: ConnectionMonitor,
    private val hub: SessionsHub,
    private val store: LocalStore,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUi())
    val ui: StateFlow<HomeUi> = _ui.asStateFlow()

    init {
        // cold start: show the snapshot now, refresh underneath
        store.state.value?.let { st ->
            st.snapshot?.let { snap ->
                hub.seed(snap.sessions)
                _ui.update {
                    it.copy(
                        hostname = snap.hostname, battery = snap.battery, onAc = snap.onAc, direct = snap.direct,
                        recent = snap.recent.map(::item).toImmutableList(), loaded = true,
                    )
                }
            }
            st.laptop?.let { l -> _ui.update { it.copy(hostname = l.hostname, serverFp = l.serverFp) } }
        }
        viewModelScope.launch {
            combine(monitor.link, monitor.account, monitor.health) { l, a, h -> Triple(l, a, h) }.collect { (l, a, h) ->
                _ui.update {
                    it.copy(
                        link = l, account = a,
                        hostname = h?.hostname ?: it.hostname,
                        battery = h?.batteryPct ?: it.battery, onAc = h?.onAc ?: it.onAc,
                        direct = h?.direct ?: it.direct,
                        lastSeenMs = (l as? Link.LaptopDown)?.lastSeenMs ?: it.lastSeenMs,
                        stillDown = if (l is Link.Up) 0 else it.stillDown,
                    )
                }
            }
        }
        viewModelScope.launch {
            monitor.resources.collect { r -> _ui.update { it.copy(resources = r) } }
        }
        // a switch while the app is open has to reach the next cold start, not only the next refresh
        viewModelScope.launch {
            monitor.health.mapNotNull { it?.direct }.distinctUntilChanged().collect { d ->
                store.update { st -> st.snapshot?.takeIf { it.direct != d }?.let { st.copy(snapshot = it.copy(direct = d)) } ?: st }
            }
        }
        viewModelScope.launch {
            store.state.map { it?.pinned.orEmpty() }.distinctUntilChanged().collect { pins ->
                _ui.update { u -> u.copy(pinned = pins.map { p -> u.known(p) }.toImmutableList()) }
            }
        }
        viewModelScope.launch {
            combine(hub.sessions, hub.ending) { s, e -> s to e }.distinctUntilChanged().collect { (s, e) ->
                _ui.update { it.copy(sessions = s.orEmpty().toImmutableList(), ending = e) }
            }
        }
        // folders aren't on the live stream, so they load whenever the link comes up
        viewModelScope.launch {
            monitor.link.map { it is Link.Up }.distinctUntilChanged().collect { up -> if (up) refresh() }
        }
        viewModelScope.launch {
            while (true) {
                _ui.update { it.copy(nowMs = clock.nowMs()) }
                delay(1_000)
            }
        }
    }

    private fun HomeUi.known(path: String): FolderItem =
        (recent + suggestions).firstOrNull { it.path == path } ?: FolderItem(path, folderName(path), isGit = false)

    private fun item(r: RecentEntry) = FolderItem(r.path, r.name, r.isGit, r.lastSpawn)

    // only a pull or a Retry tap gets told when it fails
    fun refresh(manual: Boolean = false) = viewModelScope.launch {
        _ui.update { it.copy(refreshing = true, refreshFailed = false) }
        val recent = runCatching { api.recent().entries }.getOrNull()
        val sessionsOk = hub.refresh().isSuccess
        val suggestions = if (recent != null && recent.isEmpty() && _ui.value.pinned.isEmpty()) firstRunSuggestions() else emptyList()
        val failed = recent == null || !sessionsOk
        _ui.update {
            it.copy(
                refreshing = false,
                loaded = it.loaded || recent != null,
                loadFailed = !it.loaded && recent == null,
                // before the first load the folder list shows the error and its own Retry
                refreshFailed = manual && failed && (it.loaded || recent != null),
                recent = recent?.take(5)?.map(::item)?.toImmutableList() ?: it.recent,
                suggestions = suggestions.toImmutableList(),
            )
        }
        if (recent != null) {
            val u = _ui.value
            // straight from the monitor: on a phone this can run before _ui has caught up with the probe
            val direct = monitor.health.value?.direct ?: u.direct
            store.update { st ->
                st.copy(snapshot = HomeSnapshot(u.hostname, recent.take(5), hub.sessions.value.orEmpty(), u.battery, u.onAc, clock.nowMs(), direct))
            }
        }
    }

    private suspend fun firstRunSuggestions(): List<FolderItem> = runCatching {
        api.list("Projects", hidden = false).entries
            .filter { it.isGit && it.spawnAllowed && !it.unsupported }
            .sortedByDescending { it.mtime }
            .take(3)
            .map { FolderItem("Projects/${it.name}", it.name, true) }
    }.getOrDefault(emptyList())

    fun refreshFailShown() = _ui.update { it.copy(refreshFailed = false) }

    // held for at least RetryHoldMs so a quick answer still reads as a tap that did something
    fun retry() {
        if (_ui.value.retrying) return
        _ui.update { it.copy(retrying = true) }
        monitor.probeNow()
        viewModelScope.launch {
            val held = launch { delay(RetryHoldMs) }
            // monitor goes through Reconnecting while retrying, wait it out
            val settled = withTimeoutOrNull(RetryTimeoutMs) {
                monitor.link.drop(1).first { it != Link.Reconnecting }
            } ?: monitor.link.value
            held.join()
            _ui.update {
                it.copy(retrying = false, stillDown = if (settled is Link.LaptopDown) it.stillDown + 1 else it.stillDown)
            }
        }
    }

    fun pin(path: String) = viewModelScope.launch {
        store.update { st -> if (path in st.pinned) st else st.copy(pinned = st.pinned + path) }
    }

    fun unpin(path: String) = viewModelScope.launch {
        val idx = store.state.value?.pinned?.indexOf(path) ?: -1
        store.update { it.copy(pinned = it.pinned - path) }
        _ui.update { it.copy(undoUnpin = idx to path) }
    }

    fun undoUnpin() = viewModelScope.launch {
        val (idx, path) = _ui.value.undoUnpin ?: return@launch
        store.update { st -> st.copy(pinned = st.pinned.toMutableList().apply { add(idx.coerceIn(0, size), path) }) }
        _ui.update { it.copy(undoUnpin = null) }
    }

    fun undoShown() = _ui.update { it.copy(undoUnpin = null) }

    fun move(from: Int, to: Int) = viewModelScope.launch {
        store.update { st ->
            val l = st.pinned.toMutableList()
            if (from !in l.indices || to !in l.indices) return@update st
            l.add(to, l.removeAt(from))
            st.copy(pinned = l)
        }
    }

    // local only, the laptop reaps exited ones after an hour anyway
    fun clear(id: String) = _ui.update { it.copy(hidden = it.hidden + id) }
}
