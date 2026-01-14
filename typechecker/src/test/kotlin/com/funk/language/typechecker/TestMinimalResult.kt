package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

class TestMinimalResult {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        System.setProperty("funk.project.root", "/tmp/test_std")
    }

    @Test
    fun `test minimal result with map only - no flat_map`() {
        val source = """
            # Minimal result functions

            # Map a function over the success value
            pub let map: { result: (Result 'a 'e), f: 'a -> 'b } -> (Result 'b 'e) = { result, f } ->
                match result is
                    Ok { val } -> Ok { val = f { val } }
                    Err { error } -> Err { error = error }
        """.trimIndent()

        println("Testing map function only")

        try {
            val program = parser.parse(source)
            val typed = typeChecker.typeCheck(program).program
            assertNotNull(typed)
            println("SUCCESS: map function typechecked")
        } catch (e: Exception) {
            println("FAILED on map: ${e.message?.take(500)}")
            throw e
        }
    }

    @Test
    fun `test minimal result with flat_map`() {
        val source = """
            # Chain operations that return Results
            pub let flat_map: { result: (Result 'a 'e), f: 'a -> (Result 'b 'e) } -> (Result 'b 'e) = { result, f } ->
                match result is
                    Ok { val } -> f { val }
                    Err { error } -> Err { error = error }
        """.trimIndent()

        println("Testing flat_map function only")

        try {
            val program = parser.parse(source)
            val typed = typeChecker.typeCheck(program).program
            assertNotNull(typed)
            println("SUCCESS: flat_map function typechecked")
        } catch (e: Exception) {
            println("FAILED on flat_map: ${e.message?.take(500)}")
            throw e
        }
    }
}
