package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.language.truffleir.CpsLowering
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Directly exercises [CpsLowering] walker helpers on real lowered
 * `FunctionIR` produced by the compiler. Phase 1.4 wiring only —
 * proves the walker classifies functions correctly before Phase 1.5
 * starts using it to make rewriting decisions.
 */
class CpsLoweringWalkerTest {

    @Test
    fun `walker sees zero perform sites in pure arithmetic`() {
        val fns = MezzeTestHelper.typeCheck("40 + 2").loweredFunctions
        val totalPerforms = fns.sumOf { CpsLowering.countPerformSites(it.body) }
        val anyHandles = fns.any { CpsLowering.hasHandle(it.body) }
        assertEquals(0, totalPerforms)
        assertEquals(false, anyHandles)
    }

    @Test
    fun `walker counts perform sites in effect-using program`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "div by zero"} else a / b

            let res = safe_div {a = 4, b = 0}

            let handle = {} -> perform ExceptionToResult in Ok {val = res}
            handle {}
        """.trimIndent()
        val fns = MezzeTestHelper.typeCheck(source).loweredFunctions
        val totalPerforms = fns.sumOf { CpsLowering.countPerformSites(it.body) }
        val anyHandles = fns.any { CpsLowering.hasHandle(it.body) }
        assertTrue(totalPerforms >= 1, "expected at least one PerformOpIR, saw $totalPerforms")
        assertTrue(anyHandles, "expected at least one HandleIR")
    }
}
