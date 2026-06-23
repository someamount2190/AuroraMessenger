package com.aura.di

import com.aura.network.Rendezvous
import com.aura.network.RendezvousClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the production HTTP [RendezvousClient] as the [Rendezvous] control plane. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun bindRendezvous(impl: RendezvousClient): Rendezvous
}
