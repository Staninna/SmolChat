/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.api.ThinkFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkFilterTest {
    private fun stream(pieces: List<String>, startInThink: Boolean = false): ThinkFilter.Output {
        val f = ThinkFilter(startInThink)
        val c = StringBuilder()
        val r = StringBuilder()
        for (p in pieces) f.push(p).let { c.append(it.content); r.append(it.reasoning) }
        f.finish().let { c.append(it.content); r.append(it.reasoning) }
        return ThinkFilter.Output(c.toString(), r.toString())
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("Hello world", stream(listOf("Hello", " world")).content)
    }

    @Test
    fun `removes a complete think block and the blank lines after it`() {
        val out = ThinkFilter.split("<think>\nplan it\n</think>\n\nToday I went out.")
        assertEquals("Today I went out.", out.content)
        assertEquals("plan it", out.reasoning)
    }

    @Test
    fun `empty think block from enable_thinking=false is removed`() {
        assertEquals("Answer", ThinkFilter.split("<think>\n\n</think>\n\nAnswer").content)
    }

    @Test
    fun `tags split across tokens`() {
        val out = stream(listOf("<th", "ink>", "secret", "</th", "ink", ">", "\n", "Vis", "ible"))
        assertEquals("Visible", out.content)
        assertEquals("secret", out.reasoning)
    }

    @Test
    fun `prompt opened the think block`() {
        val out = stream(listOf("reasoning ", "here", "</think>", "\n\nFinal"), startInThink = true)
        assertEquals("Final", out.content)
        assertEquals("reasoning here", out.reasoning)
    }

    @Test
    fun `lone closing tag before any content is treated as reasoning`() {
        val out = ThinkFilter.split("hidden</think>Shown")
        assertEquals("Shown", out.content)
        assertEquals("hidden", out.reasoning)
    }

    @Test
    fun `a stray closing tag after content never leaks`() {
        val out = stream(listOf("Hello ", "</thi", "nk>", "world"))
        assertEquals("Hello world", out.content)
    }

    @Test
    fun `a less-than sign that is not a tag is kept`() {
        assertEquals("a < b and <b>bold</b>", stream(listOf("a <", " b and <", "b>bold</b>")).content)
    }

    @Test
    fun `unterminated think block never leaks into content`() {
        val out = stream(listOf("Hi. ", "<think>", "still thinking"))
        assertEquals("Hi. ", out.content)
        assertEquals("still thinking", out.reasoning)
    }
}
