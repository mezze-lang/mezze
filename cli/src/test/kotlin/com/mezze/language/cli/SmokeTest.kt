package com.mezze.language.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/** Trivial test to verify JUnit discovery in the :cli module. */
class SmokeTest {
    @Test
    fun `cli test discovery works`() {
        assertEquals(2, 1 + 1)
    }
}
