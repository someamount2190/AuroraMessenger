package com.aura.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T4 security — app lock: real vs decoy PIN routing and the exponential lockout. Uses the
 * internal (prefs, clock) seam so the PBKDF2/lockout logic runs deterministically under
 * Robolectric without the Keystore-backed EncryptedSharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLockTest {

    private var clock = 1_000L
    private var counter = 0
    private fun newLock(): AppLock {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("applock_test_${counter++}", Context.MODE_PRIVATE)
        return AppLock(prefs) { clock }
    }

    @Test fun realPin_unlocksReal_andClearsDecoy() {
        val lock = newLock()
        lock.setPin("1234")
        assertTrue(lock.isLockEnabled())
        assertEquals(AppLock.UnlockResult.REAL, lock.tryUnlock("1234"))
        assertFalse(lock.locked.value)
        assertFalse(lock.decoyActive.value)
    }

    @Test fun decoyPin_unlocksDecoy_andFlagsDecoyActive() {
        val lock = newLock()
        lock.setPin("1234")
        lock.setDecoyPin("9999")
        assertEquals(AppLock.UnlockResult.DECOY, lock.tryUnlock("9999"))
        assertTrue(lock.decoyActive.value, "decoy unlock must flag the data-hiding mode")
        assertFalse(lock.locked.value)
    }

    @Test fun wrongPin_isWrong() {
        val lock = newLock()
        lock.setPin("1234")
        assertEquals(AppLock.UnlockResult.WRONG, lock.tryUnlock("0000"))
    }

    @Test fun repeatedWrongPins_lockOut_evenForCorrectPin_thenClearAfterWindow() {
        clock = 1_000L
        val lock = newLock()
        lock.setPin("1234")
        repeat(5) { assertEquals(AppLock.UnlockResult.WRONG, lock.tryUnlock("0000")) }
        // Within the lockout window the CORRECT pin is still refused.
        assertEquals(AppLock.UnlockResult.LOCKED_OUT, lock.tryUnlock("1234"))
        assertTrue(lock.lockoutUntil.value > clock)
        // Past the window it unlocks again.
        clock = lock.lockoutUntil.value + 1
        assertEquals(AppLock.UnlockResult.REAL, lock.tryUnlock("1234"))
    }

    @Test fun lockoutDelay_growsWithMoreFailures() {
        clock = 0L
        val lock = newLock()
        lock.setPin("1234")
        repeat(5) { lock.tryUnlock("x") }        // first lockout
        val firstUntil = lock.lockoutUntil.value
        clock = firstUntil + 1
        repeat(2) { lock.tryUnlock("x") }        // two more failures past the window
        assertTrue(lock.lockoutUntil.value - clock > firstUntil, "delay must grow with failures")
    }

    @Test fun storedHash_isNotThePlaintextPin() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("applock_hash_test", Context.MODE_PRIVATE)
        AppLock(prefs) { clock }.setPin("1234")
        // No stored value equals the plaintext PIN.
        assertFalse(prefs.all.values.any { it == "1234" }, "PIN must never be stored in clear")
        assertNotEquals("1234", prefs.getString("real_hash", null))
    }

    @Test fun disableLock_clearsEverything() {
        val lock = newLock()
        lock.setPin("1234")
        lock.disableLock()
        assertFalse(lock.isLockEnabled())
        assertFalse(lock.locked.value)
    }
}
