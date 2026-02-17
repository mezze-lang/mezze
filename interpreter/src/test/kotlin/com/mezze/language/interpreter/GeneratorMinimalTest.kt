package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

// Enabled to test continuation primitives implementation
class GeneratorMinimalTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
        // Set project root for stdlib module resolution
        System.setProperty("mezze.project.root", "/Users/shriyans/Projects/mezze")
        // Clear both module caches for fresh resolution
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun executeProgram(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        return MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    @Test
    fun testMinimalRepro() {
        val source = """
            use std::effects::generator::{yield, next}

            # Simple generator
            let gen = {} -> do
                yield { value = 42 }

            # Create generator
            let g = gen {}

            # Call next
            let result = next { gen = g }

            # Try to access the value field
            result.value
        """.trimIndent()

        val result = executeProgram(source)
        println("Result: $result")
        println("Result class: ${result::class.simpleName}")
        assertTrue(result is MezzeVariant)
    }

    @Test
    fun testRecursiveNext() {
        val source = """
            use std::effects::generator::{yield, next, to_list}

            # Generator that yields 42 then completes
            let gen = {} -> do
                yield { value = 42 }

            # Create generator and convert to list
            let g = gen {}
            to_list { gen = g }
        """.trimIndent()

        val result = executeProgram(source)
        println("Recursive result: $result")
        println("Recursive result class: ${result::class.simpleName}")
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        println("List size: ${list.size()}")
        println("List elements: ${list.toList()}")
    }
}
