package me.river.remoter

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.VpnNetworks
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides @Singleton
    fun clock(): Clock = Clock.System

    @Provides @Singleton
    fun store(@ApplicationContext c: Context, scope: CoroutineScope): LocalStore = DataStoreLocalStore(c, scope)

    @Provides @Singleton
    fun monitor(api: RemoterApi, vpn: VpnNetworks, clock: Clock) = ConnectionMonitor(api, vpn, clock)
}
