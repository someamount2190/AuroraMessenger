package com.aura.crypto

import java.util.Base64

/**
 * Base64 helper. The standard encoder emits the standard alphabet with no line
 * wrapping, which is byte-for-byte equivalent to Android's `Base64.NO_WRAP` that the
 * managers used before this code became a platform-independent library.
 */
internal object B64 {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(s: String): ByteArray = decoder.decode(s)
}
