package me.river.remoter

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.river.remoter.core.crypto.Attester
import me.river.remoter.core.crypto.Keys
import me.river.remoter.core.crypto.KeystoreAttester
import me.river.remoter.core.crypto.KeystorePairer
import me.river.remoter.core.crypto.KeystoreSigner
import me.river.remoter.core.crypto.Pairer
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.TlsKeyManager
import me.river.remoter.core.net.AndroidVpnNetworks
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.OkHttpRemoterApi
import me.river.remoter.core.net.PairingClient
import me.river.remoter.core.net.Reachability
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.TcpReachability
import me.river.remoter.core.net.VpnNetworks
import javax.inject.Singleton

/** Release and e2e: hardware keys, the fingerprint prompt, and sockets bound to the VPN network. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    // SigPolicy comes from the build type's own source set: src/release or src/e2e.

    @Provides @Singleton fun androidVpn(@ApplicationContext c: Context) = AndroidVpnNetworks(c)
    @Provides @Singleton fun vpn(v: AndroidVpnNetworks): VpnNetworks = v
    @Provides @Singleton fun keys() = Keys(SigPolicy.sigSpec)

    @Provides @Singleton
    fun okhttp(v: AndroidVpnNetworks, keys: Keys, store: LocalStore, clock: Clock) =
        OkHttpRemoterApi({ v.network }, TlsKeyManager(keys), { store.state.value?.laptop }, clock)

    @Provides @Singleton fun api(o: OkHttpRemoterApi): RemoterApi = o

    @Provides @Singleton
    fun signer(keys: Keys, host: ActivityHost, clock: Clock, store: LocalStore): RequestSigner =
        KeystoreSigner(keys, clock, { store.state.value?.laptop?.deviceId }, SigPolicy.authorizer(host))

    @Provides @Singleton fun pairer(keys: Keys, v: AndroidVpnNetworks): Pairer = KeystorePairer(keys, PairingClient { v.network })
    @Provides @Singleton fun reach(v: AndroidVpnNetworks, store: LocalStore): Reachability =
        TcpReachability({ v.network }, { store.state.value?.laptop?.port ?: 8443 })
    @Provides @Singleton fun attester(keys: Keys, o: OkHttpRemoterApi): Attester = KeystoreAttester(keys, o)
    @Provides @Singleton fun hooks(): IntentHooks = IntentHooks { }
}
