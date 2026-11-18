package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The bench program itself (bench/cases/contloop, kernel 9), run through the harness. */
class ContloopKernel9Test {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @Test
    fun `kernel 9 of the park bench runs in the harness`() {
        val src = javaClass.getResource("/contloop_k9.mz")!!.readText()
        val out = MezzeTestHelper.runWithOutput(src).output()
        assertTrue(out.contains("sum=3003"), out)
    }
}
