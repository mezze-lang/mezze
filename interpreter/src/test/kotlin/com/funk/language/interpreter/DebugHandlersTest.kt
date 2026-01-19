package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

class DebugHandlersTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
        // Clear module cache for fresh resolution
        com.funk.language.typechecker.ModuleResolver.clearCache()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    @Test
    fun testNestedFunctionCalls() {
        val source = """
            use std::control::continuation

            # Test what happens when calling an imported function that uses continuation
            let cont = continuation::new {}

            let inner = { x } -> do
                # Extract the suspended value from a continuation
                let extracted = cont.extract_suspended { suspended = x }
                extracted

            let outer = { x } -> do
                # Call inner, which needs to extract suspended values
                inner { x = x }

            # Now test it - create a suspended value
            let gen = {} -> do
                cont.suspend_with { value = 42 }

            let g = gen {}

            # Call outer (which calls inner) to extract the suspended value
            outer { x = g }
        """.trimIndent()

        val result = executeProgram(source)
        println("Result: $result")
        println("Result class: ${result::class.simpleName}")
        assertTrue(result is FunkVariant)
    }
}
