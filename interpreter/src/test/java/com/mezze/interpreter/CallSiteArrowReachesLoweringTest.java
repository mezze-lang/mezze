package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A polymorphic callback through a polymorphic HOF, at TWO accumulator types,
 * the second a HETEROGENEOUS add (a Float accumulator against the callback's
 * Int). No annotation anywhere.
 *
 * THESE DO NOT DISCRIMINATE THE FIX, and that is stated here rather than
 * implied. The defect they describe lives on the BUILD path: a reference to a
 * polymorphic function was emitted carrying the DECLARATION'S SCHEME, so
 * lowering read a constraint var nothing had bound and picked the dictionary
 * from the receiver alone — `impl NumAdd Float for Float`, then handed the
 * callback's Long:
 *
 *     UnsupportedSpecializationException … CallNativeDDD
 *         [FloatOps$$Lambda, 0.5, 21]          <- Double, Double, Long
 *
 * `MezzeTestHelper` takes the INTERPRETER path, which ran these programs
 * correctly the whole time. Measured: with the fix removed, both still pass.
 * Two earlier versions of this file were written believing otherwise — one with
 * literal inputs, one seeding from an effect to defeat comptime folding — and
 * neither moved. A lowered-IR test in `:typechecker:test` does not work either:
 * that harness is not the build's lowering (mono is seeded differently, and
 * `apply` keeps its `__dict_add` there with the fix in place), so a test
 * written against it failed in BOTH directions and was deleted.
 *
 * The guard is `scripts/hof-dict-e2e.sh`, which runs the build path and is
 * measured to discriminate. These stay for the reason the aot-parity script
 * keeps its non-discriminating cases: a parity check on a path nothing else
 * covers is still worth having. `bench/cases/hofpoly` is the same program.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CallSiteArrowReachesLoweringTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    @Test
    void oneHofAtTwoAccumulatorTypesWithOneUnAnnotatedCallback() {
        // The Float accumulator against the callback's Int makes the inner `+`
        // a HETEROGENEOUS add. Nothing here carries a type annotation.
        Object r = MezzeTestHelper.run("""
            use std::float

            effect Seed where {
              fseed: {} -> Float <Seed>,
              iseed: {} -> Int <Seed>
            }

            impl Seed for Fixed {} where {
              fseed = {} -> 0.5,
              iseed = {} -> 0
            }

            let step = { x } -> x * 2 + 1

            let apply = { n, f, zero } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + f { x = i } }
              go { i = n, acc = zero }

            perform Fixed {} in do
              let a = apply { n = 4, f = step, zero = Seed.iseed {} }
              let b = apply { n = 4, f = step, zero = Seed.fseed {} }
              float::to_str { n = b }
            """);
        // i = 4,3,2,1 contributing 9 + 7 + 5 + 3, seeded 0.5.
        assertEquals("24.5", r.toString());
    }

    @Test
    void theIntAccumulatorStillGoesThroughTheIntImpl() {
        Object r = MezzeTestHelper.run("""
            use std::int

            effect Seed where {
              iseed: {} -> Int <Seed>
            }

            impl Seed for Fixed {} where {
              iseed = {} -> 0
            }

            let step = { x } -> x * 2 + 1

            let apply = { n, f, zero } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + f { x = i } }
              go { i = n, acc = zero }

            perform Fixed {} in
              int::to_str { n = apply { n = 4, f = step, zero = Seed.iseed {} } }
            """);
        assertEquals("24", r.toString());
    }
}
