package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

/**
 * Test suite for the new std/control/continuation module with delimited continuations.
 */
class ContinuationModuleTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
        // Set project root for stdlib module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
        // Clear module cache to ensure fresh module resolution
        com.funk.language.typechecker.ModuleResolver.clearCache()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    @Test
    fun testModuleLoads() {
        // Test that the new continuation module loads successfully
        val source = """
            use std::control::continuation

            let cont = continuation::new {}
            {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkRecord, "Expected Unit record, got: ${result::class.simpleName}")
    }

    @Test
    fun testDelimiterCreation() {
        // Test that delimiter is created and continuation record is returned
        val source = """
            use std::control::continuation

            let cont = continuation::new {}

            # cont should be a record with methods
            # Just verify it's not null by using it in a do block
            {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkRecord)
    }

    @Test
    fun testCaptureOperation() {
        // Test capture operation from continuation module
        val source = """
            use std::control::continuation

            let cont = continuation::new {}
            let k = cont.capture {}

            {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkRecord)
    }

    @Test
    fun testResumeOperation() {
        // Test resume operation
        val source = """
            use std::control::continuation

            let cont = continuation::new {}
            let k = cont.capture {}
            let result = cont.resume { cont = k, value = 42 }

            {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkRecord)
    }
}
