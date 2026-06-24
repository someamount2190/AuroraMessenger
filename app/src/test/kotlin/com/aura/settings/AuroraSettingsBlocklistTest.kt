package com.aura.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Blocklist persistence — the pairing gates (`ScannerPairing`/`ReceiverPairing`) refuse a
 * blocked node, so a block that didn't survive a process restart would silently re-admit a
 * removed/abusive contact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuroraSettingsBlocklistTest {

    private fun settings(): AuroraSettings =
        AuroraSettings(ApplicationProvider.getApplicationContext<Context>())

    private val node = "ab".repeat(32)

    @Test fun blockNode_thenIsBlocked_andUnblockReverses() {
        val s = settings()
        assertFalse(s.isBlocked(node))
        s.blockNode(node)
        assertTrue(s.isBlocked(node))
        s.unblockNode(node)
        assertFalse(s.isBlocked(node))
    }

    @Test fun block_survivesNewInstance() {
        settings().blockNode(node)            // write via one instance
        assertTrue(settings().isBlocked(node), "block must persist across instances (process restart)")
    }

    @Test fun clearAll_removesBlocks() {
        val s = settings()
        s.blockNode(node)
        s.clearAll()
        assertFalse(settings().isBlocked(node), "clear-all-data must drop the blocklist too")
    }
}
