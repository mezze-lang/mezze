package com.mezze.language.typechecker.debug

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ConstraintDumperTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var originalOut: PrintStream

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.dump.constraints", "true")
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
        originalOut = System.out
    }

    @AfterEach
    fun teardown() {
        System.clearProperty("mezze.dump.constraints")
        System.setOut(originalOut)
    }

    @Test
    fun `dump shows generated constraints for simple binding`() {
        val output = captureOutput {
            val source = "let x = 42\nx"
            val parsed = parser.parseParsedModule(source, "dump_test.mz")
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        assertContains(output, "GENERATED CONSTRAINTS")
        assertContains(output, "SOLVER INPUT")
        assertContains(output, "SOLVER RESULT")
    }

    @Test
    fun `dump shows equality constraints for function calls`() {
        val output = captureOutput {
            val source = """
                let add = {a: Int, b: Int} -> a + b
                let result = add { a = 1, b = 2 }
                result
            """.trimIndent()
            val parsed = parser.parseParsedModule(source, "dump_fn.mz")
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        assertContains(output, "Equality")
        assertContains(output, "Int")
    }

    @Test
    fun `dump shows solver result mappings`() {
        val output = captureOutput {
            val source = """
                let identity = {x} -> x
                let result = identity { x = 42 }
                result
            """.trimIndent()
            val parsed = parser.parseParsedModule(source, "dump_poly.mz")
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        assertContains(output, "SOLVER RESULT")
        assertContains(output, "→")
    }

    @Test
    fun `no output when disabled`() {
        System.clearProperty("mezze.dump.constraints")
        val output = captureOutput {
            val source = "let x = 42\nx"
            val parsed = parser.parseParsedModule(source, "dump_disabled.mz")
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        assertTrue(output.isEmpty() || !output.contains("GENERATED CONSTRAINTS"))
    }

    private fun captureOutput(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(originalOut)
        }
        return baos.toString()
    }
}
