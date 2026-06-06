package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

class DebugResultExhaustivenessTest {

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
    fun `debug get_or_else exhaustiveness check`() {
        val code = """
            # Test just the get_or_else function
            pub let get_or_else: { result: (Result 'a 'e), def: 'a } -> 'a = { result, def } ->
                match result is
                    Ok { val } -> val
                    Err { error } -> def
        """.trimIndent()

        println("Code: $code")
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        println("Parsed successfully")

        val typed = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typed)
        println("Type checked successfully!")
    }

    @Test
    fun `debug three functions together`() {
        val code = """
            # Map a function over the success value
            pub let map: { result: (Result 'a 'e), f: { val: 'a } -> 'b } -> (Result 'b 'e) = { result, f } ->
                match result is
                    Ok { val } -> Ok { val = f { val } }
                    Err { error } -> Err { error = error }

            # Map a function over the error value
            pub let map_err: { result: (Result 'a 'e), f: { error: 'e } -> 'f } -> (Result 'a 'f) = { result, f } ->
                match result is
                    Ok { val } -> Ok { val = val }
                    Err { error } -> Err { error = f { error } }

            # Get the success value or return a default
            pub let get_or_else: { result: (Result 'a 'e), def: 'a } -> 'a = { result, def } ->
                match result is
                    Ok { val } -> val
                    Err { error } -> def
        """.trimIndent()

        println("Code: $code")
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        println("Parsed successfully")

        val typed = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typed)
        println("Type checked successfully!")
    }
}
