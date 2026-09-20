package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.validation.ElaborationInvariants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The repro for the elaboration baseline's two `flatten` rows, in twelve lines.
 *
 * A constructor in a match arm that does not name a type parameter — `None`, or
 * `Err { error = error }` in a `Result 'a 'e` — reaches the end of elaboration
 * carrying a type variable the enclosing declaration's scheme does not quantify.
 * Measured at `std/option.mz:139` and `std/result.mz:128`:
 *
 *   None    per-loc = Option 'a(27584)    context = Option 'f583(27592)
 *
 * Both are solved, both substituted, and they name DIFFERENT variables for one
 * type. The node is stamped from per-loc; the scheme quantifies the other. That
 * is the row — one written type variable that became two, not a fold and not a
 * receiver (the baseline header used to say both).
 *
 * The giveaway that it is a solver artifact and not a property of the code is
 * that it is ORDER-DEPENDENT: the same function with its arms swapped is clean.
 * `generateMatch` emits an Equality from EVERY arm to the result var, so the
 * constraint side is right and the two variables should already be one.
 *
 * These tests pin the boundary as measured. When the upstream fix lands (one
 * elaboration, one variable per written one) `bare constructor last` starts
 * passing with 0, and THAT is the signal to delete its expectation here and the
 * `flatten` rows from the baseline — not a reason to edit the number.
 */
class ConstructorInMatchArmElaborationTest {
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser()
    }

    private fun openTypeVarCount(src: String): Int {
        val ast = parser.parseParsedModule(src.trimIndent(), "t.mz")
        val typed = TypeChecker().typeCheck(ast, Paths.get("."), enablePrelude = true).typedModule
            ?: fail("no typed module — the case must TYPE-CHECK; an open variable is the point")
        return ElaborationInvariants.check(typed)
            .count { it.kind == ElaborationInvariants.Kind.UNBOUND_TYPE_VAR }
    }

    @Test
    fun `bare constructor last leaves a variable the scheme does not bind`() {
        // std/option.mz:139 and std/result.mz:128, reduced. Currently 1 each.
        assertEquals(
            1,
            openTypeVarCount("""
                pub let f: { opt: Option (Option 'a) } -> Option 'a = { opt } -> match opt is
                  Some { val } -> val
                  None -> None
            """),
            "Option.flatten's shape",
        )
        assertEquals(
            1,
            openTypeVarCount("""
                pub let g: { r: Result (Result 'a 'e) 'e } -> Result 'a 'e = { r } ->
                  match r is
                    Ok { val } -> val
                    Err { error } -> Err { error = error }
            """),
            "Result.flatten's shape",
        )
    }

    @Test
    fun `the same function with its arms swapped is clean`() {
        // Identical program, arms reordered. Nothing about the CODE changed, so a
        // difference here can only come from the solve.
        assertEquals(
            0,
            openTypeVarCount("""
                pub let r: { o: Option (Option 'a) } -> Option 'a = { o } -> match o is
                  None -> None
                  Some { val } -> val
            """),
            "arms swapped — if this ever reports > 0, the artifact has spread",
        )
    }

    /**
     * The baseline's THIRD row, `intersperse`, reduced — the same root seen from
     * the other side.
     *
     * A polymorphic call inside a match arm does not tie its type variable to the
     * enclosing scheme: `fold_left` over a list bound by the arm reports its
     * callee, its call, and its callback's `x` param as open. Lift the identical
     * fold OUT of the match and it is clean, which is what makes this the same
     * defect as the constructor rows rather than something about `fold_left`.
     *
     * Unlike the constructor case this is NOT order-dependent — either arm
     * position reports it, and so does a simple `Some { val = rest }` binding in
     * place of `intersperse`'s nested `{ head, tail }` one. The arm, not the
     * pattern, is what breaks the tie.
     */
    @Test
    fun `a polymorphic call inside a match arm leaves its variable open`() {
        val inMatch = openTypeVarCount("""
            use std::collections::list::{fold_left, cons, empty}
            pub let fm: { o: Option (List 'a), sep: 'a } -> List 'a = { o, sep } -> match o is
              None -> empty {}
              Some { val = rest } -> fold_left {
                xs = rest,
                init = empty {},
                f = { acc, x } -> cons { head = x, tail = cons { head = sep, tail = acc } },
              }
        """)
        val lifted = openTypeVarCount("""
            use std::collections::list::{fold_left, cons, empty}
            pub let fo: { xs: List 'a, sep: 'a } -> List 'a = { xs, sep } ->
              fold_left {
                xs = xs,
                init = empty {},
                f = { acc, x } -> cons { head = x, tail = cons { head = sep, tail = acc } },
              }
        """)
        assertEquals(2, inMatch, "the fold INSIDE a match arm (std/collections/list.mz:236)")
        assertEquals(0, lifted, "the SAME fold lifted out of the match")
    }

    @Test
    fun `the shapes that are already clean stay clean`() {
        // Guards against a future fix that closes the rows by loosening something
        // and takes these with it.
        assertEquals(0, openTypeVarCount("pub let h: { x: Int } -> Option 'a = { x } -> None"),
            "bare constructor in RETURN position")
        assertEquals(
            0,
            openTypeVarCount("""
                pub let k: { o: Option 'a } -> Option 'a = { o } -> match o is
                  Some { val } -> Some { val = val }
                  None -> None
            """),
            "both arms construct",
        )
        assertEquals(
            0,
            openTypeVarCount("""
                pub let m: { b: Bool } -> Option 'a = { b } -> match b is
                  True -> None
                  False -> None
            """),
            "no arm has a type of its own",
        )
    }
}
