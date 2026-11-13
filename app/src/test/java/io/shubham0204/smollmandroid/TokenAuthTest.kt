/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.auth.AuthenticatedClient
import io.shubham0204.smollmandroid.auth.TokenAuth
import io.shubham0204.smollmandroid.auth.TokenLookup
import io.shubham0204.smollmandroid.engine.CpuInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenAuthTest {
    @Test
    fun `tokens are well formed and unique`() {
        val a = TokenAuth.generateToken()
        val b = TokenAuth.generateToken()
        assertTrue(a.startsWith("sk-local-"))
        assertEquals("sk-local-".length + 43, a.length)
        assertTrue(TokenAuth.isWellFormed(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `hash is sha256 hex and stable`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            TokenAuth.hash("hello"),
        )
    }

    @Test
    fun `parses bearer header`() {
        val t = TokenAuth.generateToken()
        assertEquals(t, TokenAuth.parseBearer("Bearer $t"))
        assertEquals(t, TokenAuth.parseBearer("bearer   $t "))
        assertNull(TokenAuth.parseBearer(null))
        assertNull(TokenAuth.parseBearer(t))
        assertNull(TokenAuth.parseBearer("Basic $t"))
        assertNull(TokenAuth.parseBearer("Bearer sk-local-short"))
        assertNull(TokenAuth.parseBearer("Bearer ${t.dropLast(1)}!"))
    }

    @Test
    fun `authenticates only known token hashes`() = runTest {
        val good = TokenAuth.generateToken()
        val revoked = TokenAuth.generateToken()
        val store = mapOf(TokenAuth.hash(good) to AuthenticatedClient("com.denser.june", "June"))
        val lookup = TokenLookup { store[it] }
        assertEquals("com.denser.june", TokenAuth.authenticate("Bearer $good", lookup)?.packageName)
        assertNull(TokenAuth.authenticate("Bearer $revoked", lookup))
        assertNull(TokenAuth.authenticate(null, lookup))
        assertNull(TokenAuth.authenticate("Bearer", lookup))
    }

    @Test
    fun `performance cores skip the slowest cluster`() {
        // Pixel 10: 2x A520 @2.25GHz, 5x A725 @3.05GHz, 1x X4 @3.78GHz
        val freqs = listOf(2_253_000L, 2_253_000, 3_050_000, 3_050_000, 3_050_000, 3_050_000, 3_050_000, 3_780_000)
        assertEquals(6, CpuInfo.performanceCoresFromFrequencies(freqs, 8))
        assertEquals(3, CpuInfo.performanceCoresFromFrequencies(List(4) { 2_000_000L }, 4))
        assertEquals(4, CpuInfo.performanceCoresFromFrequencies(emptyList(), 8))
    }
}
