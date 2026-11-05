package com.mezze.interpreter

import com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap
import com.mezze.interpreter.nodes.builtin.dsl.NativeHandlers
import com.mezze.interpreter.nodes.builtin.dsl.TypedNativeHandlers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The first object-receiver typed shape: `(Object) -> long`, carrying
 * `__native_mut_int_read` past `CallNativeScalar`'s `@TruffleBoundary`.
 *
 * MEASURED on `bench/cases/mutabi` at n=2e7, min of 7, against kernel 2 — the
 * same loop calling an ORDINARY Mezze function, which is the baseline in the
 * same loop regime (kernel 1 fuses and is not a valid baseline, which cost two
 * withdrawn columns in PERF-LOG):
 *
 * | | ps/iter | young GCs |
 * |---|---|---|
 * | 2 ordinary Mezze call | 650 | 3 |
 * | 3 `read_int` via the boundary | 4 196 | 14 |
 * | 3 `read_int` via `CallNativeOL` | **672** | **3** |
 *
 * 6.2x, and a native call now costs what a Mezze call costs. `nativeCompile`
 * builds with it.
 *
 * WHY BOTH A VALUE TEST AND A WIRING TEST. The two paths return the SAME
 * value, so a value test cannot tell them apart — it guards correctness and
 * nothing else. The registration is what decides which path the compiler
 * emits, so it needs its own assertion; without one, losing the fast path is
 * silent, which is exactly how the `KnownMutOps` layer managed to be dead from
 * the day it was written.
 */
class ObjectReceiverTypedShapeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        NativeBootstrap.ensureLoaded()
    }

    /** The wiring: lose this and the op silently returns to the boundary. */
    @Test
    fun `mut_int_read is registered in the OL shape`() {
        assertNotNull(
            TypedNativeHandlers.lookupOL("__native_mut_int_read"),
            "lost the (Object) -> long registration; the op is back on " +
                "CallNativeScalar's boundary and 6.2x slower",
        )
    }

    /** It must ALSO stay on the ordinary registry — the interpreter and every
     *  caller the bytecode compiler does not reach still use that path. */
    @Test
    fun `mut_int_read keeps its ordinary handler too`() {
        assertNotNull(NativeHandlers.handlerOf("__native_mut_int_read"))
    }

    /**
     * Only `MutableInt` qualifies today, and this pins the reason rather than
     * the list: it is the one receiver class with no `@TruffleBoundary` on its
     * methods. Moving the boundary inside a compiled operation for the others
     * measured 13-22x WORSE (`seq::size` 3 926 → 51 492 ps, `seq::get`
     * 7 204 → 157 109), so widening this is a data-structure job first.
     */
    @Test
    fun `the boundary-carrying receivers are deliberately absent`() {
        for (name in listOf(
            "__native_mut_seq_len",
            "__native_mut_string_len",
            "__native_mut_set_size",
            "__native_mut_map_size",
            "__native_mut_deque_size",
            "__native_mut_bytes_len",
        )) {
            assertNull(
                TypedNativeHandlers.lookupOL(name),
                "$name's receiver class carries @TruffleBoundary on its " +
                    "methods, so the typed shape measured SLOWER, not faster",
            )
        }
    }

    /** The value, through the path the bytecode compiler actually emits. */
    @Test
    fun `read_int returns the cell's value`() {
        assertEquals(
            4242L,
            MezzeTestHelper.run(
                """
                use std::effects::mutation::{ LocalMut, int_to_mut, read_int, write_int }

                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 0 }
                  write_int { n = c, v = 4242 }
                  read_int { n = c }

                main {}
                """.trimIndent(),
            ),
        )
    }

    /** And in a loop, which is where the shape is emitted and the win is. */
    @Test
    fun `read_int is correct when the loop around it is compiled`() {
        assertEquals(
            7000L,
            MezzeTestHelper.run(
                """
                use std::effects::mutation::{ LocalMut, int_to_mut, read_int }

                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 7 }
                  let go = { i, acc } ->
                    if i <= 0 then acc
                    else go { i = i - 1, acc = acc + read_int { n = c } }
                  go { i = 1000, acc = 0 }

                main {}
                """.trimIndent(),
            ),
        )
    }
}
