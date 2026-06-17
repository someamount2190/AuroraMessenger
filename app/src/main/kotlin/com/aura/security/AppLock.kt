package com.aura.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.crypto.toHex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 8: app lock with a real PIN and a decoy PIN.
 *
 * - The real PIN unlocks the normal app.
 * - The decoy PIN unlocks a "clean" view: the app behaves as if it has no
 *   contacts or messages. (Enforced by [decoyActive] — the data layer filters
 *   to empty while decoy mode is on.)
 * - PINs are stored only as a salted, slow PBKDF2-HMAC-SHA256 hash (per-install
 *   random salt) in EncryptedSharedPreferences — so a short PIN is expensive to
 *   brute-force even if the encrypted store were ever extracted.
 * - Repeated wrong PINs trigger an exponential lockout, throttling on-device
 *   guessing.
 */
@Singleton
class AppLock @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class UnlockResult { REAL, DECOY, WRONG, LOCKED_OUT }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "aura_lock", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _locked = MutableStateFlow(isLockEnabled())
    val locked: StateFlow<Boolean> = _locked

    private val _decoyActive = MutableStateFlow(false)
    /** True when the app was unlocked with the decoy PIN — data layer shows nothing. */
    val decoyActive: StateFlow<Boolean> = _decoyActive

    private val _lockoutUntil = MutableStateFlow(prefs.getLong(KEY_LOCKED_UNTIL, 0L))
    /** Wall-clock ms until which unlock attempts are refused (0 = not locked out). */
    val lockoutUntil: StateFlow<Long> = _lockoutUntil

    fun isLockEnabled(): Boolean = prefs.contains(KEY_REAL_HASH)

    /** Set (or change) the real PIN. */
    fun setPin(pin: String) {
        prefs.edit()
            .putString(KEY_SALT, ensureSalt())
            .putString(KEY_REAL_HASH, hash(pin))
            .apply()
        resetAttempts()
        _locked.value = true
    }

    fun setDecoyPin(pin: String) {
        prefs.edit().putString(KEY_DECOY_HASH, hash(pin)).apply()
    }

    fun disableLock() {
        prefs.edit().remove(KEY_REAL_HASH).remove(KEY_DECOY_HASH).apply()
        resetAttempts()
        _locked.value = false
        _decoyActive.value = false
    }

    fun lock() { if (isLockEnabled()) _locked.value = true }

    fun tryUnlock(pin: String): UnlockResult {
        val now = System.currentTimeMillis()
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        if (now < until) {
            _lockoutUntil.value = until
            return UnlockResult.LOCKED_OUT
        }
        val candidate = hash(pin)
        return when {
            prefs.getString(KEY_REAL_HASH, null) == candidate -> {
                resetAttempts()
                _decoyActive.value = false
                _locked.value = false
                UnlockResult.REAL
            }
            prefs.getString(KEY_DECOY_HASH, null) == candidate -> {
                resetAttempts()
                _decoyActive.value = true
                _locked.value = false
                UnlockResult.DECOY
            }
            else -> {
                registerFailure(now)
                UnlockResult.WRONG
            }
        }
    }

    /** Count a wrong attempt; after [LOCKOUT_THRESHOLD] start an exponential lockout. */
    private fun registerFailure(now: Long) {
        val fails = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAIL_COUNT, fails)
        if (fails >= LOCKOUT_THRESHOLD) {
            val steps = (fails - LOCKOUT_THRESHOLD).coerceAtMost(5)
            val delay = (LOCKOUT_BASE_MS shl steps).coerceAtMost(LOCKOUT_MAX_MS)
            val until = now + delay
            editor.putLong(KEY_LOCKED_UNTIL, until)
            _lockoutUntil.value = until
        }
        editor.apply()
    }

    private fun resetAttempts() {
        prefs.edit().remove(KEY_FAIL_COUNT).remove(KEY_LOCKED_UNTIL).apply()
        _lockoutUntil.value = 0L
    }

    private fun ensureSalt(): String =
        prefs.getString(KEY_SALT, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_SALT, it).apply()
        }

    // Slow, salted PIN hash. PBKDF2-HMAC-SHA256 is built into Android (no extra
    // dependency) and makes brute-forcing a short PIN from a leaked hash expensive —
    // unlike the old single-pass HKDF. Argon2id would be stronger but needs a lib.
    private fun hash(pin: String): String {
        val salt = ensureSalt().toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val KEY_SALT = "salt"
        const val KEY_REAL_HASH = "real_hash"
        const val KEY_DECOY_HASH = "decoy_hash"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val LOCKOUT_THRESHOLD = 5
        const val LOCKOUT_BASE_MS = 30_000L
        const val LOCKOUT_MAX_MS = 15 * 60 * 1000L
        const val PBKDF2_ITERATIONS = 200_000
    }
}
