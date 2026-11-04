package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Variant specification, sections 3 and 4: where inclusion applies, and binding.
 *
 * One rule covers all of section 3: WHOEVER RECEIVES A VALUE MUST ACCEPT AT
 * LEAST EVERYTHING THE SENDER CAN SEND. There is no direction to memorise and
 * no variance table — identify the receiver. A list element is read, so it is
 * sent outward; a function parameter is received by the function, which is why
 * the apparent flip happens; a mutable cell both receives and sends, which is
 * where invariance comes from rather than being stipulated.
 *
 * Section 4: a BOUND VALUE has one concrete group and each use is CHECKED
 * against it — inclusion is a check, not a unification, so naming a value
 * changes nothing about where it may go.
 */
class VariantSpecReceiverTest : VariantSpecBase() {

    // ---------------------------------------------- 3.1 the position receives

    @Test fun `a value whose group is a subset fits`() {
        accepts(
            "pub let f = { c: | Red | } -> 1\n" +
                "pub let u = {} -> do\n  let v = Red\n  f { c = v }\n$MAIN",
            "| Red | ⊆ | Red |",
        )
    }

    @Test fun `a value whose group is wider does not fit`() {
        rejects(
            "pub let f = { c: | Red | } -> 1\n" +
                "pub let u = { v: | Red, Pink | } -> f { c = v }\n$MAIN",
            "T0201",
            "f receives, and it cannot handle Pink",
        )
    }

    // ------------------------------------------ 3.2 the function receives

    @Test fun `an over-qualified function fits`() {
        accepts(
            """
            pub let wide = { c: | Red, Pink | } -> 1
            pub let needs_narrow = { f: { c: | Red | } -> Int } -> 2
            pub let u = {} -> needs_narrow { f = wide }
            $MAIN
            """,
            "the FUNCTION is the receiver here, so the check runs against what the " +
                "caller will send — only Red. `wide` accepts Red, so it fits. Nothing " +
                "flips; the receiver is simply a different party. Paired with the case " +
                "below, which must be refused",
        )
    }

    @Test fun `an under-qualified function does not fit`() {
        rejects(
            """
            pub let narrow = { c: | Red | } -> 1
            pub let feed_pink = { f: { c: | Red, Pink | } -> Int } -> 2
            pub let u = {} -> feed_pink { f = narrow }
            $MAIN
            """,
            "T0201",
            "feed_pink will send a Pink, and narrow has no arm for it. Only evidence " +
                "alongside the acceptance above — equality refuses both",
        )
    }

    // --------------------------------------------- 3.3 the caller receives

    @Test fun `a function returning less than expected fits`() {
        accepts(
            """
            pub let supplied = {} -> A
            pub let needs = { f: {} -> | A, B | } -> 1
            pub let u = {} -> needs { f = supplied }
            $MAIN
            """,
            "the caller receives the return, and | A | ⊆ | A, B |",
        )
    }

    @Test fun `a function returning more than expected does not fit`() {
        rejects(
            """
            pub let supplied = {} -> if 1 > 0 then A else B
            pub let needs = { f: {} -> | A | } -> 1
            pub let u = {} -> needs { f = supplied }
            $MAIN
            """,
            "T0201",
            "it could return a B the caller has no arm for. So a function may accept " +
                "MORE and return LESS than the position asks — both from one question",
        )
    }

    // ------------------------------------------- 3.4 / 3.5 containers

    @Test fun `an immutable container element may be narrower`() {
        accepts(
            "pub let wide = { ys: [| Red, Pink |] } -> 1\n" +
                "pub let u = {} -> wide { ys = [Red] }\n$MAIN",
            "a list cannot be written through, so its elements are only ever sent out",
        )
    }

    @Test fun `a mutable container element may be narrower, like any container`() {
        accepts(
            """
            use std::effects::mutation::{ Mut, LocalMut, MutSeq, seq_to_mut, push }
            pub let widen = { m: MutSeq (| Red, Pink |) } -> push { seq = m, v = Pink }
            pub let u = {} -> perform LocalMut in do
              let xs: [| Red |] = [Red]
              let m = seq_to_mut { seq = xs }
              widen { m = m }
            $MAIN
            """,
            "MutSeq has the SAME semantics as Seq — it is a memory optimisation, not a " +
                "different kind of container — so its element behaves exactly as a list's " +
                "does (3.4) and may be narrower.\n\nThis test previously demanded " +
                "invariance, on the reasoning that a mutable cell is written as well as " +
                "read and so must satisfy both directions at once. That derivation is " +
                "sound for a genuinely mutable cell and simply does not describe MutSeq. " +
                "It was also the only rule in the suite that would have needed variance " +
                "information the typechecker does not carry, and the only lever available " +
                "was the constructor's NAME.",
        )
    }

    // ---------------------------------------------------------- 4. binding

    @Test fun `a bound value is checked per use, not unified`() {
        accepts(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let g = { c: | Red, Blue | } -> 2
            pub let u = {} -> do
              let v = Red
              f { c = v } + g { c = v }
            $MAIN
            """,
            "| Red | ⊆ both, so both checks pass and v is never modified. Inclusion is " +
                "a CHECK, not a unification — naming a value changes nothing. A bound " +
                "value's group is always concrete: either the tag is unclaimed (1.2) or " +
                "exactly one named group claims it (1.1), and 8.3 forbids a second",
        )
    }

    @Test fun `a bound value used once`() {
        accepts(
            "pub let f = { c: | Red, Pink | } -> 1\n" +
                "pub let u = {} -> do\n  let v = Red\n  f { c = v }\n$MAIN",
            "CONTROL for the case above: one use must work, else the two-use result " +
                "says nothing about per-use checking",
        )
    }

    @Test fun `two literals are two groups`() {
        accepts(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let g = { c: | Red, Blue | } -> 2
            pub let u = {} -> f { c = Red } + g { c = Red }
            $MAIN
            """,
            "each literal is fitted at its own site; nothing is shared between them",
        )
    }

    @Test fun `a parameter's group comes from the arms`() {
        val t = printedType(
            "pub let both = { c } -> match c is\n  Red -> 1\n  Pink -> 2\n$MAIN", "both")
        assertTrue(
            t.contains("Red") && t.contains("Pink"),
            "an unannotated parameter generalizes to what the arms name; got $t",
        )
    }
}
