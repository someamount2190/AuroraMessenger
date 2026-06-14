package com.aura.di

import android.content.Context
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridSigner
import com.aura.crypto.SymmetricCipher
import com.aura.db.AuroraDatabase
import com.aura.db.ContactDao
import com.aura.db.DbKeyManager
import com.aura.db.MeshPeerDao
import com.aura.db.MessageDao
import com.aura.db.PrekeyDao
import com.aura.db.RatchetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideHkdf(): Hkdf = Hkdf.instance

    @Provides @Singleton
    fun provideHybridKem(hkdf: Hkdf): HybridKem = HybridKem(hkdf)

    @Provides @Singleton
    fun provideHybridSigner(): HybridSigner = HybridSigner()

    @Provides @Singleton
    fun provideSymmetricCipher(): SymmetricCipher = SymmetricCipher()

    @Provides @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        dbKeyManager: DbKeyManager
    ): AuroraDatabase = AuroraDatabase.build(context, dbKeyManager.getOrCreate())

    @Provides
    fun provideContactDao(db: AuroraDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideMessageDao(db: AuroraDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideMeshPeerDao(db: AuroraDatabase): MeshPeerDao = db.meshPeerDao()

    @Provides
    fun provideRatchetDao(db: AuroraDatabase): RatchetDao = db.ratchetDao()

    @Provides
    fun providePrekeyDao(db: AuroraDatabase): PrekeyDao = db.prekeyDao()
}
