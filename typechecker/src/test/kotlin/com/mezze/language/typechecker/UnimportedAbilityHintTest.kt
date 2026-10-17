package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * "Implement it" is the wrong advice when an impl already exists.
 *
 * A blanket `impl X for 'a` is matched where the constraint is DISCHARGED — the
 * call site — not where the constrained function was written. So calling
 * `with_query`, which is constrained on `ToQuery`, without `ToQuery` in scope
 * reported "{ q: Str } does not implement 'ToQuery'. To enable this, implement:
 * impl ToQuery for { q: Str }". True, and it sends the reader to write a second
 * impl of something the stdlib already implements for every type.
 *
 * The fix the reader needs is an import, and the compiler knows where from.
 */
class UnimportedAbilityHintTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun errorFor(source: String): String = try {
        val ast = parser.parseParsedModule(source.trimIndent())
        typeChecker.typeCheck(ast, java.nio.file.Path.of("."))
        "(no error)"
    } catch (e: Exception) {
        e.message ?: "(empty message)"
    }

    @Test
    fun `an unimported blanket impl is reported as an import, not as missing`() {
        val msg = errorFor(
            """
            use std::effects::http::{ request }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        assertTrue(
            msg.contains("std::codec::query"),
            "the message must name the module to import; got: $msg",
        )
        assertTrue(
            msg.contains("use std::codec::query::{ ToQuery }"),
            "the message must give the import line verbatim; got: $msg",
        )
        assertTrue(
            !msg.contains("To enable this, implement"),
            "writing a second impl is the wrong advice when one already exists; got: $msg",
        )
    }

    @Test
    fun `importing the ability makes the same program check`() {
        // The counterpart: the hint has to be ACTIONABLE. If this fails, the
        // message above is telling people to do something that does not work.
        val msg = errorFor(
            """
            use std::effects::http::{ request, ToQuery }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        assertTrue(msg == "(no error)", "expected the import to be enough; got: $msg")
    }

    @Test
    fun `an ability nothing implements still says to implement it`() {
        // The old message is right in the case it was written for, and must stay.
        val msg = errorFor(
            """
            ability Shout for 'a where {
              shout: { it: 'a } -> Str,
            }

            let go = { x: 'a } with Shout 'a -> Str = { x } -> x.shout {}

            go { x = 42 }
            """,
        )
        assertTrue(
            msg.contains("implement") || msg.contains("Shout"),
            "an ability with no impl anywhere must still ask for one; got: $msg",
        )
        assertTrue(
            !msg.contains("use std::"),
            "nothing in std implements it, so there is no import to suggest; got: $msg",
        )
    }
}
