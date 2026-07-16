package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.parsedast.diagnostics.explain
import com.mezze.language.parsedast.diagnostics.explainWarningCode
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WarningExplanationsTest {

    @Test
    fun `every WarningCode has a non-blank explanation`() {
        for (code in WarningCode.entries) {
            assert(code.explain().isNotBlank()) { "WarningCode ${code.name} has a blank explanation" }
        }
    }

    @Test
    fun `display returns the W-code prefix`() {
        assert(WarningCode.W0101_UNUSED_VARIABLE.display() == "W0101")
        assert(WarningCode.W0102_UNUSED_IMPORT.display() == "W0102")
    }

    @Test
    fun `explainWarningCode finds by display code`() {
        val r = explainWarningCode("W0101")
        assertNotNull(r)
        assertContains(r, "W0101")
    }

    @Test
    fun `explainWarningCode finds by full name`() {
        val r = explainWarningCode("W0102_UNUSED_IMPORT")
        assertNotNull(r)
        assertContains(r, "W0102")
    }

    @Test
    fun `explainWarningCode returns null for unknown`() {
        assertNull(explainWarningCode("W9999"))
    }
}
