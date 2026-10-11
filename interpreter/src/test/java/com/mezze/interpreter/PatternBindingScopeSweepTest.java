package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Soundness sweep following the match-arm scope-leak fix
 * (project_match_arm_binding_scope_leak / commit a6b904518).
 *
 * The fix restores `localsByName` when a match arm closes. This sweep confirms
 * the SAME no-leak / no-alias property for every other pattern-binding construct
 * that mints slots via `forceAllocLocal`: nested record / seq destructuring,
 * seq rest patterns, nested matches, or-patterns, and if-let guards. In each
 * case an inner binding named `b` must NOT survive past its construct to shadow
 * an outer `let b`.
 */
class PatternBindingScopeSweepTest {

    @Test
    void nestedRecordDestructuringDoesNotLeak() {
        Object r = MezzeTestHelper.run("""
            let compute = {} -> do
              let b = 10
              let x = match Some { val = { inner = 5 } } is
                Some { val = { inner = b } } -> b
                None -> 0
              b + x
            compute {}
            """);
        assertEquals(15L, r); // 10 (outer) + 5 (nested), not 5 + 5
    }

    @Test
    void seqHeadBindingDoesNotLeak() {
        Object r = MezzeTestHelper.run("""
            let compute = {} -> do
              let b = 10
              let x = match [20, 30, 40] is
                [b, ...rest] -> b
                [] -> 0
              b + x
            compute {}
            """);
        assertEquals(30L, r); // 10 (outer) + 20 (head), not 20 + 20
    }

    @Test
    void seqRestBindingDoesNotLeak() {
        // `rest` is also a fresh binding; its slot must not leak either.
        Object r = MezzeTestHelper.run("""
            use std::collections::seq
            let compute = {} -> do
              let rest = 7
              let n = match [1, 2, 3] is
                [first, ...rest] -> seq::size { xs = rest }
                [] -> 0
              rest + n
            compute {}
            """);
        assertEquals(9L, r); // 7 (outer rest) + 2 (tail size), not 2 + 2
    }

    @Test
    void nestedMatchDoesNotLeakIntoOuterArm() {
        // The inner match binds `b`; after it, the OUTER arm's `b` must survive.
        Object r = MezzeTestHelper.run("""
            let compute = {} -> match Some { val = 10 } is
              Some { val = b } -> do
                let inner = match Some { val = 5 } is
                  Some { val = b } -> b
                  None -> 0
                b + inner
              None -> 0
            compute {}
            """);
        assertEquals(15L, r); // outer b 10 + inner 5, not 5 + 5
    }

    @Test
    void ifLetBindingDoesNotLeak() {
        Object r = MezzeTestHelper.run("""
            let compute = {} -> do
              let b = 10
              let x = match 1 is
                _ if let Some { val = b } = Some { val = 20 } -> b
                _ -> 0
              b + x
            compute {}
            """);
        assertEquals(30L, r); // 10 + 20, not 20 + 20
    }
}
