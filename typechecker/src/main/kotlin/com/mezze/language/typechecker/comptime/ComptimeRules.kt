package com.mezze.language.typechecker.comptime

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typedast.Hash

/**
 * The rules `comptime`'s signature cannot express.
 *
 * `comptime : { eval: {} -> 'a } with Embeddable 'a -> 'a` already carries most
 * of the contract, and deliberately so — the effect slot rejects an escaping
 * effect through ordinary unification, and `Embeddable` rejects a
 * non-embeddable result through the ordinary ability-obligation error, field
 * paths included. What is left are the two things a type cannot say.
 *
 * Both are decided while type checking, which is what keeps `mez check` and the
 * editor honest: the only ways a build can fail that a check did not are a body
 * that crashes and one that never terminates.
 *
 * Every rule keys on [WellKnownHashConstants.FUNCTION_COMPTIME] — the content
 * hash, never the spelling — so a re-export or a local alias still resolves,
 * and a same-named user binding never does.
 */
object ComptimeRules {

    /** True when [hash] is stdlib `comptime`. */
    fun isComptime(effectHash: Hash?, operationName: String): Boolean =
        WellKnownHashConstants.isComptime(effectHash, operationName)

    /** The shared IR traversal. Needs no hash threaded in: a comptime site is a
     *  `PerformOpIR` carrying a result shape, which identifies itself. */
    val ir: com.mezze.language.comptime.ComptimeIr by lazy {
        com.mezze.language.comptime.ComptimeIr()
    }

    /**
     * Rule 1 — `comptime` is applied, never referenced.
     *
     * The mechanism is that the call site is REPLACED by the value it computes,
     * so there is nothing coherent for a reference to denote: a bare
     * `let f = comptime`, a `comptime` stored in a record, or a partial
     * application `comptime {}` (which row polymorphism would otherwise happily
     * hand back as a function still awaiting `eval`).
     */
    fun rejectNonCallReference(loc: Loc?): Nothing {
        throw TypeCheckError(
            "`comptime` must be applied directly at a call site — it is not a value. " +
                "Write `comptime { eval = {} -> ... }` where you want the result; it " +
                "cannot be passed, stored, or partially applied, because the compiler " +
                "replaces the call itself with the value it computes.",
            loc,
            code = ErrorCode.V0801_COMPTIME_MISUSE,
        )
    }

    /**
     * Rule 2 — the `eval` thunk must be closed.
     *
     * `comptime` runs while the program is being compiled, so anything reachable
     * from an enclosing function's parameters or locals simply does not exist
     * yet:
     *
     *     let scale = { n } -> comptime { eval = {} -> n * 2 }
     *
     * The check is structural rather than a scope analysis: a lowered lambda
     * already records exactly what it closes over, so "closed" is
     * `captures.isEmpty()`. Top-level functions and values are unaffected —
     * they are reached by hash, not captured.
     *
     * This is the most likely mistake by some distance, which is why it is
     * decided here and not left to fail at build time.
     */
    fun checkThunkIsClosed(capturedNames: List<String>, loc: Loc?) {
        if (capturedNames.isEmpty()) return
        val names = capturedNames.distinct()
        val what = names.joinToString(", ") { "`$it`" }
        throw TypeCheckError(
            "`comptime` cannot capture $what — it is a run-time value, and " +
                "`comptime` runs while the program is still being compiled. " +
                "The `eval` thunk must be closed: it may call top-level functions " +
                "and read top-level values, but nothing from an enclosing function.",
            loc,
            code = ErrorCode.V0802_COMPTIME_NOT_CLOSED,
        )
    }

    /**
     * Rule 3 — the `eval` thunk must have PERFORMED its effects.
     *
     * `<*>` on the slot says: whatever this body does, it handles itself. The
     * effects happen, during compilation, and are then erased — nothing survives
     * into the compiled program's type.
     *
     * A body that performs NOTHING does not satisfy that, and the distinction is
     * real even though both look alike from outside: a pure body and a
     * fully-discharged one have the same EMPTY residual row, so the residual is
     * exactly what cannot tell them apart. `performed` can.
     *
     * Enforced here rather than in `unifyEffectRows` because a callback row goes
     * through the contravariance path and never reaches it — and because
     * `comptime` is the only thing in the language that declares `<*>`, so a
     * comptime-only rule is what this actually is.
     */
    // (Former rule on unresolved result types is deliberately absent. An unresolved result type means the call
    // cannot be FOLDED — reification needs a static shape — but it is not an
    // error: the call stays, and the declaration's own `eval {}` body computes
    // the same value at run time. `comptime { eval = {} -> Some { val = 7 } }` is
    // good code whose variant type stays open until a later `match` closes it,
    // and rejecting it was tried and reverted. See TypedASTCompiler's
    // checkComptimeCallSite. V0803 is burned and must not be reused.


    // ── questions to the compiler (introspection §9.2) ─────────────────────
    //
    // A question whose `Comptime` is answered by `ComptimeEval` runs during the
    // build ([BuildTimeUnits]). These are its rules, in its own words: the person
    // wrote a `perform`, not a `comptime`.

    /** The question reads a run-time value, so it cannot be answered while compiling. */
    fun checkQuestionIsClosed(capturedNames: List<String>, loc: Loc?) {
        if (capturedNames.isEmpty()) return
        val what = capturedNames.distinct().joinToString(", ") { "`$it`" }
        throw TypeCheckError(
            "this question to the compiler depends on $what — a run-time value. It is " +
                "answered while the program is being compiled, so it may use literals, " +
                "top-level functions and top-level values, but nothing from an enclosing " +
                "function.",
            loc,
            code = ErrorCode.V0802_COMPTIME_NOT_CLOSED,
        )
    }

    /**
     * A question answered at build whose smallest closed expression is a
     * function, or that two different answers would have to share: there is no
     * value to compute while compiling.
     */
    fun rejectUnfoldableQuestion(loc: Loc?): Nothing {
        throw TypeCheckError(
            "this question to the compiler is answered while compiling, but what it " +
                "computes is a function, or it is answered by two different handlers at " +
                "once — there is no single value to compute at build. Ask for data, " +
                "under one `ComptimeEval`.",
            loc,
            code = ErrorCode.V0801_COMPTIME_MISUSE,
        )
    }
}
