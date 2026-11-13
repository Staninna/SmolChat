/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: Long): ModelEntity?

    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    suspend fun getAll(): List<ModelEntity>

    @Insert
    suspend fun insert(model: ModelEntity): Long

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PairedAppDao {
    @Query("SELECT * FROM paired_apps ORDER BY COALESCE(lastUsedAt, pairedAt) DESC")
    fun observeAll(): Flow<List<PairedAppEntity>>

    @Query("SELECT * FROM paired_apps WHERE tokenHash = :hash LIMIT 1")
    suspend fun findByTokenHash(hash: String): PairedAppEntity?

    @Upsert
    suspend fun upsert(app: PairedAppEntity)

    @Query(
        "UPDATE paired_apps SET lastUsedAt = :time, requestCount = requestCount + 1 " +
            "WHERE packageName = :packageName"
    )
    suspend fun recordUse(packageName: String, time: Long)

    @Query("DELETE FROM paired_apps WHERE packageName = :packageName")
    suspend fun revoke(packageName: String)
}

@Dao
interface RequestLogDao {
    @Query("SELECT * FROM request_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<RequestLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RequestLogEntity)

    @Query("DELETE FROM request_log WHERE id NOT IN (SELECT id FROM request_log ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 5000)

    /** Rows for statistics: no bodies, oldest first. */
    @Query(
        "SELECT timestamp, clientPackage, clientLabel, path, status, errorCode, promptTokens, completionTokens, " +
            "durationMs, tokensPerSecond, queueMs, loadMs, promptMs, generationMs, cachedTokens " +
            "FROM request_log WHERE timestamp >= :since ORDER BY timestamp ASC"
    )
    fun observeStatRows(since: Long): Flow<List<StatRow>>

    @Query("UPDATE request_log SET requestBody = NULL, responseBody = NULL")
    suspend fun clearBodies()

    @Query("DELETE FROM request_log")
    suspend fun clear()
}

@Database(
    entities = [ModelEntity::class, PairedAppEntity::class, RequestLogEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ServerDatabase : RoomDatabase() {
    companion object {
        /** Version 2 adds per-phase timings to the request log. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE request_log ADD COLUMN mode TEXT")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN model TEXT")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN queueMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN loadMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN promptMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN generationMs INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN cachedTokens INTEGER")
                    db.execSQL("ALTER TABLE request_log ADD COLUMN finishReason TEXT")
                }
            }
    }

    abstract fun models(): ModelDao

    abstract fun pairedApps(): PairedAppDao

    abstract fun requestLog(): RequestLogDao
}
