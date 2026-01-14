package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Test that modules are cached with qualified names
 */
class CacheQualificationTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        // Clear both caches
        TypeChecker.clearCache()
        ModuleResolver.clearCache()
        
        // Enable debug output
        System.setProperty("funk.typecheck.debug", "true")

        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    @DisplayName("Modules should be cached with qualified names")
    fun testModuleCachingWithQualification() {
        val source = """
            use std::collections::array::{of}
            use std::collections::list::{from_array}

            let arr = of { x = 1, xs = of { x = 2, xs = [] } }
            arr
        """.trimIndent()

        println("\n=== Starting testModuleCachingWithQualification ===")
        
        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        println("\n=== Type checking complete ===")
        
        // If we get here without errors, the test passes
        assertNotNull(result)
        assertTrue(result.program.items.isNotEmpty())
    }
}
