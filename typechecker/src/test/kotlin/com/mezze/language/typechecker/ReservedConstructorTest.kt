package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * The built-in constructors of Bool/Option/Result/List (True/False, Some/None,
 * Ok/Err, Cons/Nil) are RESERVED — a user variant may not declare them. Rejected
 * uniformly via BuiltinTypes.reservedConstructorOwner.
 */
class ReservedConstructorTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        parser = MezzeParser(); ModuleResolver.clearCache(); TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    private fun rejects(reservedCtor: String, source: String) {
        val parsed = parser.parseParsedModule(source, "test.mz")
        val err = assertThrows<TypeCheckError>("declaring reserved constructor '$reservedCtor' should fail") {
            typeChecker.typeCheck(parsed, Paths.get("."))
        }
        assertTrue(
            err.message?.contains(reservedCtor) == true &&
                err.message?.contains("built-in") == true,
            "error should name the reserved constructor and 'built-in'; got: ${err.message}"
        )
    }

    // Newly reserved (previously slipped through, producing a confusing type error).
    @Test fun `True is reserved`() = rejects("True", """
        type T = | True, Maybe |
        pub let main: {} -> {} = { } -> {}
    """.trimIndent())

    @Test fun `Cons is reserved`() = rejects("Cons", """
        type MyList = | Cons { head: Int }, Empty |
        pub let main: {} -> {} = { } -> {}
    """.trimIndent())

    @Test fun `Nil is reserved`() = rejects("Nil", """
        type MyList = | Full { x: Int }, Nil |
        pub let main: {} -> {} = { } -> {}
    """.trimIndent())

    // Already reserved (regression guard on the existing behavior).
    @Test fun `Ok is reserved`() = rejects("Ok", """
        type MyResult = | Ok { v: Int }, Fail |
        pub let main: {} -> {} = { } -> {}
    """.trimIndent())

    @Test fun `Some is reserved`() = rejects("Some", """
        type MyOpt = | Some { v: Int }, Nothing |
        pub let main: {} -> {} = { } -> {}
    """.trimIndent())
}
