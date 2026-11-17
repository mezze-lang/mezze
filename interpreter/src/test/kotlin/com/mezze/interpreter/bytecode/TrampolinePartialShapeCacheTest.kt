package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `TrampolineRootNode` asks, on every call, whether the incoming record is a
 * PARTIAL APPLICATION — and it asked by looking up each parameter name in the
 * record, a string search per parameter per call, behind a `@TruffleBoundary`.
 *
 * WHAT THAT COST. A loop calling a constraint-carrying function ran at 25 321 ps
 * an iteration against 620 for calls that do not come through this root. A
 * profile put 1 422 of ~5 500 samples in this root's `execute`, 752 in the
 * check, and 404 each in `MezzeRecord.getFieldIndex` and
 * `Shape.findFieldIndex`. Keying the answer on the record's SHAPE took the row
 * to 2 899 — **8.73x**, converged with 8.61x on the second kernel and every
 * control at exactly 1.00x.
 *
 * WHY A SHAPE IS THE RIGHT KEY. The question is whether the record HAS a field
 * for each parameter, which is a property of its field NAMES alone — which is
 * what a `Shape` is. Two records sharing a shape always get the same answer.
 *
 * WHAT THESE TESTS ARE FOR. The cache can only fail one way: by telling the
 * trampoline a record is complete when it is not, which turns a partial
 * application into a call with a missing argument. So only the COMPLETE answer
 * is remembered — a partial application allocates a `MezzeFunction` holding
 * THAT record's supplied arguments, and remembering it would hand a later
 * caller another caller's arguments. Caching the partial answer as well fails
 * `a partial application still works after a complete call to the same
 * function`.
 *
 * The orders matter separately and both are here: complete-then-partial is the
 * case where a cached shape must not be mistaken for a different one, and
 * partial-then-complete is the case where the cache must not have been armed
 * by the partial call.
 */
class TrampolinePartialShapeCacheTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any? = MezzeTestHelper.run(src.trimIndent())

    /** The plain case, so the rest have a baseline: both arguments supplied. */
    @Test
    fun `a complete call is unaffected`() = assertEquals(
        30L,
        run(
            """
            let add = { a, b } -> a + b
            add { a = 10, b = 20 }
            """,
        ),
    )

    /**
     * COMPLETE FIRST, so the cache is armed with the two-field shape, and THEN
     * a partial call whose record has only one field. The one-field shape is a
     * different object, so the guard must miss and the check must run — if the
     * cached shape were treated as matching, `b` would be bound to nothing.
     */
    @Test
    fun `a partial application still works after a complete call to the same function`() = assertEquals(
        // 10 + 20 from the complete call, then 1 + 2 through the partial one.
        33L,
        run(
            """
            let add = { a, b } -> a + b
            let full = add { a = 10, b = 20 }
            let add1 = add { a = 1 }
            full + add1 { b = 2 }
            """,
        ),
    )

    /**
     * PARTIAL FIRST. If the check's answer were remembered regardless of what
     * it was, the one-field shape would be recorded as complete and the later
     * complete call would take the cached path — or worse, a second partial
     * call would reuse the first one's captured argument.
     */
    @Test
    fun `a complete call still works after a partial application`() = assertEquals(
        // 1 + 2 = 3, then 10 + 20 = 30.
        33L,
        run(
            """
            let add = { a, b } -> a + b
            let add1 = add { a = 1 }
            let partial = add1 { b = 2 }
            partial + add { a = 10, b = 20 }
            """,
        ),
    )

    /**
     * TWO PARTIAL APPLICATIONS OF ONE FUNCTION, each supplying a different
     * value for the same field. They share a shape, so this is the case where
     * remembering anything about the first one's record would be visible: the
     * second must carry its own 100, not the first's 1.
     */
    @Test
    fun `two partial applications of one function keep their own arguments`() = assertEquals(
        // (1 + 2) + (100 + 2) = 105.
        105L,
        run(
            """
            let add = { a, b } -> a + b
            let add1 = add { a = 1 }
            let add100 = add { a = 100 }
            add1 { b = 2 } + add100 { b = 2 }
            """,
        ),
    )

    /**
     * THE SAME FIELDS IN A DIFFERENT ORDER. Two call sites may build records
     * whose shapes are different objects for the same field set, so the guard
     * misses on the second and the check runs again — correct, and the point is
     * that a miss is not a failure.
     */
    @Test
    fun `the same fields in a different order still call completely`() = assertEquals(
        // 30 from each spelling.
        60L,
        run(
            """
            let add = { a, b } -> a + b
            add { a = 10, b = 20 } + add { b = 20, a = 10 }
            """,
        ),
    )

    /**
     * A WARM LOOP, because the cached field is `@CompilationFinal` and so a
     * constant to partial evaluation: a straight-line test exercises the
     * interpreter's answer, not the compiled one. 1..300 summed through a
     * two-parameter helper is 45 150.
     *
     * <p>This loop was written to run a partial application BESIDE the complete
     * call, so both paths would stay live in one compiled loop. It cannot: a
     * captured partial application does not complete when it is called from
     * inside another function, which is the pre-existing defect pinned below.
     */
    @Test
    fun `a complete call stays correct in a warm loop`() = assertEquals(
        45150L,
        run(
            """
            let add = { a, b } -> a + b
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = add { a = acc, b = i } }
            go { i = 300, acc = 0 }
            """,
        ),
    )

    /**
     * BUG 43, FIXED: a partial application called from inside ANOTHER FUNCTION
     * returned a function instead of completing.
     *
     * <p>The guess recorded here was merge order in the trampoline. It was not:
     * `add1`'s type carries `NumAdd`, so the monomorphizer cloned the THUNK, and
     * the call site, rewritten to the clone's hash, fell out of the compiler's
     * non-lambda-let set (#389). It was CallDirect'ed, and a thunk ignores its
     * argument and returns the partial again. A clone of a thunk is now a thunk.
     */
    @Test
    fun `a captured partial application completes when called inside a function`() = assertEquals(
        2L,
        run(
            """
            let add = { a, b } -> a + b
            let add1 = add { a = 1 }
            let call = { x } -> add1 { b = x }
            call { x = 1 }
            """,
        ),
    )

    @Test
    fun `a captured partial completes inside a recursive loop`() = assertEquals(
        10L,
        run(
            """
            let add = { a, b } -> a + b
            let add1 = add { a = 1 }
            let loop = { n, acc } -> if n == 0 then acc else loop { n = n - 1, acc = add1 { b = acc } }
            loop { n = 10, acc = 0 }
            """,
        ),
    )

    @Test
    fun `a two-step captured partial completes`() = assertEquals(
        6L,
        run(
            """
            let add3 = { a, b, c } -> a + b + c
            let p1 = add3 { a = 1 }
            let p2 = p1 { b = 2 }
            let call = { x } -> p2 { c = x }
            call { x = 3 }
            """,
        ),
    )
}
