package com.aura.crypto.testutil

import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import kotlinx.coroutines.runBlocking

/**
 * Detects whether the liboqs native library is loadable in the current test JVM.
 * Kyber/Dilithium go through JNI; on a plain desktop JVM without the native lib the
 * call fails, and the T2 suites use [available] with JUnit `Assume` to **skip** rather
 * than fail. On an Android emulator (jniLibs present) or a host with native liboqs
 * provisioned, [available] is true and the suites run for real.
 */
object NativeCrypto {
    val available: Boolean by lazy {
        try {
            runBlocking { HybridKem(Hkdf()).generateKyberKeyPair().isSuccess }
        } catch (t: Throwable) {
            false
        }
    }
}
