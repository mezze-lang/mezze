package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Heterogeneous `+` — `impl NumAdd Int for Float`, shipped by
 * "feat(abilities): class-parameter NumAdd for heterogeneous `+`".
 *
 * `NumAdd Float for Float` and `NumAdd Int for Float` share a receiver and
 * differ ONLY in the operand, so the full positional tuple is the only thing
 * that can choose between them. Both call-site emitters built that tuple with
 * `.mapNotNull { it as? VarType }`, dropping every CONCRETE position — so
 * `NumAdd Int Float` arrived as the 1-tuple `[Int]`, which
 *
 *   - matches no 2-annotation impl (the arities differ), and
 *   - reads as a SINGLE-parameter ability, so the B1 multi-param guard that
 *     exists to stop receiver-only matching does not apply,
 *
 * leaving a receiver-only match on whichever var survived. For
 * `{ n } -> 0.5 + n` that survivor is the OPERAND, so it matched
 * `impl NumAdd Int for Int`, whose `out = Int`:
 *
 *     error[T0201]: Type mismatch for associated type 'out':
 *                   expected Int but got Float
 *
 * The CONSUMER is part of the shape and not incidental: `out` has to be
 * pinned to Float from outside before the resolution runs, or it stays open,
 * nothing conflicts, and the wrong impl is never noticed. Written as a bare
 * `direct { n = 21 }` with nothing consuming it, every case here passes with
 * the bug still in.
 *
 * ONLY the first test fails without the fix, and the asymmetry is the evidence
 * for the diagnosis rather than a gap in the tests. The receiver used to be
 * taken as "the first type arg that happens to be a VARIABLE", so
 *
 *     n + 0.5    `NumAdd Float 'a`  first var IS the receiver  -> right, by luck
 *     0.5 + n    `NumAdd 'a Float`  first var is the OPERAND   -> wrong impl
 *
 * The other three are guards: the operand-first direction, the homogeneous
 * Float pair, and the homogeneous Int pair that every arithmetic-heavy program
 * in this suite depends on.
 *
 * The tuple now carries concrete positions, and a multi-parameter ability no
 * longer falls back to receiver-only matching — a wrong impl is a DIFFERENT
 * operation, not a weaker answer. `bench/cases/hetero` is the reproducer.
 */
@Execution(ExecutionMode.SAME_THREAD)
class HeterogeneousNumericDispatchTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    @Test
    void floatReceiverWithIntOperandThroughAnUnAnnotatedParameter() {
        // The receiver is CONCRETE (0.5) and the operand is a parameter the
        // call site pins to Int — the position that used to be dropped.
        Object r = MezzeTestHelper.run("""
            use std::float
            let direct = { n } -> 0.5 + n
            float::to_str { n = direct { n = 21 } }
            """);
        assertEquals("21.5", r.toString());
    }

    @Test
    void intReceiverWithFloatOperandIsTheOtherDirection() {
        // `impl NumAdd Float for Int having 'out = Float` — receiver Int,
        // operand Float. The tuple ORDER is what distinguishes this from the
        // case above, not the set of types involved.
        Object r = MezzeTestHelper.run("""
            use std::float
            let direct = { n } -> n + 0.5
            float::to_str { n = direct { n = 21 } }
            """);
        assertEquals("21.5", r.toString());
    }

    @Test
    void theHomogeneousImplIsStillChosenWhenBothAreFloat() {
        // The guard must not cost the ordinary case: receiver and operand both
        // Float, so `NumAdd Float for Float` is the one that applies.
        Object r = MezzeTestHelper.run("""
            use std::float
            let direct = { n } -> 0.5 + n
            float::to_str { n = direct { n = 21.25 } }
            """);
        assertEquals("21.75", r.toString());
    }

    @Test
    void anIntOnlyAddStillResolvesToTheIntImpl() {
        // Nothing about the multi-param guard may disturb the homogeneous Int
        // path, which is every arithmetic-heavy program in the suite.
        Object r = MezzeTestHelper.run("""
            use std::int
            let direct = { n } -> 2 + n
            int::to_str { n = direct { n = 21 } }
            """);
        assertEquals("23", r.toString());
    }
}
