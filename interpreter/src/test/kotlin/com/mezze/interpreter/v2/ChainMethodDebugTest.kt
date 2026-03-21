package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ChainMethodDebugTest {
    @BeforeEach
    fun setUp() { com.mezze.language.typechecker.ModuleResolver.clearCache() }
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `simple chain with let`() {
        assertEquals("HELLO", eval("""
            use std::str
            let trimmed = "  hello  ".trim {}
            trimmed.to_upper {}
        """))
    }

    @Test
    fun `direct chain`() {
        assertEquals("HELLO", eval("""
            use std::str
            "  hello  ".trim {}.to_upper {}
        """))
    }

    @Test
    fun `chain with function call between`() {
        assertEquals("HELLO", eval("""
            use std::str
            let trimmed = str::trim {s = "  hello  "}
            trimmed.to_upper {}
        """))
    }
}
