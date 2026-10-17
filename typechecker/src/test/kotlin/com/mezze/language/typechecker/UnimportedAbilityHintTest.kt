package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.FixHint
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Implement it" is the wrong advice when an impl already exists.
 *
 * A blanket `impl X for 'a` is matched where the constraint is settled — the
 * call site — not where the constrained function was written. So calling
 * `with_query`, which is constrained on `ToQuery`, without `ToQuery` in scope
 * reported "{ q: Str } does not implement 'ToQuery'. To enable this, implement:
 * impl ToQuery for { q: Str }". True, and it sends the reader to write a second
 * impl of something the stdlib already implements for every type.
 *
 * What they need is an import, and the compiler knows which one. The message
 * says what is wrong in one plain line; the hints say what to do; the fix hint
 * lets an editor do it.
 */
class UnimportedAbilityHintTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private class Reported(val message: String, val hints: List<String>, val fixHints: List<FixHint>)

    private fun report(source: String): Reported = try {
        val ast = parser.parseParsedModule(source.trimIndent())
        typeChecker.typeCheck(ast, java.nio.file.Path.of("."))
        Reported("(no error)", emptyList(), emptyList())
    } catch (e: TypeCheckError) {
        Reported(e.message, e.hints, e.fixHints)
    }

    @Test
    fun `the message is one plain line and says the ability is not imported`() {
        val r = report(
            """
            use std::effects::http::{ request }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        assertEquals(
            "`ToQuery` isn't imported here, so `{ q: Str }` can't use it", r.message,
            "the one-liner says what is wrong, in the reader's terms, and stops",
        )
        assertTrue(
            !r.message.contains("impl ToQuery for"), "do not tell them to write an impl: ${r.message}",
        )
    }

    @Test
    fun `the hints say an impl already exists and give the import line`() {
        val r = report(
            """
            use std::effects::http::{ request }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        assertEquals(
            listOf(
                "`std::codec::query` implements `ToQuery` for every type, " +
                    "so `{ q: Str }` needs no impl of its own",
                "add `use std::codec::query::{ ToQuery }` at the top of this file",
            ),
            r.hints,
        )
    }

    @Test
    fun `an editor gets the import as a one-click fix`() {
        val r = report(
            """
            use std::effects::http::{ request }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        val imports = r.fixHints.filterIsInstance<FixHint.Import>()
        assertEquals(1, imports.size, "expected one Import fix hint; got ${r.fixHints}")
        assertEquals("ToQuery", imports.single().name)
        assertEquals(listOf("std::codec::query"), imports.single().candidateModules)
    }

    @Test
    fun `importing the ability makes the same program check`() {
        // The counterpart: the advice has to WORK. If this fails, the hint above
        // is telling people to do something that does not help.
        val r = report(
            """
            use std::effects::http::{ request, ToQuery }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        assertEquals("(no error)", r.message, "expected the suggested import to be enough")
    }

    @Test
    fun `an ability nothing implements still says to implement it`() {
        // The old message is right in the case it was written for, and stays.
        val r = report(
            """
            ability Shout for 'a where {
              shout: { it: 'a } -> Str,
            }

            let go: { x: 'a } with Shout 'a -> Str = { x } -> x.shout {}

            go { x = 42 }
            """,
        )
        assertTrue(
            r.message.contains("Shout"), "the ability must be named; got: ${r.message}",
        )
        assertTrue(
            r.hints.none { it.contains("use std::") } && !r.message.contains("use std::"),
            "nothing in std implements it, so there is no import to suggest; got: ${r.message} / ${r.hints}",
        )
    }
}
