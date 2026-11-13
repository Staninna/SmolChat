/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.pairing

import io.shubham0204.smollmandroid.auth.TokenAuth
import io.shubham0204.smollmandroid.data.PairedAppDao
import io.shubham0204.smollmandroid.data.PairedAppEntity

class PairingRepository(private val dao: PairedAppDao) {
    val pairedApps = dao.observeAll()

    /** Issues a new token for [packageName], replacing any previous one. Returns the plain token. */
    suspend fun pair(packageName: String, label: String): String {
        val token = TokenAuth.generateToken()
        dao.upsert(
            PairedAppEntity(
                packageName = packageName,
                label = label,
                tokenHash = TokenAuth.hash(token),
                pairedAt = System.currentTimeMillis(),
            )
        )
        return token
    }

    suspend fun revoke(packageName: String) = dao.revoke(packageName)
}
