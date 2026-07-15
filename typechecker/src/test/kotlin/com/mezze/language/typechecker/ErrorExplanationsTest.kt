package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.explain
import com.mezze.language.parsedast.diagnostics.explainErrorCode
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ErrorExplanationsTest {

    @Test
    fun `every ErrorCode has an explanation`() {
        for (code in ErrorCode.entries) {
            val explanation = code.explain()
            assert(explanation.isNotBlank()) { "ErrorCode ${code.name} has blank explanation" }
        }
    }

    @Test
    fun `explainErrorCode finds by display code`() {
        val result = explainErrorCode("T0201")
        assertNotNull(result)
        assertContains(result, "T0201")
        assertContains(result, "TYPE_MISMATCH")
    }

    @Test
    fun `explainErrorCode finds by full name`() {
        val result = explainErrorCode("V0702_UNDEFINED_VARIABLE")
        assertNotNull(result)
        assertContains(result, "V0702")
    }

    @Test
    fun `explainErrorCode returns null for unknown code`() {
        val result = explainErrorCode("X9999")
        assertNull(result)
    }

    @Test
    fun `explanation for T0201 mentions type`() {
        val explanation = ErrorCode.T0201_TYPE_MISMATCH.explain()
        assertContains(explanation, "type")
    }

    @Test
    fun `explanation for V0601 mentions match`() {
        val explanation = ErrorCode.V0601_NON_EXHAUSTIVE_MATCH.explain()
        assertContains(explanation, "match")
    }
}
