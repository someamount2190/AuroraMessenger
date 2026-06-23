package com.aura.di

import com.aura.identity.IdentityProvider
import com.aura.identity.IdentityStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the production [IdentityStore] as the [IdentityProvider] read interface. */
@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {
    @Binds
    @Singleton
    abstract fun bindIdentityProvider(impl: IdentityStore): IdentityProvider
}
