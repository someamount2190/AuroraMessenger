package com.aura.transport.rtc

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framing + reassembly for media over the WebRTC data channel (Robolectric for a real
 * android.util.Base64). Exercises the logic the live data channel can't be unit-tested
 * against: split a blob into id-tagged chunks, reassemble in order, demux concurrent
 * transfers, and drop an over-cap transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RtcMediaChunkingTest {

    private fun blob(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    /** Mirror the sender: a start frame (with chunk count) plus the ordered chunk frames. */
    private fun framesFor(id: String, bytes: ByteArray, chunkBytes: Int): Pair<JSONObject, List<JSONObject>> {
        val start = JSONObject().put("t", "media").put("id", id)
            .put("chunks", RtcMediaChunking.chunkCount(bytes.size, chunkBytes))
        val chunks = ArrayList<JSONObject>()
        var off = 0; var i = 0
        while (off < bytes.size) {
            val end = minOf(off + chunkBytes, bytes.size)
            chunks.add(RtcMediaChunking.chunkFrame(id, i, bytes, off, end))
            off = end; i++
        }
        return start to chunks
    }

    @Test fun chunkCount_math() {
        assertEquals(0, RtcMediaChunking.chunkCount(0, 1024))
        assertEquals(1, RtcMediaChunking.chunkCount(1, 1024))
        assertEquals(1, RtcMediaChunking.chunkCount(1024, 1024))
        assertEquals(2, RtcMediaChunking.chunkCount(1025, 1024))
        assertEquals(3, RtcMediaChunking.chunkCount(3000, 1024))
    }

    @Test fun roundTrips_acrossSizes() {
        val chunkBytes = 1024
        for (size in intArrayOf(1, 1023, 1024, 1025, 3072, 3077, 50_000)) {
            val bytes = blob(size)
            val r = RtcMediaReassembler(10 * 1024 * 1024)
            val (start, chunks) = framesFor("id-$size", bytes, chunkBytes)
            assertTrue(r.begin(start), "begin should accept size=$size")

            var completed: RtcMediaReassembler.Completed? = null
            chunks.forEachIndexed { idx, c ->
                val done = r.chunk(c)
                if (idx < chunks.size - 1) assertNull(done, "completed early at size=$size") else completed = done
            }
            assertNotNull(completed, "never completed at size=$size")
            assertContentEquals(bytes, RtcMediaReassembler.assemble(completed!!.parts), "bad bytes at size=$size")
            assertEquals("id-$size", completed!!.meta.getString("id"))
        }
    }

    @Test fun demuxes_concurrentTransfers() {
        val r = RtcMediaReassembler(10 * 1024 * 1024)
        val a = blob(2500); val b = blob(1500)
        val (sa, ca) = framesFor("A", a, 1024)
        val (sb, cb) = framesFor("B", b, 1024)
        assertTrue(r.begin(sa)); assertTrue(r.begin(sb))

        val results = HashMap<String, ByteArray>()
        val max = maxOf(ca.size, cb.size)
        for (i in 0 until max) {   // interleave A and B chunks
            if (i < ca.size) r.chunk(ca[i])?.let { results[it.meta.getString("id")] = RtcMediaReassembler.assemble(it.parts) }
            if (i < cb.size) r.chunk(cb[i])?.let { results[it.meta.getString("id")] = RtcMediaReassembler.assemble(it.parts) }
        }
        assertContentEquals(a, results["A"])
        assertContentEquals(b, results["B"])
    }

    @Test fun dropsOverCapTransfer() {
        val r = RtcMediaReassembler(maxBytes = 1024)   // tiny cap; slack is 64 KiB, so exceed both
        val (start, chunks) = framesFor("big", blob(140_000), 4096)
        assertTrue(r.begin(start))
        var done: RtcMediaReassembler.Completed? = null
        for (c in chunks) r.chunk(c)?.let { done = it }
        assertNull(done, "an over-cap transfer must never complete")
    }

    @Test fun begin_rejectsBadChunkCounts() {
        val r = RtcMediaReassembler(10 * 1024 * 1024)
        assertFalse(r.begin(JSONObject().put("id", "x").put("chunks", 0)))
        assertFalse(r.begin(JSONObject().put("id", "x")))            // missing chunks
        assertFalse(r.begin(JSONObject().put("id", "").put("chunks", 3)))  // missing id
    }

    @Test fun chunkForUnknownId_isDropped() {
        val r = RtcMediaReassembler(10 * 1024 * 1024)
        val orphan = RtcMediaChunking.chunkFrame("never-began", 0, blob(100), 0, 100)
        assertNull(r.chunk(orphan))
    }
}
