/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.shubham0204.smollm.GGUFReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ModelImportException(message: String) : Exception(message)

/** Imports GGUF files picked with the system file picker into app-private storage. */
class ModelRepository(
    private val context: Context,
    private val models: ModelDao,
    private val settings: SettingsRepository,
) {
    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    val observeModels = models.observeAll()

    /** Copies [uri] into app storage, reads its metadata and stores it. [onProgress] gets 0..1. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ModelEntity =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "model.gguf"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (!name.endsWith(".gguf", ignoreCase = true)) {
                throw ModelImportException("$name is not a .gguf file.")
            }
            val free = modelsDir.usableSpace
            if (size > 0 && size > free - 200L * 1024 * 1024) {
                throw ModelImportException("Not enough storage: the model needs ${size / 1_000_000} MB.")
            }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = uniqueFile(safeName)
            val partial = File(target.path + ".part")
            try {
                resolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (size > 0) onProgress((copied.toFloat() / size).coerceIn(0f, 1f))
                        }
                    }
                } ?: throw ModelImportException("Could not open the selected file.")
                if (!partial.renameTo(target)) throw ModelImportException("Could not save the model file.")
            } catch (e: Throwable) {
                partial.delete()
                throw e
            }
            val entity =
                try {
                    readMetadata(target, name.removeSuffix(".gguf").removeSuffix(".GGUF"))
                } catch (e: Throwable) {
                    target.delete()
                    throw e
                }
            val id = models.insert(entity)
            if (settings.current.activeModelId < 0 || models.get(settings.current.activeModelId) == null) {
                settings.update { it.copy(activeModelId = id) }
            }
            entity.copy(id = id)
        }

    suspend fun delete(model: ModelEntity) =
        withContext(Dispatchers.IO) {
            models.delete(model.id)
            File(model.path).delete()
            if (settings.current.activeModelId == model.id) {
                val next = models.getAll().firstOrNull()?.id ?: -1L
                settings.update { it.copy(activeModelId = next) }
            }
        }

    private suspend fun readMetadata(file: File, displayName: String): ModelEntity {
        val reader = GGUFReader()
        reader.load(file.absolutePath)
        try {
            if (!reader.isValid) throw ModelImportException("This file is not a valid GGUF model.")
            val arch = reader.getArchitecture()
            fun int(key: String) = arch?.let { reader.getIntegerValue("$it.$key")?.toInt() }
            return ModelEntity(
                displayName = displayName,
                path = file.absolutePath,
                sizeBytes = file.length(),
                architecture = arch,
                trainedContextLength = int("context_length"),
                blockCount = int("block_count"),
                headCountKv = int("attention.head_count_kv"),
                keyLength = int("attention.key_length"),
                valueLength = int("attention.value_length"),
                fullAttentionInterval = int("full_attention_interval"),
                addedAt = System.currentTimeMillis(),
            )
        } finally {
            reader.close()
        }
    }

    private fun uniqueFile(name: String): File {
        var f = File(modelsDir, name)
        var i = 1
        while (f.exists()) {
            f = File(modelsDir, name.removeSuffix(".gguf") + "-$i.gguf")
            i++
        }
        return f
    }
}
