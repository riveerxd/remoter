package me.river.remoter.feature.browser

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.crypto.PromptCopy
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.SignResult
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.net.FsEntry
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.ListResponse
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.MkdirRequest
import me.river.remoter.core.net.Names
import me.river.remoter.core.net.Paths
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SearchHit
import me.river.remoter.core.net.SymlinkKind
import me.river.remoter.core.net.displayPath
import me.river.remoter.core.net.folderName
import me.river.remoter.core.net.toAppError
import me.river.remoter.feature.session.copy

/** Why Start can't go ahead here. The button stays tappable and this explains it. */
enum class Blocked { Denied, Home, Offline, Unsupported }

data class NewFolder(
    val editing: Boolean = false,
    val name: String = "",
    val gitInit: Boolean = false,
    val error: AppError? = null,
    val submitting: Boolean = false,
    val shake: Int = 0,
)

data class BrowserUi(
    val path: String,
    val list: ListResponse? = null,
    val loading: Boolean = true,
    val query: String = "",
    val deeper: ImmutableList<SearchHit> = persistentListOf(),
    val searching: Boolean = false,
    /** The deeper search failed, which is not the same as finding nothing. */
    val searchError: AppError? = null,
    val pinned: Set<String> = emptySet(),
    val newFolder: NewFolder = NewFolder(),
    val offline: Boolean = false,
    val hostname: String = "the laptop",
    val snack: String? = null,
    val snackRetry: Boolean = false,
    /** Where the folder sat in the pinned list, so Undo puts it back in the same spot. */
    val undoUnpin: Pair<Int, String>? = null,
    /** Why the last listing failed. With a list on screen, that list is the old one. */
    val error: AppError? = null,
) {
    val name get() = folderName(path)

    val visible: ImmutableList<FsEntry>
        get() = list?.entries.orEmpty()
            .filter { query.isBlank() || it.name.contains(query.trim().substringAfterLast('/'), ignoreCase = true) }
            .toImmutableList()

    val blocked: Blocked?
        get() = when {
            offline -> Blocked.Offline
            list == null -> null
            list.spawnAllowed -> null
            list.denyReason == DenyReason.Home -> Blocked.Home
            // only an older laptop says this, and its refusal explains
            list.denyReason == DenyReason.Untrusted -> null
            list.denyReason == DenyReason.Unsupported -> Blocked.Unsupported
            else -> Blocked.Denied
        }
}

@HiltViewModel(assistedFactory = BrowserViewModel.Factory::class)
class BrowserViewModel @AssistedInject constructor(
    @Assisted private val path: String,
    private val api: RemoterApi,
    private val signer: RequestSigner,
    private val store: LocalStore,
    private val monitor: ConnectionMonitor,
    private val clock: Clock,
) : ViewModel() {
    @AssistedFactory interface Factory {
        fun create(path: String): BrowserViewModel
    }

    private val _ui = MutableStateFlow(BrowserUi(path))
    val ui: StateFlow<BrowserUi> = _ui.asStateFlow()

    private val _gone = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val gone: SharedFlow<String> = _gone

    private var search: Job? = null

    init {
        load()
        viewModelScope.launch { store.state.map { it?.pinned.orEmpty().toSet() }.collect { p -> _ui.update { it.copy(pinned = p) } } }
        viewModelScope.launch {
            monitor.link.collect { l ->
                _ui.update { it.copy(offline = l !is Link.Up && l != Link.Reconnecting, hostname = monitor.health.value?.hostname ?: it.hostname) }
            }
        }
    }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(loading = true) }
        val hidden = store.state.value?.prefs?.showHidden ?: false
        try {
            val l = api.list(path, hidden)
            _ui.update { it.copy(list = l, loading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = e.toAppError(clock.nowMs())
            if (err == AppError.NotFound) _gone.tryEmit(path)
            // The old list stays and the screen says it's the old one.
            _ui.update { it.copy(loading = false, error = err) }
        }
    }

    /**
     * Local filter at once; after 250 ms idle with 2+ characters, the laptop searches deeper.
     * [BrowserUi.searching] flips on with the keystroke, not after the wait, or "Nothing
     * called" flashes up for those 250 ms on every letter.
     */
    fun setQuery(q: String) {
        search?.cancel()
        if (q.trim().length < 2) {
            _ui.update { it.copy(query = q, deeper = persistentListOf(), searching = false, searchError = null) }
            return
        }
        _ui.update { it.copy(query = q, searching = true, searchError = null) }
        search = viewModelScope.launch {
            delay(SEARCH_IDLE_MS)
            val q2 = q.trim().removePrefix("~/")
            try {
                val hits = api.search(q2, path).hits
                _ui.update { it.copy(deeper = hits.toImmutableList(), searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(deeper = persistentListOf(), searching = false, searchError = e.toAppError(clock.nowMs())) }
            }
        }
    }

    fun retrySearch() = setQuery(_ui.value.query)

    fun togglePin(child: String) = viewModelScope.launch {
        val pinned = store.state.value?.pinned.orEmpty()
        val idx = pinned.indexOf(child)
        if (idx >= 0) {
            store.update { it.copy(pinned = it.pinned - child) }
            _ui.update { it.copy(undoUnpin = idx to child) }
        } else {
            store.update { it.copy(pinned = it.pinned + child) }
        }
    }

    fun undoUnpin() = viewModelScope.launch {
        val (idx, child) = _ui.value.undoUnpin ?: return@launch
        _ui.update { it.copy(undoUnpin = null) }
        store.update { st ->
            if (child in st.pinned) st else st.copy(pinned = st.pinned.toMutableList().apply { add(idx.coerceIn(0, size), child) })
        }
    }

    fun undoShown() = _ui.update { it.copy(undoUnpin = null) }

    fun childPath(name: String) = if (path.isEmpty()) name else "$path/$name"

    fun openNewFolder(prefill: String = "") = _ui.update {
        it.copy(newFolder = NewFolder(editing = true, name = prefill.filter { c -> c.isLetterOrDigit() || c in "._-" }))
    }

    fun cancelNewFolder() = _ui.update { it.copy(newFolder = NewFolder()) }

    fun setNewName(n: String) = _ui.update {
        val nf = it.newFolder
        // A shown error clears the moment the name becomes valid.
        val err = if (nf.error != null && Names.isValidFolderName(n)) null else nf.error
        it.copy(newFolder = nf.copy(name = n, error = err))
    }

    fun toggleGit() = _ui.update { it.copy(newFolder = it.newFolder.copy(gitInit = !it.newFolder.gitInit)) }

    /** Validation waits for submit. The fingerprint prompt names the exact folder. */
    fun submitNewFolder() {
        val nf = _ui.value.newFolder
        if (nf.submitting) return
        if (!Names.isValidFolderName(nf.name)) {
            _ui.update { it.copy(newFolder = nf.copy(error = AppError.Validation(me.river.remoter.core.net.ErrorCode.NameInvalid))) }
            return
        }
        _ui.update { it.copy(newFolder = nf.copy(submitting = true)) }
        viewModelScope.launch {
            val body = RemoterJson.encodeToString(MkdirRequest.serializer(), MkdirRequest(path, nf.name, nf.gitInit)).toByteArray()
            val prompt = PromptCopy("Create ${displayPath(childPath(nf.name))} on ${_ui.value.hostname}")
            when (val r = signer.sign("POST", Paths.mkdir, body, prompt)) {
                is SignResult.Ok -> try {
                    api.mkdir(r.signed)
                    insert(nf.name, nf.gitInit)
                    _ui.update { it.copy(newFolder = NewFolder()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    refused(e.toAppError(clock.nowMs()), nf)
                }
                SignResult.Cancelled -> _ui.update { it.copy(newFolder = nf.copy(submitting = false)) }
                else -> refused(AppError.KeyInvalidated, nf)
            }
        }
    }

    private fun refused(err: AppError, nf: NewFolder) {
        if (err is AppError.Validation) {
            _ui.update { it.copy(newFolder = nf.copy(submitting = false, error = err, shake = nf.shake + 1)) }
        } else {
            _ui.update {
                it.copy(
                    newFolder = nf.copy(submitting = false, shake = nf.shake + 1, editing = false),
                    snack = "Couldn't create '${nf.name}': ${err.copy(it.hostname).title}", snackRetry = true,
                )
            }
        }
    }

    fun retryNewFolder() {
        _ui.update { it.copy(snack = null, snackRetry = false, newFolder = it.newFolder.copy(editing = true)) }
        submitNewFolder()
    }

    fun snackShown() = _ui.update { it.copy(snack = null, snackRetry = false) }

    private fun insert(name: String, git: Boolean) = _ui.update { u ->
        val l = u.list ?: return@update u
        val e = FsEntry(name, clock.nowMs(), git, false, SymlinkKind.None, null, 0, 0, l.trusted, l.spawnAllowed, l.denyReason, false)
        u.copy(list = l.copy(entries = (l.entries + e).sortedWith(compareBy(NaturalOrder) { it.name })))
    }

    companion object {
        const val SEARCH_IDLE_MS = 250L
    }
}

/** `v2` before `v10`, the same order the laptop sorts in. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
