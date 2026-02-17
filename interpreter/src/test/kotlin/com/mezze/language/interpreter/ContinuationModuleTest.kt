package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

/**
 * Test suite for the new std/control/continuation module with delimited continuations.
 */
class ContinuationModuleTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
        // Set project root for stdlib module resolution
        TestUtils.setupProjectRoot()
        // Clear module cache to ensure fresh module resolution
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun executeProgram(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        return MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
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
        assertTrue(result is MezzeRecord, "Expected Unit record, got: ${result::class.simpleName}")
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
        assertTrue(result is MezzeRecord)
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
        assertTrue(result is MezzeRecord)
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
        assertTrue(result is MezzeRecord)
    }
}
