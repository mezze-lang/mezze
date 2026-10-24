package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * One dictionary slot filled from ANOTHER constraint's type.
 *
 * An impl over a two-parameter type constrains one ability per parameter:
 *
 *     impl A for Pair 'p 'q  with A 'p, B 'q
 *
 * Each of `fa` and `fb` needs its own dictionary. They were resolved by taking
 * the operation NAME against ONE flat list of the impl's type arguments — in
 * `forallVars` order, first impl of that name claiming any element winning. At
 * `Pair Str Bool` the list is `[Str, Bool]`, so `fb` found `impl B for Str`
 * and the WRONG OPERATION RAN: "A-StrB-Str" where "A-StrB-Bool" was meant.
 *
 * A SILENT WRONG ANSWER, not a crash — it only crashes when the wrong impl's
 * body happens to reject the value, which is how it was found (an
 * `IntoErrorResponse for Str` handed an HttpError variant to `bytes::from_str`
 * in std::effects::http). Both slots holding the SAME type hides it entirely,
 * so `Pair Str Str` always "worked".
 *
 * Two things had to be fixed. Each dict now resolves against the type ITS OWN
 * constraint is about, chosen by which ability declares the operation — not by
 * the dict key's variable, because a key raised inside a match arm names a
 * FRESH variable the substitution does not relate to the impl's. And
 * `refToDictedOp` read the wrapped op's parameter names from `declIndex`,
 * which holds top-level declarations only; for an impl op it found none and
 * DISCARDED the wrapper, passing the bare op with no dictionaries at all.
 */
class DictConstraintCrossingTest {

    private val head = """
        use std::str

        ability A for 'x where { fa: { it: 'x } -> Str }
        ability B for 'y where { fb: { it: 'y } -> Str }

        impl A for Int where { fa = { it } -> "A-Int" }
        impl A for Str where { fa = { it } -> "A-Str" }
        impl B for Bool where { fb = { it } -> "B-Bool" }
        impl B for Str where { fb = { it } -> "B-Str" }

        type Pair 'p 'q = { l: 'p, r: 'q }

        impl A for Pair 'p 'q where {
          fa: { it: Pair 'p 'q } with A 'p, B 'q -> Str = { it } ->
            str::concat { l = it.l.fa {}, r = it.r.fb {} },
        }

        type Either 'p 'q = |
          L { lv: 'p },
          R { rv: 'q },
        |

        impl A for Either 'p 'q where {
          fa: { it: Either 'p 'q } with A 'p, B 'q -> Str = { it } -> match it is
            L { lv } -> lv.fa {}
            R { rv } -> rv.fb {},
        }

        impl A for Result 'p 'q where {
          fa: { it: Result 'p 'q } with A 'p, B 'q -> Str = { it } -> match it is
            Ok { val } -> str::concat { l = "R-ok-", r = val.fa {} }
            Err { error } -> str::concat { l = "R-err-", r = error.fb {} },
        }

        ability C for 'z where { fc: { it: 'z } -> Str }
        impl C for Bool where { fc = { it } -> "C-Bool" }
        impl C for Int where { fc = { it } -> "C-Int" }

        type Box 'p 'q = |
          Full { fv: 'p },
          Empty { ev: 'q },
        |

        impl A for Box 'p 'q where {
          fa: { it: Box 'p 'q } with A 'p, C 'q -> Str = { it } -> match it is
            Full { fv } -> str::concat { l = "X-full-", r = fv.fa {} }
            Empty { ev } -> str::concat { l = "X-empty-", r = ev.fc {} },
        }

        let via: { v: 'v } with A 'v -> Str = { v } -> v.fa {}
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    /** The control: a concrete receiver resolves both slots at the call site. */
    @Test
    fun `a record impl dispatches both slots directly`() {
        val r = run(
            """
            let p: Pair Str Bool = { l = "x", r = True }
            p.fa {}
            """.trimIndent()
        )
        assertEquals("A-StrB-Bool", r)
    }

    /**
     * THE regression: through a constrained function the impl arrives as a
     * dictionary, and its own two dictionaries have to be resolved per
     * constraint rather than from one shared list.
     */
    @Test
    fun `a record impl dispatches both slots through a dictionary`() {
        val r = run(
            """
            let p: Pair Str Bool = { l = "x", r = True }
            via { v = p }
            """.trimIndent()
        )
        assertEquals("A-StrB-Bool", r, "fb must come from B Bool, not from A's Str")
    }

    /**
     * The same over a VARIANT, whose arms bind their payloads through `match`.
     * This is the shape `Result` has, and it needed the second half of the fix:
     * a key raised in a match arm names a fresh variable, so the per-variable
     * path cannot resolve it and the ability-keyed one must.
     */
    @Test
    fun `a variant impl dispatches the second parameter's slot`() {
        val r = run(
            """
            let right: Either Str Bool = R { rv = True }
            via { v = right }
            """.trimIndent()
        )
        assertEquals("B-Bool", r, "the R arm's payload is a Bool, so fb is B Bool's")
    }

    @Test
    fun `a variant impl dispatches the first parameter's slot`() {
        val r = run(
            """
            let left: Either Str Bool = L { lv = "x" }
            via { v = left }
            """.trimIndent()
        )
        assertEquals("A-Str", r)
    }

    /**
     * Both parameters at the SAME type is the case that always worked, and the
     * reason the bug survived: one dictionary fits both slots, so taking the
     * wrong one is invisible. Kept so a future change cannot "fix" the tests
     * above by making this one wrong.
     */
    @Test
    fun `both parameters at one type still dispatches`() {
        val r = run(
            """
            let p: Pair Str Str = { l = "x", r = "y" }
            via { v = p }
            """.trimIndent()
        )
        assertEquals("A-StrB-Str", r)
    }

    /**
     * A Result whose ERROR type is never determined — `Result Str 'a`, which is
     * what inference gives an `Ok { … }` with no `Err` anywhere. No error can
     * exist, so the `B` dictionary can never be called; demanding it is wrong.
     *
     * Two things had to hold for this to work, and each was a separate bug:
     *
     *  - an unresolvable slot must not discard the wrapper. `fb` cannot be
     *    resolved (nothing says what the error type is) and its miss used to
     *    throw away `fa`'s dictionary too, which WAS resolved. The op then ran
     *    bare, `val.fa {}` fell back to a name lookup, found THIS impl again,
     *    recursed, and matched a Str against Ok/Err — surfacing as
     *    "Non-exhaustive match", which reads like a broken match and is a
     *    misdirected dictionary;
     *  - the wrapper's parameter names must be found. `declIndex` holds
     *    top-level declarations only, so for an impl operation it found none
     *    and discarded the wrapper for that reason instead.
     *
     * The marked outputs are load-bearing: this impl's `Ok` arm calls
     * `val.fa {}` on a Str, so without the "R-ok-" prefix the right and wrong
     * impl both print "A-Str" and the test proves nothing. That is how I first
     * mis-read this case.
     */
    @Test
    fun `a Result with an undetermined error type dispatches through a dictionary`() {
        val r = run(
            """
            let free: Result Str 'a = Ok { val = "x" }
            via { v = free }
            """.trimIndent()
        )
        assertEquals("R-ok-A-Str", r)
    }

    /** The control: the same value with its error type pinned. */
    @Test
    fun `a Result with a pinned error type dispatches through a dictionary`() {
        val r = run(
            """
            let pinned: Result Str Str = Ok { val = "x" }
            via { v = pinned }
            """.trimIndent()
        )
        assertEquals("R-ok-A-Str", r)
    }

    /** And the Err arm, which needs the error type to be known. */
    @Test
    fun `a pinned Err arm uses its own ability`() {
        val r = run(
            """
            let bad: Result Str Str = Err { error = "boom" }
            via { v = bad }
            """.trimIndent()
        )
        assertEquals("R-err-B-Str", r)
    }

    /**
     * The slot with NO candidate at all, which is what pins keeping the rest of
     * the wrapper. `C` is implemented for `Bool` only, so at `Box Str 'a` the
     * `fc` slot cannot be filled by anything — not by its own constraint's type,
     * which is undetermined, and not by any of the impl's other type arguments.
     *
     * A miss used to discard the WHOLE wrapper, `fa`'s resolved dictionary
     * included. The operation then ran bare, `fv.fa {}` fell back to a name
     * lookup, found this same impl again and matched a Str against Full/Empty,
     * reported as "Non-exhaustive match" — a misdirected dictionary wearing a
     * broken match's error.
     *
     * TWO `C` impls are load-bearing, and not for the reason they look like.
     * With only one, `tryResolveAbilityOp` takes its sole-candidate fallback —
     * one candidate and a variant receiver returns that candidate whatever its
     * target — and the slot gets filled with `C for Bool` at `Str`. Wrong, but
     * never called, so the miss this test is about never happens. A second impl
     * removes the guess and the slot is genuinely unfillable.
     *
     * The free-error `Result` above does not pin this either: `B` has a `Str`
     * impl, so its unreachable slot resolves from the impl's other type
     * argument.
     */
    @Test
    fun `an unfillable slot does not discard the slots that resolved`() {
        val r = run(
            """
            let free: Box Str 'a = Full { fv = "x" }
            via { v = free }
            """.trimIndent()
        )
        assertEquals("X-full-A-Str", r)
    }

    /** Swapping which parameter is which must swap the answer, not keep it. */
    @Test
    fun `the slots follow their own parameters when swapped`() {
        val r = run(
            """
            let p: Pair Int Str = { l = 7, r = "y" }
            via { v = p }
            """.trimIndent()
        )
        assertEquals("A-IntB-Str", r)
    }
}
