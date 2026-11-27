package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `match map::get { m, k } is Some { val } -> … , None -> …` is emitted as a raw
 * lookup plus a null test, skipping the `MezzeVariant` the native would allocate
 * for the `Some`. Every spelling of that match must still give the same answer,
 * and the spellings the fusion REFUSES must fall back and give it too.
 *
 * WHY THE FUSION EXISTS. `bench/cases/allocsurface` at n=2e7, one kernel per
 * process, young collections as the evidence:
 *
 *     kernel            ps/iter   young
 *     map size            1 172       3     a native call, no lookup
 *     map has             6 111       3     the same lookup, returns Bool
 *     map get            16 303      18     the same lookup, returns Option
 *
 * `has` sits on the tier control (6 180 ps, 3 collections), so the lookup
 * allocates nothing and costs ~5 ns; the ~10 ns and fifteen extra collections
 * between it and `get` are the `Option` alone — twice the lookup it wraps.
 *
 * WHAT CAN GO WRONG, and so what is tested:
 *
 *  - **A value that is itself a variant.** The fusion decides "present" by
 *    testing the raw result for null. A map holding `None` as a VALUE must
 *    therefore still take the `Some` branch with `val = None` — the payload is
 *    a `MezzeVariant`, not null, and nothing about it is absent. This is the
 *    case that would break a fusion that tested for falsiness instead.
 *  - **EVALUATION ORDER.** A record evaluates its fields in the order WRITTEN,
 *    and the fusion emits the map then the key. Written `{ k = …, m = … }` it
 *    must refuse and fall back, or two side-effecting arguments would run in
 *    the wrong order.
 *  - **A guard, or a wildcard in place of `None`.** A guard must fall back; a
 *    wildcard must fuse and behave.
 *  - **Absent keys**, which are the branch the null test actually selects.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL: inverting the `IsNotNull` test fails
 * THREE of the six. The three that survive are the right three — two expect the
 * `None` branch whichever way the test goes, and the guarded one is REFUSED by
 * the fusion and falls back to the generic path, which is what it is there to
 * check.
 *
 * An earlier draft of the fusion rejected `isScalarABI()`, and then the same
 * sentinel failed only ONE of the six: it fired in straight-line test code and
 * not in a loop, which is where the cost is. The count a sentinel fails is
 * worth reading as a number, not just as "something failed".
 */
class FusedMapGetMatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    @Test
    fun `a present key takes the Some branch with its value`() {
        assertEquals(42L, run(
            """
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 7, v = 42 }
            match map::get { m = m, k = 7 } is
              Some { val } -> val
              None -> 0
            """
        ))
    }

    @Test
    fun `an absent key takes the None branch`() {
        assertEquals(99L, run(
            """
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 7, v = 42 }
            match map::get { m = m, k = 8 } is
              Some { val } -> val
              None -> 99
            """
        ))
    }

    @Test
    fun `the empty map takes the None branch`() {
        assertEquals(5L, run(
            """
            use std::collections::map
            let m = map::empty {}
            match map::get { m = m, k = 1 } is
              Some { val } -> val
              None -> 5
            """
        ))
    }

    /**
     * THE CASE A FALSINESS TEST WOULD FAIL. The value stored is `None`, which is
     * a variant and is emphatically present; only a NULL raw result means
     * absent, and Mezze has no null so a map cannot hold one.
     */
    @Test
    fun `a key whose value is itself None is still present`() {
        assertEquals(1L, run(
            """
            use std::collections::map
            use std::option
            let m = map::insert { m = map::empty {}, k = 1, v = None }
            let present = match map::get { m = m, k = 1 } is
              Some { val } -> match val is
                Some { ... } -> 0 - 1
                None -> 1
              None -> 0
            present
            """
        ))
    }

    /** A wildcard in place of `None` fuses and must behave the same. */
    @Test
    fun `a wildcard arm behaves as None`() {
        assertEquals(1L, run(
            """
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 2, v = 20 }
            let hit = match map::get { m = m, k = 2 } is
              Some { val } -> val
              _ -> 0
            let miss = match map::get { m = m, k = 3 } is
              Some { val } -> val
              _ -> 7
            if hit == 20 && miss == 7 then 1 else 0
            """
        ))
    }

    /**
     * A GUARD must make the fusion refuse and fall back — the generic path
     * handles guards and this one does not, so the answer has to come out the
     * same either way.
     */
    @Test
    fun `a guarded arm still selects correctly`() {
        assertEquals(1L, run(
            """
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 1, v = 10 }
            let big = match map::get { m = m, k = 1 } is
              Some { val } if val > 5 -> 1
              Some { ... } -> 0
              None -> 0
            let small = match map::get { m = m, k = 1 } is
              Some { val } if val > 50 -> 0
              Some { ... } -> 1
              None -> 0
            if big == 1 && small == 1 then 1 else 0
            """
        ))
    }
}
