package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

class TestSimpleResultFunction {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test simple polymorphic function with arrow syntax`() {
        val source = """
            pub let simple_map: { result: (Result 'a 'e), f: { val: 'a } -> 'b } -> (Result 'b 'e) = { result, f } ->
                match result is
                    Ok { val } -> Ok { val = f { val } }
                    Err { error } -> Err { error = error }
        """.trimIndent()

        println("Testing source:")
        println(source)

        try {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val typed = typeChecker.typeCheck(parsedModule, Paths.get("."))
            assertNotNull(typed)
            println("SUCCESS: Typechecked successfully")
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
