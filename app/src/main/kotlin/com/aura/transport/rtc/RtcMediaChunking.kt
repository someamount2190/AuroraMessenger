package com.aura.transport.rtc

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Media chunking + reassembly for the WebRTC data channel.
 *
 * The data channel is a single shared, reliable, ordered SCTP stream, so a media blob is
 * split into small id-tagged "mediachunk" frames that interleave safely with msg/ctl
 * frames (and a second concurrent transfer); the receiver demuxes by id and concatenates
 * in arrival order. Kept transport-pure (JSON + Base64 only, no native WebRTC types) so the
 * framing/reassembly logic is unit-testable without a live PeerConnection.
 *
 * Why small chunks here vs the TCP path's 256 KiB: a single data-channel message must stay
 * under the negotiated SCTP `max-message-size` (256 KiB between two libwebrtc peers). 64 KiB
 * raw -> ~88 KiB base64 JSON frame, comfortably under the cap with headroom for the envelope.
 * The TCP path streams over a byte stream (not discrete messages), so it can use larger chunks.
 */
internal object RtcMediaChunking {
    /** Raw bytes per data-channel media chunk (~88 KiB base64 framed, well under the 256 KiB cap). */
    const val CHUNK_BYTES = 64 * 1024

    /** Number of chunks a [sealedSize]-byte blob splits into at [chunkBytes]. */
    fun chunkCount(sealedSize: Int, chunkBytes: Int = CHUNK_BYTES): Int =
        if (sealedSize <= 0) 0 else (sealedSize + chunkBytes - 1) / chunkBytes

    /** Build one "mediachunk" frame for [data] in [off, end). */
    fun chunkFrame(id: String, index: Int, data: ByteArray, off: Int, end: Int): JSONObject =
        JSONObject()
            .put("t", "mediachunk")
            .put("id", id)
            .put("i", index)
            .put("data", Base64.encodeToString(data.copyOfRange(off, end), Base64.NO_WRAP))
}

/**
 * Reassembles inbound "media"/"mediachunk" frames into the sealed blob, keyed by message id
 * so independent transfers (and interleaved msg/ctl frames) don't corrupt each other.
 *
 * Fed from the data channel's `onMessage` callback in SCTP order, so a given id's chunks
 * arrive after its start frame and in sequence. Accumulates the (still base64) parts cheaply
 * on the network thread; the heavy Base64 decode + concat is deferred to [assemble], called
 * off-thread once the transfer is [Completed]. Total size is capped (over-cap transfers are
 * dropped) to bound the pre-decode memory a peer can force.
 */
internal class RtcMediaReassembler(private val maxBytes: Int) {

    /** A finished transfer: the start-frame metadata plus the ordered base64 parts. */
    class Completed(val meta: JSONObject, val parts: List<String>)

    private class Accum(val meta: JSONObject, val expected: Int) {
        val parts = ArrayList<String>()
        var approxBytes = 0L
    }

    private val accums = ConcurrentHashMap<String, Accum>()

    /** Begin a transfer from a "media" start frame. Returns false if the chunk count is absent/insane. */
    fun begin(meta: JSONObject): Boolean {
        val id = meta.optString("id")
        val chunks = meta.optInt("chunks", -1)
        if (id.isEmpty() || chunks < 1 || chunks > MAX_CHUNKS) return false
        accums[id] = Accum(meta, chunks)
        return true
    }

    /**
     * Feed a "mediachunk" frame. Returns [Completed] on the final chunk (and forgets the id),
     * else null. A chunk for an unknown/over-cap id is dropped.
     */
    fun chunk(frame: JSONObject): Completed? {
        val id = frame.optString("id")
        val accum = accums[id] ?: return null
        val b64 = frame.optString("data")
        accum.parts.add(b64)
        accum.approxBytes += (b64.length.toLong() * 3) / 4   // base64 -> raw size estimate
        if (accum.approxBytes > maxBytes + REASSEMBLY_SLACK) { accums.remove(id); return null }
        if (accum.parts.size < accum.expected) return null
        accums.remove(id)
        return Completed(accum.meta, accum.parts)
    }

    /** Drop all in-flight transfers (called on session disposal). */
    fun clear() = accums.clear()

    companion object {
        // 1024 * 64 KiB = 64 MiB structural ceiling; the real cap is [maxBytes] (the 50 MB store limit).
        private const val MAX_CHUNKS = 1024
        private const val REASSEMBLY_SLACK = 64 * 1024

        /** Decode + concatenate the ordered base64 parts into the sealed blob (heavy; call off-thread). */
        fun assemble(parts: List<String>): ByteArray {
            val buf = ByteArrayOutputStream()
            for (p in parts) buf.write(Base64.decode(p, Base64.NO_WRAP))
            return buf.toByteArray()
        }
    }
}
