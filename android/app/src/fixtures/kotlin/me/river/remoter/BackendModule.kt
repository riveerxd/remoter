package me.river.remoter

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.river.remoter.core.crypto.Pairer
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.Reachability
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.VpnNetworks
import me.river.remoter.core.testing.FakePairer
import me.river.remoter.core.testing.FakeReachability
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import javax.inject.Singleton

/** Debug + benchmark: fixture backend, fake signer. No laptop, no finger. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides @Singleton fun fixture() = FixtureBackend(oneSessionPerFolder = true)
    @Provides @Singleton fun api(f: FixtureBackend): RemoterApi = f
    @Provides @Singleton fun fakeVpn() = FakeVpnNetworks()
    @Provides @Singleton fun vpn(v: FakeVpnNetworks): VpnNetworks = v
    @Provides @Singleton fun signer(clock: Clock): RequestSigner = FakeSigner(clock, fingerMs = if (BuildConfig.BUILD_TYPE.contains("benchmark", ignoreCase = true)) 0 else 600)
    @Provides @Singleton fun fakePairer() = FakePairer()
    @Provides @Singleton fun pairer(p: FakePairer): Pairer = p
    @Provides @Singleton fun reach(): Reachability = FakeReachability()
}

@Module
@InstallIn(SingletonComponent::class)
object HooksModule {
    @Provides @Singleton
    fun hooks(b: FixtureBackend, v: FakeVpnNetworks, store: me.river.remoter.core.net.LocalStore): IntentHooks = IntentHooks { FixtureHooks.apply(it, b, v, store) }
}

@Module
@InstallIn(SingletonComponent::class)
object FixtureAttestModule {
    // no attestation in fixtures, call it fresh for a day
    @Provides @Singleton
    fun attester(): me.river.remoter.core.crypto.Attester = me.river.remoter.core.crypto.Attester { System.currentTimeMillis() + 86_400_000 }
}
