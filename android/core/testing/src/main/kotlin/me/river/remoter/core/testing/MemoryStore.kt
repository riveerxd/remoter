package me.river.remoter.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.LocalStore

class MemoryStore(initial: LocalState? = LocalState()) : LocalStore {
    private val s = MutableStateFlow(initial)
    override val state: StateFlow<LocalState?> = s
    override suspend fun update(f: (LocalState) -> LocalState) {
        s.value = f(s.value ?: LocalState())
    }

    /** For the splash test: the first read hasn't finished yet. */
    fun finishLoading(state: LocalState = LocalState()) {
        s.value = state
    }
}
