package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

class DebugIncrementalResultTest {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    @Test
    fun `test incremental parsing like TestFullResultStep`() {
        val functions = listOf(
            """pub let map: { result: (Result 'a 'e), f: 'a -> 'b } -> (Result 'b 'e) = { result, f } ->
    match result is
        Ok { val } -> Ok { val = f { val } }
        Err { error } -> Err { error = error }""",

            """pub let map_err: { result: (Result 'a 'e), f: 'e -> 'f } -> (Result 'a 'f) = { result, f } ->
    match result is
        Ok { val } -> Ok { val = val }
        Err { error } -> Err { error = f { error } }""",

            """pub let get_or_else: { result: (Result 'a 'e), def: 'a } -> 'a = { result, def } ->
    match result is
        Ok { val } -> val
        Err { error } -> def"""
        )

        var accumulated = "# std/result.funk - Result type utility functions\n# Note: Result type (Ok/Err) is built-in to the language\n\n"

        for ((index, func) in functions.withIndex()) {
            accumulated += func + "\n\n"
            val funcName = func.lines().first().substringAfter("let ").substringBefore(":").trim()
            println("\n[$index] Testing with function: $funcName")

            try {
                val program = parser.parse(accumulated)
                val typed = typeChecker.typeCheck(program).program
                assertNotNull(typed)
                println("  ✓ SUCCESS with $funcName")
            } catch (e: Exception) {
                println("  ✗ FAILED after adding $funcName")
                println("  Error: ${e.message?.take(300)}")
                throw AssertionError("Failed after adding function $funcName at index $index", e)
            }
        }
    }
}
