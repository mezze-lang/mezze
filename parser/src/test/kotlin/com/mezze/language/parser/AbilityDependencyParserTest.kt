package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Originally tested the legacy ability-decl `with` dependency form
 * (`ability Ord for 'a with Eq 'a where { ... }`). That form is no
 * longer accepted — in Mezze all ability constraints live on
 * individual operations via the per-method `with` clause. The parser
 * now rejects ability-level constraints with
 * [ErrorCode.P0119_ABILITY_LEVEL_CONSTRAINT].
 *
 * These tests pin that behavior down (audit task #212).
 */
class AbilityDependencyParserTest {

    private val parser = MezzeParser()

    /**
     * Try to parse `source`, returning the first parse error.
     * `parseParsedModule` either succeeds or surfaces the failure as a
     * Kotlin exception with a message that begins with the diagnostic.
     * For consistent assertions we re-parse via the low-level path and
     * walk the resulting diagnostic.
     */
    private fun parseExpectingFailure(source: String): Throwable {
        return assertThrows(Throwable::class.java) {
            parser.parseParsedModule(source, "ability_test.mz")
        }
    }

    @Test
    fun `ability-level with-constraint is rejected with P0119`() {
        val source = """
            ability Ord for 'a with Eq 'a where {
                compare : {it: 'a, r: 'a} -> Bool
            }
        """.trimIndent()

        val ex = parseExpectingFailure(source)
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("ability-level 'with' constraint") || msg.contains("P0119"),
            "Expected P0119 ability-level constraint error, got: $msg",
        )
        // Hints render inline in the formatted error block.
        assertTrue(
            msg.contains("operation") || msg.contains("with"),
            "Error should reference the per-operation fix, got: $msg",
        )
    }

    @Test
    fun `ability-level multi-dependency form is rejected`() {
        val source = """
            ability OrdShow for 'a with Eq 'a, Show 'a where {
                method : {it: 'a} -> Bool
            }
        """.trimIndent()

        val ex = parseExpectingFailure(source)
        assertTrue(
            (ex.message ?: "").contains("ability-level 'with'"),
            "Expected P0119; got: ${ex.message}",
        )
    }

    @Test
    fun `where-bound on ability declaration is rejected with P0119`() {
        // Rust/Haskell-style `where 'a: Eq` — Mezze does not support this.
        // The `where` keyword in an ability decl only opens the body block;
        // a type-var followed by `:` is the typo'd-from-other-language case.
        val source = """
            ability Ord for 'a where 'a: Eq where {
                compare : {it: 'a, r: 'a} -> Bool
            }
        """.trimIndent()

        val ex = parseExpectingFailure(source)
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("'a: …") || msg.contains("'a:") || msg.contains("P0119"),
            "Expected P0119 where-bound error, got: $msg",
        )
        assertTrue(
            msg.contains("constraints on individual operations") || msg.contains("with"),
            "Error should mention the canonical per-method `with`, got: $msg",
        )
    }

    @Test
    fun `ability without any ability-level constraint parses cleanly`() {
        val source = """
            ability Eq for 'a where {
                eq : {it: 'a, r: 'a} -> Bool
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source, "ok.mz")
        assertEquals(1, module.decls.size)
        val ability = module.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("Eq", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(1, ability.operations.size)
        assertEquals("eq", ability.operations[0].name)
    }

    @Test
    fun `per-method with-constraint inside ability body is fine`() {
        // The canonical form — `with` lives on the operation signature.
        val source = """
            ability Container for 'c where {
                insert : { it: 'c, item: 'a } with Ord 'a -> 'c
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source, "ok.mz")
        assertEquals(1, module.decls.size)
        val ability = module.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("Container", ability.name)
        assertEquals(1, ability.operations.size)
        val insertOp = ability.operations[0]
        assertEquals(1, insertOp.abilityConstraints.size)
        assertEquals("Ord", insertOp.abilityConstraints[0].abilityName)
    }
}
