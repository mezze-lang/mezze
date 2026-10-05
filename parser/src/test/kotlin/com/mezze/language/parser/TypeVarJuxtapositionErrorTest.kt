package com.mezze.language.parser

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Regression for the type-var-applied-to-type-var error message.
 *
 * Before: `{it: 'm 'a}` produced "Record type field ''a' is missing a type annotation"
 *         — confusing because the user wasn't trying to declare a field named `'a`.
 * After: surface that the real issue is trying to apply `'m` to `'a`.
 */
class TypeVarJuxtapositionErrorTest {
    private val parser = MezzeParser()

    @Test
    fun `'m 'a in record type produces clear error`() {
        val source = """
            ability Foo for 'm where {
                bad: {it: 'm 'a} -> 'a
            }
        """.trimIndent()

        try {
            parser.parseParsedModule(source)
            fail("Expected parse error for type var juxtaposition")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            assertTrue(msg.contains("Type variable"),
                "Error should mention 'Type variable', got: $msg")
            assertTrue(msg.contains("cannot be applied"),
                "Error should mention 'cannot be applied', got: $msg")
            // Should NOT use the misleading "missing a type annotation" framing
            assertTrue(!msg.contains("missing a type annotation"),
                "Error should not be the misleading 'missing annotation' message: $msg")
        }
    }

    @Test
    fun `valid uses of type vars in records still work`() {
        // Sanity check: the fix shouldn't have broken legitimate cases.
        // `Result 'a 'e` is a type CONSTRUCTOR applied to type vars — fine.
        val source = """
            ability Foo for 'm where {
                ok: {it: Result Int Str} -> Int
            }
        """.trimIndent()

        // Should parse without error
        val module = parser.parseParsedModule(source)
        assertNotNull(module)
    }

    @Test
    fun `valid quantifier impl heads still parse`() {
        // `impl Eq 'a for Option 'a` — type vars as quantifiers, not applied
        // to each other. Should remain fine.
        val source = """
            type Foo 'a = | Wrap {val: 'a} |

            impl Eq 'a for Foo 'a where {
                eq = {it, r} -> True,
                neq = {it, r} -> False
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
    }

    /**
     * The same misleading framing, reached a second way: a NESTED record type whose
     * field type fails to parse was reported as the OUTER field missing an annotation
     * it plainly has.
     *
     * `{ h: { run: Thunk <Tick> } }` is genuinely invalid — `<Tick>` is arrow syntax,
     * and `run` has no arrow for the row to attach to. But the message named `h` and
     * said `h` had no type annotation, pointing at the one part of the line that was
     * fine. The cause: the field-list parser tolerates matching nothing, so a failure
     * deep in a field's type arrives looking identical to "no fields parsed", with the
     * stream still sitting on the first field name — and the entry parser's honest
     * message had already been discarded.
     *
     * Writing the inner record as a named alias always reported it correctly, so the
     * same construct produced two different diagnostics depending on spelling.
     */
    @Test
    fun `a nested record field whose TYPE fails reports the real reason`() {
        val source = """
            type Thunk = {} -> Int
            let use_it: { h: { run: Thunk <Tick> } } -> Int = { h } -> (h.run) {}
        """.trimIndent()

        try {
            parser.parseParsedModule(source)
            fail("Expected a parse error: `<Tick>` has no arrow to attach to")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("<"),
                "the message should name the token that actually failed, got: $msg",
            )
            assertTrue(
                !msg.contains("'h' is missing a type annotation"),
                "blamed the outer field for an annotation it has: $msg",
            )
        }
    }

    /** A field that really has no annotation must still say so. */
    @Test
    fun `a field with no annotation at all still reports the missing type`() {
        val source = """
            let use_it: { h: { run } } -> Int = { h } -> 1
        """.trimIndent()

        try {
            parser.parseParsedModule(source)
            fail("Expected a parse error: `run` has no type annotation")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("missing a type annotation"),
                "a genuinely annotation-less field should still report that, got: $msg",
            )
        }
    }
}
