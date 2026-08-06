package io.agentmail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверяет регистр и границы handle при поиске адресного упоминания. */
class TagMatcherTest {
    @Test
    fun `matches exact tag ignoring case`() {
        assertTrue(TagMatcher.contains("Please ask @Dmitry.Bykov today", "@dmitry.bykov"))
    }

    @Test
    fun `does not match longer handle`() {
        assertFalse(TagMatcher.contains("Ask @dmitry.bykov2", "@dmitry.bykov"))
        assertFalse(TagMatcher.contains("Ask @dmitry.bykov-team", "@dmitry.bykov"))
    }

    @Test
    fun `matches next to punctuation`() {
        assertTrue(TagMatcher.contains("(@dmitry.bykov), please review", "@dmitry.bykov"))
    }
}
