package com.aura.crypto.testutil

import com.aura.crypto.HybridKem
import kotlinx.coroutines.runBlocking

/**
 * Historically gated the liboqs-native (Kyber/Dilithium) test tier. After the crypto
 * re-engineering the entire stack is pure-JVM (BouncyCastle X-Wing + ML-DSA, Tink), so
 * this always reports available; it is retained only so any remaining `Assume`-guarded
 * suites keep compiling. The probe genuinely exercises BC X-Wing key generation.
 */
object NativeCrypto {
    val available: Boolean by lazy {
        try {
            runBlocking { HybridKem().generateKeyPair().isSuccess }
        } catch (t: Throwable) {
            false
        }
    }
}
