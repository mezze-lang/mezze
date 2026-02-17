package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class DebugIncompleteGetOrElseTest {

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
    fun `test incomplete get_or_else as extracted by TestFullResultStep`() {
        // This is what TestFullResultStep actually extracts if function extraction is broken
        // This SHOULD fail because it's missing the Err case
        val code = """
            pub let get_or_else: { result: (Result 'a 'e), def: 'a } -> 'a = { result, def } ->
                match result is
                    Ok { val } -> val
        """.trimIndent()

        println("Testing INCOMPLETE get_or_else (missing Err case):")
        println(code)

        val exception = assertThrows<Exception> {
            val program = parser.parse(code)
            println("Parsed successfully")
            typeChecker.typeCheck(program).program
        }

        println("CORRECTLY FAILED with: ${exception.message}")
        assertTrue(
            exception.message?.contains("Non-exhaustive") == true ||
            exception.message?.contains("Missing cases") == true ||
            exception.message?.contains("doesn't handle all cases") == true,
            "Expected exhaustiveness error, got: ${exception.message}"
        )
    }
}
