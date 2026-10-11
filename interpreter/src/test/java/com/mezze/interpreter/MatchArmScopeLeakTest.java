package com.mezze.interpreter;

import com.mezze.interpreter.runtime.MezzeLanguage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression: a `match`-arm pattern binding must NOT leak past the match into
 * the enclosing scope.
 *
 * The IR compiler allocates a fresh frame slot for each arm binding
 * (`forceAllocLocal`), which overwrites `localsByName[name]`. `compileMatch`
 * used to restore `scopeStack` and `slotConcreteTypes` when an arm closed, but
 * NOT `localsByName` — so after
 *
 *     let b = 10
 *     let r = match Some { val = 20 } is Some { val = b } -> b; None -> 0
 *     ... b ...
 *
 * a later reference to `b` resolved to the ARM's slot (20), not the outer
 * `let b` (10). The typechecker scoped `b` correctly, so no type error fired;
 * the wrong value simply flowed at runtime — and when the arm binding had a
 * different runtime representation (e.g. Bytes vs Int) it crashed deep in an
 * arithmetic node ("Cannot add Long and MezzeBytes"). See
 * project_match_arm_binding_scope_leak.
 */
class MatchArmScopeLeakTest {

    private Map<String, Object> runAndGetContext(String source) {
        MezzeTestHelper.run(source);
        return MezzeLanguage.getLastExecutionContext();
    }

    @Test
    void armBindingDoesNotShadowOuterLetOfSameName() {
        var ctx = runAndGetContext("""
            let compute = {} -> do
              let b = 10
              let r = match Some { val = 20 } is
                Some { val = b } -> b
                None -> 0
              b + r
            let result = compute {}
            """);
        // b must still be the outer 10, so 10 + 20 = 30 (the bug gave 40).
        assertEquals(30L, ctx.get("result"));
    }

    @Test
    void armBindingDoesNotShadowOuterAcrossTwoMatches() {
        // Two matches each binding `b`; the outer `b` must survive both.
        var ctx = runAndGetContext("""
            let compute = {} -> do
              let b = 100
              let x = match Some { val = 1 } is
                Some { val = b } -> b
                None -> 0
              let y = match Some { val = 2 } is
                Some { val = b } -> b
                None -> 0
              b + x + y
            let result = compute {}
            """);
        // 100 + 1 + 2 = 103 (a leak would give 1 + 1 + 2 = 4 or similar).
        assertEquals(103L, ctx.get("result"));
    }

    @Test
    void armBindingDoesNotLeakIntoSubsequentArmUseOfOuterName() {
        // The outer `n` is used in the SECOND let after a match that binds `n`.
        var ctx = runAndGetContext("""
            let compute = { n: Int } -> do
              let doubled = match Some { val = 5 } is
                Some { val = n } -> n + n
                None -> 0
              n + doubled
            let result = compute { n = 7 }
            """);
        // n stays the parameter 7; doubled = 10; 7 + 10 = 17 (leak would give 5 + 10 = 15).
        assertEquals(17L, ctx.get("result"));
    }
}
