package com.aura.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

/**
 * Phase 4 wire protocol: length-prefixed UTF-8 JSON frames over a direct TCP
 * socket. [4-byte big-endian length][json bytes]. Frame types:
 *
 *  msg     { t:"msg", from, to, id, ts, sealed }   sealed = b64(XChaCha20(json{body,type}))
 *  ack     { t:"ack", id }                          delivery receipt (double tick)
 *  relay   { t:"relay", ip, port, payload }         payload = b64(raw frame bytes)
 *  relayed { t:"relayed", payload }                 relay's response passthrough
 *  ctl     { t:"ctl", from, to, sealed }            sealed control message (Phase 6 timer sync)
 *
 * Everything sensitive lives inside `sealed`; relays and observers see only
 * routing metadata of the hop they carry.
 */
object WireFrames {
    // 1 MiB hard cap on any single frame — bounds the pre-authentication allocation a
    // peer can force. Media no longer rides in one giant frame; it is streamed as
    // ~256 KiB "mediachunk" frames and reassembled (see MessageSender/MediaTransfer).
    const val MAX_FRAME_BYTES = 1024 * 1024

    fun write(out: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val dos = DataOutputStream(out)
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()
    }

    fun read(input: InputStream): JSONObject? {
        val dis = DataInputStream(input)
        val len = try { dis.readInt() } catch (e: Exception) { return null }
        if (len <= 0 || len > MAX_FRAME_BYTES) return null
        val buf = ByteArray(len)
        dis.readFully(buf)
        return try { JSONObject(String(buf, Charsets.UTF_8)) } catch (e: Exception) { null }
    }
}
