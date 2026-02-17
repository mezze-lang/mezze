package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

class DebugHandlersTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
        System.setProperty("mezze.project.root", "/Users/shriyans/Projects/mezze")
        // Clear module cache for fresh resolution
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun executeProgram(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        return MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
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
        assertTrue(result is MezzeVariant)
    }
}
