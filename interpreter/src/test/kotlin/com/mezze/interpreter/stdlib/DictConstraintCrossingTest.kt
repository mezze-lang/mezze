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
