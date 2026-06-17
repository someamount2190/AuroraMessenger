package com.aura.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the 32-byte SQLCipher database key. Generated once, stored in
 * EncryptedSharedPreferences whose master key lives in the Android Keystore.
 */
@Singleton
class DbKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "aura_dbkey",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun getOrCreate(): ByteArray {
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(key, Base64.NO_WRAP)).commit()
        return key
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object { const val KEY = "db_key" }
}
