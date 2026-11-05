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
 * | kernel | boundary | typed shape | GCs |
 * |---|---|---|---|
 * | 2 ordinary Mezze call | 658 | 642 | 3 |
 * | 3 `read_int` | 4 196 | **640** | 14 → 3 |
 * | 4 `incr` | 4 710 | **774** | 13 → 3 |
 * | 5 `seq::size` | 4 209 | **1 774** | → 3 |
 * | 6 `seq::get` | 7 850 | **5 921** | 19 — it allocates an Option |
 * | 7 `char_at` NOT ported | 9 692 | 8 906 | the negative control |
 * | 8 `string_len` | 3 962 | **1 953** | 13 → 3 |
 *
 * `read_int` and `incr` land AT the ordinary-call baseline, 6.6x and 6.1x, with
 * the allocation gone. `nativeCompile` builds and `just native-test` passes.
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
     * The size and length family, all on `OL`. 2.0-2.4x each.
     *
     * An earlier version of this test asserted these were deliberately ABSENT,
     * on the theory that their receiver classes carry `@TruffleBoundary` on
     * their methods. That was WRONG: `MutableSeq.size()`, `getSafe` and
     * `setSafe` are plain field and array access, and the boundaries in those
     * files sit on other methods. What had actually gone wrong was the
     * registration — see the init block in `MutOps`.
     */
    @Test
    fun `the size and length family is on the OL shape`() {
        for (name in listOf(
            "__native_mut_seq_len",
            "__native_mut_string_len",
            "__native_mut_set_size",
            "__native_mut_map_size",
            "__native_mut_deque_size",
            "__native_mut_bytes_len",
        )) {
            assertNotNull(TypedNativeHandlers.lookupOL(name), "$name lost its shape")
        }
    }

    /** The int and float cell updates, on the two-operand shapes. 6.1x. */
    @Test
    fun `the cell updates are on their two-operand shapes`() {
        for (name in listOf(
            "__native_mut_int_write", "__native_mut_int_incr",
            "__native_mut_int_decr", "__native_mut_int_mul",
            "__native_mut_int_div", "__native_mut_int_mod",
            "__native_mut_seq_get",
        )) {
            assertNotNull(TypedNativeHandlers.lookupOLO(name), "$name lost its shape")
        }
        for (name in listOf(
            "__native_mut_float_write", "__native_mut_float_add",
            "__native_mut_float_sub", "__native_mut_float_mul",
            "__native_mut_float_div",
        )) {
            assertNotNull(TypedNativeHandlers.lookupODO(name), "$name lost its shape")
        }
        assertNotNull(TypedNativeHandlers.lookupOD("__native_mut_float_read"))
        assertNotNull(TypedNativeHandlers.lookupOB("__native_mut_bool_read"))
        assertNotNull(TypedNativeHandlers.lookupOBO("__native_mut_bool_write"))
        assertNotNull(TypedNativeHandlers.lookupOO("__native_mut_bool_toggle"))
        assertNotNull(TypedNativeHandlers.lookupOOB("__native_mut_set_contains"))
    }

    /**
     * The NEGATIVE CONTROL for the whole change. `string_char_at` allocates a
     * variant and a String per call, is on no shape, and does not move:
     * 9 692 ps through the boundary against 8 906 with every other op ported.
     * If it ever moved, the measurement would be reading something other than
     * the shape.
     */
    @Test
    fun `an unported op stays on no shape`() {
        assertNull(TypedNativeHandlers.lookupOLO("__native_mut_string_char_at"))
        assertNull(TypedNativeHandlers.lookupOL("__native_mut_string_read"))
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
