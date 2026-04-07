package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

class DebugIncrementalResultTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
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

        var accumulated = "# std/result.mz - Result type utility functions\n# Note: Result type (Ok/Err) is built-in to the language\n\n"

        for ((index, func) in functions.withIndex()) {
            accumulated += func + "\n\n"
            val funcName = func.lines().first().substringAfter("let ").substringBefore(":").trim()
            println("\n[$index] Testing with function: $funcName")

            try {
                val parsedModule = parser.parseParsedModule(accumulated, "test.mz")
                val typed = typeChecker.typeCheck(parsedModule, Paths.get("."))
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
