package com.aura.di

import android.content.Context
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridSigner
import com.aura.crypto.PrekeyManager
import com.aura.crypto.PrekeyStore
import com.aura.crypto.RatchetManager
import com.aura.crypto.RatchetStore
import com.aura.crypto.SymmetricCipher
import com.aura.db.AuroraDatabase
import com.aura.db.ContactDao
import com.aura.db.DbKeyStore
import com.aura.db.MeshPeerDao
import com.aura.db.MessageDao
import com.aura.db.PrekeyDao
import com.aura.db.RatchetDao
import com.aura.db.RoomPrekeyStore
import com.aura.db.RoomRatchetStore
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
    fun provideHybridKem(): HybridKem = HybridKem()

    @Provides @Singleton
    fun provideHybridSigner(): HybridSigner = HybridSigner()

    @Provides @Singleton
    fun provideSymmetricCipher(): SymmetricCipher = SymmetricCipher()

    @Provides @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        dbKeyManager: DbKeyStore
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

    // ── aura-crypto wiring ──────────────────────────────────────────────────
    // The crypto library is storage- and DI-agnostic: it persists through the
    // RatchetStore / PrekeyStore interfaces it defines, and the managers are plain
    // classes. We back those stores with Room adapters and construct the managers
    // here as singletons (the per-contact locks require a single shared instance).

    @Provides @Singleton
    fun provideRatchetStore(dao: RatchetDao): RatchetStore = RoomRatchetStore(dao)

    @Provides @Singleton
    fun providePrekeyStore(dao: PrekeyDao): PrekeyStore = RoomPrekeyStore(dao)

    @Provides @Singleton
    fun provideRatchetManager(
        store: RatchetStore, hkdf: Hkdf, cipher: SymmetricCipher
    ): RatchetManager = RatchetManager(store, hkdf, cipher)

    @Provides @Singleton
    fun provideKemSessionStore(dao: com.aura.db.RatchetDao): com.aura.crypto.KemSessionStore =
        com.aura.db.RoomKemSessionStore(dao)

    @Provides @Singleton
    fun provideKemRatchetManager(
        store: com.aura.crypto.KemSessionStore, kem: HybridKem, hkdf: Hkdf, cipher: SymmetricCipher
    ): com.aura.crypto.KemRatchetManager = com.aura.crypto.KemRatchetManager(store, kem, hkdf, cipher)

    @Provides @Singleton
    fun providePrekeyManager(
        store: PrekeyStore, kem: HybridKem, signer: HybridSigner
    ): PrekeyManager = PrekeyManager(store, kem, signer)
}
