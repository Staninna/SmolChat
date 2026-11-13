/*
 * Copyright (C) 2025 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shubham0204.smollm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GGUFReader {
    companion object {
        init {
            System.loadLibrary("ggufreader")
        }
    }

    private var nativeHandle: Long = 0L

    suspend fun load(modelPath: String) =
        withContext(Dispatchers.IO) { nativeHandle = getGGUFContextNativeHandle(modelPath) }

    fun getContextSize(): Long? {
        assert(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val contextSize = getContextSize(nativeHandle)
        return if (contextSize == -1L) {
            null
        } else {
            contextSize
        }
    }

    fun getChatTemplate(): String? {
        assert(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val chatTemplate = getChatTemplate(nativeHandle)
        return chatTemplate.ifEmpty { null }
    }

    /** `general.architecture`, e.g. "qwen35", or null if missing. */
    fun getArchitecture(): String? = getStringValue("general.architecture")

    /** Reads a string metadata value, or null if it is missing or not a string. */
    fun getStringValue(key: String): String? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        return getString(nativeHandle, key).ifEmpty { null }
    }

    /** Reads an integer metadata value (max element for integer arrays), or null if missing. */
    fun getIntegerValue(key: String): Long? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        return getInteger(nativeHandle, key).takeIf { it >= 0 }
    }

    /** `true` if the file could be parsed as GGUF. */
    val isValid: Boolean
        get() = nativeHandle != 0L

    /** Releases the native gguf_context. */
    fun close() {
        if (nativeHandle != 0L) {
            free(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun getString(nativeHandle: Long, key: String): String

    private external fun getInteger(nativeHandle: Long, key: String): Long

    private external fun free(nativeHandle: Long)

    /** Returns the native handle (pointer to gguf_context created on the native side) */
    private external fun getGGUFContextNativeHandle(modelPath: String): Long

    /** Read the context size (in no. of tokens) from the GGUF file, given the native handle */
    private external fun getContextSize(nativeHandle: Long): Long

    /** Read the chat template from the GGUF file, given the native handle */
    private external fun getChatTemplate(nativeHandle: Long): String
}
