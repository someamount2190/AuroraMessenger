package com.aura.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// Entities and DAOs live in per-aggregate files in this package:
//   Contacts.kt  (ContactEntity, PairState, ContactDao)
//   Messages.kt  (MessageEntity, UnreadByContact, MessageDao)
//   Ratchet.kt   (RatchetStateEntity, RatchetSkippedKeyEntity, RatchetDao)
//   Prekeys.kt   (PrekeyEntity, PrekeyDao)
//   MeshPeers.kt (MeshPeerEntity, MeshPeerDao)

@Database(
    entities = [
        ContactEntity::class, MessageEntity::class, MeshPeerEntity::class,
        RatchetStateEntity::class, RatchetSkippedKeyEntity::class, PrekeyEntity::class
    ],
    version = 7,
    // Export the schema so version 6 became the migration baseline: from here on,
    // changes ship as @AutoMigration / Migration objects that PRESERVE user data
    // instead of wiping it. (Schema JSONs land in app/schemas — commit them.)
    // v6→v7 adds the `prekeys` table (forward-secret PQXDH handshake); a purely
    // additive change, so an automatic migration carries every real user across.
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 6, to = 7)]
)
abstract class AuroraDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun meshPeerDao(): MeshPeerDao
    abstract fun ratchetDao(): RatchetDao
    abstract fun prekeyDao(): PrekeyDao

    companion object {
        /** Build the SQLCipher-encrypted database. [dbKey] must be 32 bytes. */
        fun build(context: Context, dbKey: ByteArray): AuroraDatabase {
            require(dbKey.size == 32) { "DB key must be 32 bytes" }
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AuroraDatabase::class.java, "aura.db")
                .openHelperFactory(SupportOpenHelperFactory(dbKey))
                // Only the pre-export schemas (v1..v5, which have no committed schema to
                // migrate from) are allowed to wipe. From v6 onward every bump MUST ship a
                // migration, so a real user's contacts/messages survive app updates.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                .build()
        }
    }
}
