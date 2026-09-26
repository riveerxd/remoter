package me.river.remoter

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.RemoterJson

private val Context.store by preferencesDataStore("remoter")
private val KEY = stringPreferencesKey("state")

/**
 * One JSON value in DataStore. Nothing here is secret: the keys stay in hardware
 * and terminal output is never written anywhere.
 */
class DataStoreLocalStore(private val context: Context, scope: CoroutineScope) : LocalStore {
    private val s = MutableStateFlow<LocalState?>(null)
    override val state: StateFlow<LocalState?> = s

    init {
        scope.launch {
            context.store.data.collect { prefs ->
                s.value = prefs[KEY]?.let { runCatching { RemoterJson.decodeFromString(LocalState.serializer(), it) }.getOrNull() } ?: LocalState()
            }
        }
    }

    override suspend fun update(f: (LocalState) -> LocalState) {
        context.store.edit { prefs ->
            val cur = prefs[KEY]?.let { runCatching { RemoterJson.decodeFromString(LocalState.serializer(), it) }.getOrNull() } ?: LocalState()
            val next = f(cur)
            prefs[KEY] = RemoterJson.encodeToString(LocalState.serializer(), next)
            s.value = next
        }
    }
}
