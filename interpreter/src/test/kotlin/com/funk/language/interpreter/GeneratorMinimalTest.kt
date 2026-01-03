package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

// Enabled to test continuation primitives implementation
class GeneratorMinimalTest {
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
        // Clear both module caches for fresh resolution
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
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
        assertTrue(result is FunkVariant)
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
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        println("List size: ${list.elements.size}")
        println("List elements: ${list.elements}")
    }
}
