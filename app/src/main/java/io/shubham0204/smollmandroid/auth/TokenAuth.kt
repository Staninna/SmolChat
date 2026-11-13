/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Minimal view of a paired client for authentication. */
data class AuthenticatedClient(val packageName: String, val label: String)

/** Looks up a paired, non-revoked client by the SHA-256 hash of its token. */
fun interface TokenLookup {
    suspend fun findByTokenHash(tokenHash: String): AuthenticatedClient?
}

/**
 * Bearer token handling (docs/LOCAL_LLM_API.md §1.1). Tokens are 256 random bits, and only their
 * SHA-256 hash is ever stored.
 */
object TokenAuth {
    const val TOKEN_PREFIX = "sk-local-"
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return TOKEN_PREFIX + encoder.encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Extracts the token from an `Authorization` header value, or null if malformed. */
    fun parseBearer(header: String?): String? {
        if (header == null) return null
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        val token = parts[1].trim()
        return token.takeIf { isWellFormed(it) }
    }

    fun isWellFormed(token: String): Boolean =
        token.startsWith(TOKEN_PREFIX) &&
            token.length == TOKEN_PREFIX.length + 43 &&
            token.substring(TOKEN_PREFIX.length).all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Resolves the client for an `Authorization` header, or null if it is not authorised. */
    suspend fun authenticate(header: String?, lookup: TokenLookup): AuthenticatedClient? {
        val token = parseBearer(header) ?: return null
        return lookup.findByTokenHash(hash(token))
    }
}
