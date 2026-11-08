package com.mezze.language.typechecker

import com.mezze.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * A printed type is source the reader can write back.
 *
 * That is the standard these two bugs failed, and the arity check landed this
 * session is what makes the failure concrete rather than cosmetic: both printed
 * forms are now programs the compiler REFUSES.
 *
 *  - A generic RECORD alias printed its name and dropped its arguments, so
 *    `Pair Int` came out as a bare `Pair` — which V0705 refuses as
 *    under-applied. `RecordType.typeArgs` exists for this and its own doc says
 *    it is "set by adoptRecordAlias; empty everywhere else"; the annotation
 *    converter was one of the "everywhere else".
 *  - Nested applications printed without brackets at EVERY site, so
 *    `Option (Option Int)` came out as `Option Option Int` — which reads as
 *    `Option` applied to two arguments, and V0705 refuses that too. This one was
 *    general and pre-existing: variants and builtins had it as well, and fixing
 *    the record case above is only what made it visible there.
 *
 * Asserted on the MESSAGE rather than on a printer call, because the message is
 * where the standard applies — the author reads this and edits their source.
 */
class TypePrintingSpecTest : VariantSpecBase() {

    /** The rendered type from a forced mismatch against `Str`. */
    private fun rendered(src: String): String {
        val r = check(src.trimIndent())
        if (r.isSuccess) fail("expected a type mismatch so the type gets printed\n\n$src")
        return (r.exceptionOrNull() as? TypeCheckError)?.message
            ?: fail("expected a TypeCheckError")
    }

    private fun mustPrint(src: String, want: String, why: String) {
        val msg = rendered(src)
        if (!msg.contains(want)) fail("$why\n\nexpected the message to contain: $want\n got: $msg")
    }

    private val want = "pub let want_str = { s: Str } -> s"

    // ============================ a generic alias prints its arguments

    @Test fun `a generic record alias prints its type argument`() {
        mustPrint(
            """
            use std::int
            type Pair 'a = { l: 'a, r: 'a }
            $want
            pub let bad = { q: Pair Int } -> want_str { s = q }
            $MAIN
            """,
            "Pair Int",
            "A bare `Pair` is under-applied, which V0705 now refuses — so the " +
                "message was naming a type the author could not have written.",
        )
    }

    @Test fun `a two-parameter record alias prints both arguments`() {
        mustPrint(
            """
            use std::int
            type Both 'a 'b = { l: 'a, r: 'b }
            $want
            pub let bad = { q: Both Int Bool } -> want_str { s = q }
            $MAIN
            """,
            "Both Int Bool",
            "Arity and order both matter to a reader retyping this.",
        )
    }

    // ================================ nested applications keep their brackets

    @Test fun `a nested builtin application is bracketed`() {
        mustPrint(
            """
            use std::int
            use std::option
            $want
            pub let bad = { o: Option (Option Int) } -> want_str { s = o }
            $MAIN
            """,
            "Option (Option Int)",
            "`Option Option Int` reads as `Option` applied to TWO arguments, " +
                "which V0705 refuses. The printed form has to reparse to the type " +
                "it came from.",
        )
    }

    @Test fun `a nested variant alias is bracketed`() {
        mustPrint(
            """
            use std::int
            type Maybe 'a = | Nowt, Summat 'a |
            $want
            pub let bad = { m: Maybe (Maybe Int) } -> want_str { s = m }
            $MAIN
            """,
            "Maybe (Maybe Int)",
            "The bug was general — not something the record fix introduced, only " +
                "something it made visible there too.",
        )
    }

    @Test fun `a nested record alias is bracketed`() {
        mustPrint(
            """
            use std::int
            type Pair 'a = { l: 'a, r: 'a }
            $want
            pub let bad = { q: Pair (Pair Int) } -> want_str { s = q }
            $MAIN
            """,
            "Pair (Pair Int)",
            "Both fixes at once: the arguments are printed, and the nested one is " +
                "bracketed.",
        )
    }

    // ===================================== what must NOT gain brackets

    @Test fun `a space-free argument is not bracketed`() {
        for ((ann, expect) in listOf("Option Int" to "Option Int", "[Int]" to "[Int]")) {
            mustPrint(
                """
                use std::int
                use std::option
                use std::collections::seq
                $want
                pub let bad = { x: $ann } -> want_str { s = x }
                $MAIN
                """,
                expect,
                "`Int` and `[Int]` are self-delimiting, so bracketing them would be " +
                    "noise. The rule is a space at bracket depth zero, which neither has.",
            )
        }
    }

    @Test fun `a record shape argument keeps its own braces and gains none`() {
        mustPrint(
            """
            use std::int
            use std::option
            $want
            pub let bad = { o: Option { x: Int } } -> want_str { s = o }
            $MAIN
            """,
            "Option { x: Int }",
            "The spaces inside `{ x: Int }` are at depth one, so they do not " +
                "trigger bracketing — the braces already delimit the argument.",
        )
    }

    // ============================== the alias stays TRANSPARENT throughout

    @Test fun `printing the alias name does not make it a distinct type`() {
        accepts(
            """
            use std::int
            type Pair 'a = { l: 'a, r: 'a }
            pub let raw = { r: { l: Int, r: Int } } -> r.l
            pub let viaAlias = { q: Pair Int } -> q.l
            pub let a = { q: Pair Int } -> raw { r = q }
            pub let b = { r: { l: Int, r: Int } } -> viaAlias { q = r }
            $MAIN
            """,
            "Both directions, because the identity carried for PRINTING must not " +
                "leak into equality. `RecordType.typeArgs` is deliberately outside " +
                "equals/hashCode, and setting it in the converter must not change " +
                "that.",
        )
    }
}
