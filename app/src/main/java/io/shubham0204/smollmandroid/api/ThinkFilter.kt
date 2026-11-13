/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.api

/**
 * Incrementally splits generated text into visible content and `<think>…</think>` reasoning.
 *
 * Tags may be split across token pieces, so text that could be the start of a tag is held back
 * until it can be decided. Qwen-style templates sometimes open the think block in the prompt, so
 * a lone `</think>` near the start is also handled: anything before it counts as reasoning. When the
 * prompt is known to end inside an open think block, pass `startInThink = true`.
 */
class ThinkFilter(startInThink: Boolean = false) {
    data class Output(val content: String, val reasoning: String)

    private val pending = StringBuilder()
    private var inThink = startInThink
    private var sawAnyContent = false
    private val contentSoFar = StringBuilder()

    /** Feed one piece; returns what can be emitted now. */
    fun push(piece: String): Output {
        pending.append(piece)
        return drain(final = false)
    }

    /** Flush everything left at the end of generation. */
    fun finish(): Output = drain(final = true)

    private fun drain(final: Boolean): Output {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        while (pending.isNotEmpty()) {
            val tag = if (inThink) CLOSE else OPEN
            val idx = pending.indexOf(tag)
            // A closing tag without an opening one. Before any visible text it means the template
            // opened the block in the prompt, so the text so far was reasoning. After visible text
            // the stray tag is simply dropped so it never reaches the client.
            if (!inThink) {
                val closeIdx = pending.indexOf(CLOSE)
                if (closeIdx >= 0 && (idx < 0 || closeIdx < idx)) {
                    val before = pending.substring(0, closeIdx)
                    if (!sawAnyContent) reasoning.append(before) else appendContent(content, before)
                    pending.delete(0, closeIdx + CLOSE.length)
                    continue
                }
            }
            if (idx >= 0) {
                val before = pending.substring(0, idx)
                if (inThink) reasoning.append(before) else appendContent(content, before)
                pending.delete(0, idx + tag.length)
                inThink = !inThink
                continue
            }
            // No complete tag: keep back a suffix that could still become a tag.
            val keep = if (final) 0 else partialTagSuffix(pending)
            val emit = pending.substring(0, pending.length - keep)
            if (inThink) reasoning.append(emit) else appendContent(content, emit)
            pending.delete(0, pending.length - keep)
            break
        }
        if (final) {
            if (inThink) {
                // unterminated think block: treat the rest as reasoning
                inThink = false
            }
        }
        return Output(content.toString(), reasoning.toString())
    }

    private fun appendContent(out: StringBuilder, text: String) {
        if (text.isEmpty()) return
        var t = text
        // the text right after a think block usually starts with blank lines: drop them
        if (contentSoFar.isEmpty()) t = t.trimStart()
        if (t.isEmpty()) return
        // Hold back checks for a late "</think>" only while nothing visible was produced; once we
        // emit text we commit to it being content.
        if (!sawAnyContent && t.isNotBlank()) sawAnyContent = true
        out.append(t)
        contentSoFar.append(t)
    }

    /** Length of the longest suffix of [buf] that is a proper prefix of an opening or closing tag. */
    private fun partialTagSuffix(buf: StringBuilder): Int {
        val maxLen = minOf(buf.length, CLOSE.length - 1)
        for (len in maxLen downTo 1) {
            val suffix = buf.substring(buf.length - len)
            if (OPEN.startsWith(suffix) || CLOSE.startsWith(suffix)) return len
        }
        return 0
    }

    companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"

        /** Convenience for complete strings. */
        fun split(text: String): Output {
            val f = ThinkFilter()
            val a = f.push(text)
            val b = f.finish()
            return Output((a.content + b.content).trimEnd(), (a.reasoning + b.reasoning).trim())
        }
    }
}
