package com.aura.crypto

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoResultTest {

    @Test fun success_holdsValue() {
        val r = CryptoResult.Success(42)
        assertTrue(r.isSuccess); assertFalse(r.isFailure)
        assertEquals(42, r.getOrNull())
        assertEquals(42, r.getOrThrow())
    }

    @Test fun failure_returnsNull_andThrowsOnGetOrThrow() {
        val r: CryptoResult<Int> = CryptoResult.Failure("boom")
        assertTrue(r.isFailure)
        assertNull(r.getOrNull())
        assertFailsWith<IllegalStateException> { r.getOrThrow() }
    }

    @Test fun map_transformsSuccess_passesThroughFailure() {
        assertEquals(4, CryptoResult.Success(2).map { it * 2 }.getOrNull())
        val f: CryptoResult<Int> = CryptoResult.Failure("x")
        assertTrue(f.map { it * 2 }.isFailure)
    }

    @Test fun onSuccess_onFailure_callbacksFire() {
        var s = 0; var fCount = 0
        CryptoResult.Success(1).onSuccess { s = it }.onFailure { fCount++ }
        assertEquals(1, s); assertEquals(0, fCount)
        CryptoResult.Failure("e").onSuccess { s = 99 }.onFailure { fCount++ }
        assertEquals(1, s); assertEquals(1, fCount)
    }

    @Test fun getOrElse_usesFallbackOnFailure() {
        assertEquals(7, CryptoResult.Success(7).getOrElse { -1 })
        assertEquals(-1, (CryptoResult.Failure("e") as CryptoResult<Int>).getOrElse { -1 })
    }

    @Test fun cryptoRunCatching_wrapsSuccess() {
        assertEquals(5, cryptoRunCatching { 5 }.getOrNull())
    }

    @Test fun cryptoRunCatching_capturesException() {
        val r = cryptoRunCatching { throw IllegalArgumentException("nope") }
        assertTrue(r.isFailure)
        assertEquals("nope", (r as CryptoResult.Failure).reason)
    }

    @Test fun cryptoRunCatching_rethrowsCancellation() {
        assertFailsWith<CancellationException> {
            cryptoRunCatching { throw CancellationException("cancelled") }
        }
    }
}
