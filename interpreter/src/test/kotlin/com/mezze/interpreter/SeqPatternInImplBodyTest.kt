package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Disease 2 in one concrete place: a seq PATTERN inside an ability-impl body.
 *
 * Impl-op bodies are typed structurally — `TypedAstBuilder.patternBindingTypes`
 * reads each binding's type off the scrutinee's declared shape, because the
 * solver produced no entries for these bodies. For a seq pattern it read the
 * element type from `SeqType`/`ListType` and nothing else, so a scrutinee
 * arriving as `TypeApplication("Seq", [T])` — the DOMINANT form, 5,722
 * occurrences against 1,546 in the typed stdlib — would leave every binding in
 * the pattern with no type at all, and an untyped binding is exactly what makes
 * `==` inside an impl body fall to the structural default.
 *
 * HONESTLY: these six programs all passed BEFORE that second arm was added. Every
 * route tried — the impl target itself, a record field, a variant payload —
 * delivers the scrutinee as a dedicated node, because `[P]` parses straight to
 * `SeqType`. So the one-sided read was a latent hazard rather than a live defect,
 * and the fix is hardening. The tests stay because the hazard is real
 * (`containerElementType` carries a comment about the same one-sidedness, where
 * it DID bite) and because B.1 will move these types around: if a future change
 * starts delivering containers in the other form, these catch it.
 *
 * The programs answer differently depending on which instance is used, so a wrong
 * pick is visible rather than silent: `P`'s `eq` compares mod 10, so
 * `P.(1) == P.(91)` is True by its own instance and False structurally.
 */
class SeqPatternInImplBodyTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val pWithModEq = """
        use std::abilities::compare::{ Eq }
        use std::collections::seq
        pub newtype P = Int
        impl Eq for P where {
          eq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 == b % 10,
          neq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 != b % 10
        }
    """.trimIndent()

    @Test
    fun `a head binding from a seq pattern in an impl body carries the element type`() {
        // `head` is bound by the seq pattern, then compared. If the pattern gave
        // it no type, the comparison falls to the structural default and answers
        // False.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                ability FirstIs for 'a where {
                  first_is: { it: 'a, probe: P } -> Bool
                }
                impl FirstIs for [P] where {
                  first_is = { it, probe } -> match it is
                    [head, ...tail] -> head == probe
                    [] -> False
                }
                let xs: [P] = [P.(1), P.(2)]
                xs.first_is { probe = P.(91) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a rest binding keeps the container type`() {
        // `tail` is bound to the whole container, so comparing it exercises the
        // SEQ's instance over the element's — one level up from the head case.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                ability TailIs for 'a where {
                  tail_is: { it: 'a, probe: [P] } -> Bool
                }
                impl TailIs for [P] where {
                  tail_is = { it, probe } -> match it is
                    [head, ...tail] -> tail == probe
                    [] -> False
                }
                let xs: [P] = [P.(1), P.(2), P.(3)]
                xs.tail_is { probe = [P.(92), P.(93)] }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `two leading bindings both carry the element type`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                ability TwoEq for 'a where {
                  two_eq: { it: 'a } -> Bool
                }
                impl TwoEq for [P] where {
                  two_eq = { it } -> match it is
                    [x, y, ...rest] -> x == y
                    [_, ...rest] -> False
                    [] -> False
                }
                let xs: [P] = [P.(1), P.(91)]
                xs.two_eq {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the control - the same comparison outside an impl body`() {
        // Outside an impl body the solver types the bindings itself, so this path
        // never depended on the structural read. It is here so a failure above
        // localizes to the impl-body path rather than to P's instance.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                let first_is: { xs: [P], probe: P } -> Bool = { xs, probe } -> match xs is
                  [head, ...tail] -> head == probe
                  [] -> False
                first_is { xs = [P.(1), P.(2)], probe = P.(91) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a seq pattern on a record FIELD inside an impl body`() {
        // Reaching the container through a record field puts the solver in the
        // middle, which was the most likely route to the name-carried form. It
        // is not: the field's declared `[P]` is a dedicated node too.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                type Box = { items: [P] }
                ability BoxFirstIs for 'a where {
                  box_first_is: { it: 'a, probe: P } -> Bool
                }
                impl BoxFirstIs for Box where {
                  box_first_is = { it, probe } -> match it.items is
                    [head, ...tail] -> head == probe
                    [] -> False
                }
                let b: Box = { items = [P.(1), P.(2)] }
                b.box_first_is { probe = P.(91) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a seq pattern on a generic container inside an impl body`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                type Wrap = | Holds { xs: [P] } |
                ability WrapFirstIs for 'a where {
                  wrap_first_is: { it: 'a, probe: P } -> Bool
                }
                impl WrapFirstIs for Wrap where {
                  wrap_first_is = { it, probe } -> match it is
                    Holds { xs } -> match xs is
                      [head, ...tail] -> head == probe
                      [] -> False
                }
                let w: Wrap = Holds { xs = [P.(1), P.(2)] }
                w.wrap_first_is { probe = P.(91) }
                """.trimIndent(),
            ),
        )
    }
}
