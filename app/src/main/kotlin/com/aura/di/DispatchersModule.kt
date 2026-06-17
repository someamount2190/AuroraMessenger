package com.aura.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Injectable coroutine dispatchers. Per the Android coroutines best-practice guide,
 * classes take a [CoroutineDispatcher] rather than hardcoding `Dispatchers.IO`, so tests
 * can substitute a deterministic TestDispatcher and dispatcher choice isn't baked in.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @Singleton @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
