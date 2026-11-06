package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A recursive do-block `let` lambda that CAPTURES a dictionary from the
 * enclosing function's dict parameter.
 *
 * Two sites have to agree on such a lambda's hash, and they computed it two
 * different ways. The registration pre-computes the hash that the lambda's
 * self-calls will target, before the body compiles, so a self-reference lowers
 * to `CallDirectIR(targetHash = …)`; the emission computes the hash the lambda
 * is actually stored under. `saltedLambdaHash` folds the dict-capture LIST into
 * both — and the registration passed that list as EMPTY.
 *
 * So the moment the inner lambda's `+` dictionary came from the OUTER
 * function's `__dict_add` parameter, the lambda emitted under one hash and
 * called itself at another. The self-call's target resolved nowhere — not the
 * context, not the prelowered stdlib, not the store — and BDSL rejected the
 * whole lambda:
 *
 *     [BDSL] compile failed for lambda (hash=bb46f8cc)
 *         reason=unsupported (node=CallDirectIR:target-not-compiled:e26b4019)
 *
 * after the program had type-checked clean. There is no AST interpreter to fall
 * back to (#391), so this is fatal.
 *
 * Both callers now go through `dictCapturesFor`, so the prediction and the
 * emission are one computation.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RecursiveLetDictCaptureTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    @Test
    void recursiveLetLambdaCapturingTheEnclosingDictRuns() {
        // `zero` is what makes `apply` polymorphic in the accumulator, so
        // `apply` takes `__dict_add` and the recursive `go` captures it.
        // Without a `zero` parameter the accumulator is pinned by the literal
        // and the dictionary never reaches the inner lambda.
        Object r = MezzeTestHelper.run("""
            let step = { x } -> x * 2 + 1
            let apply = { n, f, zero } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + f { x = i } }
              go { i = n, acc = zero }
            apply { n = 4, f = step, zero = 0 }
            """);
        // i = 4,3,2,1 contributing 9 + 7 + 5 + 3.
        assertEquals("24", r.toString());
    }

    @Test
    void theSeedStillReachesTheAccumulator() {
        // Same shape, non-zero seed: pins that the captured dictionary is used
        // for a real addition rather than the seed being dropped.
        Object r = MezzeTestHelper.run("""
            let step = { x } -> x * 2 + 1
            let apply = { n, f, zero } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + f { x = i } }
              go { i = n, acc = zero }
            apply { n = 4, f = step, zero = 100 }
            """);
        assertEquals("124", r.toString());
    }

    @Test
    void twoRecursiveLetsInOneBodyBothCaptureTheDict() {
        // Two sibling recursive lambdas in the SAME do-block, each capturing
        // the same enclosing dictionary. They are textually different, so this
        // pins that the hash agreement holds per-lambda rather than by luck of
        // there being only one.
        Object r = MezzeTestHelper.run("""
            let twice = { n, f, zero } -> do
              let up = { i, acc } ->
                if i <= 0 then acc
                else up { i = i - 1, acc = acc + f { x = i } }
              let down = { i, acc } ->
                if i <= 0 then acc
                else down { i = i - 1, acc = acc + f { x = i } + 1 }
              up { i = n, acc = zero } + down { i = n, acc = zero }
            let step = { x } -> x * 2 + 1
            twice { n = 3, f = step, zero = 0 }
            """);
        // up: 7 + 5 + 3 = 15. down: the same plus 1 each round = 18.
        assertEquals("33", r.toString());
    }
}
