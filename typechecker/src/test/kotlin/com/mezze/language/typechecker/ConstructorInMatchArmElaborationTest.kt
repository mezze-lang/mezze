package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.validation.ElaborationInvariants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The shapes that carried the last three elaboration rows, now closed.
 *
 * A constructor in a match arm that does not name a type parameter (`None`, or
 * `Err { error = error }` in a `Result 'a 'e`), and a polymorphic call inside a
 * match arm, used to reach the end of elaboration carrying a type variable the
 * enclosing declaration's scheme does not quantify.
 *
 * The cause was not in the match, nor in how constructors are typed. Each
 * per-binding solve propagated only its HasConstructor subset, RE-SOLVED on its
 * own, and everything else it learned was dropped — including the equality tying
 * the declaration's ANNOTATION to its inferred body. The annotation's variable and
 * the body's stayed two unrelated roots in the substitution emit reads, so a node
 * stamped from one carried a variable the scheme, quantified from the other, does
 * not bind. Whichever variable a node happened to be stamped from decided whether
 * the gate saw it as bound, which is why swapping the match arms used to make the
 * row disappear.
 *
 * `ConstraintGenerator` now propagates the whole solve. These cases are kept —
 * they are the ones that broke, and they are cheap.
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
            0,
            openTypeVarCount("""
                pub let f: { opt: Option (Option 'a) } -> Option 'a = { opt } -> match opt is
                  Some { val } -> val
                  None -> None
            """),
            "Option.flatten's shape — a bare constructor last in a match",
        )
        assertEquals(
            0,
            openTypeVarCount("""
                pub let g: { r: Result (Result 'a 'e) 'e } -> Result 'a 'e = { r } ->
                  match r is
                    Ok { val } -> val
                    Err { error } -> Err { error = error }
            """),
            "Result.flatten's shape — a payload constructor that names no 'a",
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
            "arms swapped — this was the CLEAN side of the old order-dependence",
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
        // STILL OPEN: `x` is the fold callback's param, which the declaration does
        // not name, so the annotation-variable rule does not reach it. See the
        // baseline header — the blanket rule that would close it is measured unsound.
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
