package com.aura.crypto

/**
 * Shared byte-encoding utilities. Ported from ShadowMesh core/crypto.
 */

/** Encode every byte as a two-character lowercase hex string. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * Decode a lowercase or uppercase hex string to a [ByteArray].
 * Inverse of [ByteArray.toHex]. Throws [IllegalArgumentException] on odd-length input.
 */
fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hexToBytes: odd-length string (${hex.length} chars)" }
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Encode [v] as a big-endian 4-byte array. */
fun intTo4Bytes(v: Int): ByteArray = byteArrayOf(
    (v shr 24).toByte(),
    (v shr 16).toByte(),
    (v shr  8).toByte(),
     v.toByte()
)

/** Decode a big-endian 4-byte integer from [b] starting at [off]. */
fun readInt4(b: ByteArray, off: Int): Int =
    ((b[off    ].toInt() and 0xFF) shl 24) or
    ((b[off + 1].toInt() and 0xFF) shl 16) or
    ((b[off + 2].toInt() and 0xFF) shl  8) or
     (b[off + 3].toInt() and 0xFF)
