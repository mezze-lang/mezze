package com.mezze.language.typechecker.validation

import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError

/**
 * `cont.resume` and `cont.abort` may only appear as the LAST thing a performer op
 * body does, and `cont` (or either handle) may never be used as a value.
 *
 * The rule exists because of what they ARE at runtime, which is not what they
 * look like (see EffectHandlerDriver / EffectBindingNode):
 *
 *   - `cont.resume { val = X }` is the IDENTITY FUNCTION. It transfers no control.
 *     The body's value becomes the op's result because the handler was called
 *     as an ordinary function at the op-call site.
 *   - `cont.abort { val = X }` THROWS, caught at the enclosing `perform`.
 *
 * So Mezze has tail-resumptive handlers plus an escape continuation — not
 * one-shot continuations. There is no `k` to store, defer, or call twice. Every
 * spelling that assumes otherwise type-checks today and means something the
 * author did not write. MEASURED:
 *
 *     a = {} -> do
 *       cont.resume { val = 1 }
 *       cont.resume { val = 2 }
 *
 * prints `body ran, v = 2` — ONCE. The first call computed 1 as a discarded
 * statement; it did not run the body. In Eff/Koka/OCaml 5 that same handler is
 * multi-shot and the body runs twice. Likewise `cont.resume { val = x }` followed by
 * cleanup runs the cleanup IMMEDIATELY, not after the body resumes, and
 * `abort` followed by anything leaves dead code after a throw.
 *
 * None of those are rejected by typing: `resume`'s type is
 * `{ val: opReturn } -> performReturn`, which is exactly a delimited
 * continuation's type. The type system is general enough for the feature the
 * runtime does not implement, which is precisely why the restriction has to be
 * stated here instead.
 *
 * SURVEYED before writing: every explicit `resume`/`abort` in the stdlib (9 of
 * them, in comptime/exception/early_return/noop) is already a lone tail call,
 * and so is every use across the test suites. Several bodies have MORE THAN ONE
 * `abort` — one per `match` arm — which is why the rule is TAIL POSITION and not
 * "at most once": each arm is its own tail.
 *
 * Narrow on purpose: it rejects spellings, never changes what a valid program
 * means, and says nothing about a body that uses neither name (the common case,
 * including `atomically = { f } -> f {}`).
 */
object HandlerTailPositionValidator {

    /** Check one performer op body. [implLabel] is e.g. `impl 'StringHandler'`. */
    fun validate(body: ParsedExpr, opName: String, implLabel: String) {
        walk(body, inTail = true, opName = opName, implLabel = implLabel, inHook = false)
    }

    /**
     * Check an `initialize_scope` / `finalize_scope` body, where NEITHER binding
     * is meaningful — a hook is not an operation. It runs around the block, not
     * in response to a performed op, so there is no op call to hand a value back
     * to.
     *
     * All four combinations type-checked before this and were wrong in three
     * different ways, MEASURED:
     *
     *   abort in initialize_scope  java.lang.ArrayIndexOutOfBoundsException:
     *                              Index -1 out of bounds for length 16
     *   abort in finalize_scope    silently SWALLOWED — `cont.abort { val = 22 }` on a
     *                              block that produced 7 still yields 7
     *   resume in either           identity; meaningless noise
     *
     * The first is the worst: `invokeInitializeScope` runs BEFORE
     * `pushHandlerAndEnterHandle`, so an abort escaping the hook unwinds into a
     * catch that pops a handler never pushed. `length 16` is
     * `HandlerChainRegistry.EffectState`'s INITIAL_CAPACITY and `-1` is its
     * `depth` — the per-thread handler chain is left CORRUPTED, which is worse
     * than a clean crash.
     */
    fun validateHook(body: ParsedExpr, hookName: String, implLabel: String) {
        walk(body, inTail = false, opName = hookName, implLabel = implLabel, inHook = true)
    }

    /** `cont.resume` / `cont.abort` → the display spelling, else null. A QUALIFIED
     *  receiver (`m::cont.abort`) or a user effect op named `abort`
     *  (`Abort.abort {}`, CustomEffectTest) is ordinary code. */
    private fun bindingName(e: ParsedExpr): String? =
        ScopedEffectConstants.contHandle(e)?.let { "${ScopedEffectConstants.CONT}.$it" }

    private fun isBareCont(e: ParsedExpr): Boolean =
        e is ParsedIdentifier && e.qualifier == null && e.localName == ScopedEffectConstants.CONT

    private fun rejectNonTail(name: String, opName: String, implLabel: String, loc: Loc): Nothing =
        throw TypeCheckError(
            "`$name` must be the last thing operation '$opName' in $implLabel does.\n" +
                if (name.endsWith(ScopedEffectConstants.RESUME)) {
                    "`cont.resume` does not transfer control — it hands its value back as the " +
                        "operation's result and the body continues from there. Anything after " +
                        "it in this body runs BEFORE the resumed computation, and a second " +
                        "`cont.resume` simply replaces the first value rather than running the body " +
                        "again. Move the work into the computation that performs the effect, or " +
                        "compute the value first and end with a single `cont.resume` (or just the value)."
                } else {
                    "`cont.abort` ends the whole `perform` block, so anything after it in this body " +
                        "is unreachable. Keep it as the last expression."
                },
            loc,
            code = ErrorCode.V0503_HANDLER_BINDING_NOT_IN_TAIL_POSITION,
        )

    private fun rejectInHook(name: String, hookName: String, implLabel: String, loc: Loc): Nothing =
        throw TypeCheckError(
            "`$name` is not available in '$hookName' in $implLabel.\n" +
                "A scope hook is not an operation — it runs around the `perform` block, " +
                "not in response to a performed one, so there is no operation call to " +
                "hand a value back to. Put `$name` in an operation body; a hook returns " +
                "its value directly (`initialize_scope` the scope, `finalize_scope` " +
                "anything).",
            loc,
            code = ErrorCode.V0503_HANDLER_BINDING_NOT_IN_TAIL_POSITION,
        )

    private fun rejectAsValue(name: String, opName: String, implLabel: String, loc: Loc): Nothing =
        throw TypeCheckError(
            "`$name` can only be called, not used as a value, in operation '$opName' " +
                "in $implLabel.\n" +
                "The runtime holds the continuation; a handler never does. `cont.resume` is " +
                "the identity function, `cont.abort` throws and `cont.park` blocks this task, " +
                "so each is called in place (`cont.resume`/`cont.abort` as the body's last " +
                "expression), never stored or passed on.",
            loc,
            code = ErrorCode.V0503_HANDLER_BINDING_NOT_IN_TAIL_POSITION,
        )

    /**
     * [inTail] is true where the expression's value IS the body's value.
     *
     * Exhaustive over the sealed `ParsedExpr` with no `else`, deliberately: a
     * new expression form should fail to COMPILE here rather than silently
     * become a position this check does not look at. That is the failure shape
     * this whole area kept producing — a check that runs and quietly passes.
     */
    private fun walk(e: ParsedExpr, inTail: Boolean, opName: String, implLabel: String, inHook: Boolean) {
        fun sub(x: ParsedExpr?, tail: Boolean = false) {
            if (x != null) walk(x, tail, opName, implLabel, inHook)
        }
        when (e) {
            // ---- the tail spine: these pass the position through
            is ParsedDo -> {
                for (s in e.statements) when (s) {
                    is ParsedStatement.Let -> sub(s.value)
                    is ParsedStatement.Expr -> sub(s.expr)
                }
                sub(e.result, inTail)
            }
            is ParsedIf -> {
                sub(e.condition)
                sub(e.thenBranch, inTail)
                sub(e.elseBranch, inTail)
            }
            is ParsedMatch -> {
                sub(e.scrutinee)
                for (c in e.cases) {
                    sub(c.guard)
                    for (g in c.altGuards) sub(g)
                    for (b in c.ifLetBindings) sub(b.expr)
                    sub(c.body, inTail)
                }
            }
            is ParsedParens -> sub(e.expr, inTail)

            // ---- the call: the one place the bindings may legitimately appear
            is ParsedFunctionCall -> {
                val name = bindingName(e.function)
                if (name != null) {
                    if (inHook) rejectInHook(name, opName, implLabel, e.loc)
                    if (!inTail) rejectNonTail(name, opName, implLabel, e.loc)
                    // Arguments are not a tail position: `cont.resume { val = cont.resume
                    // { val = 1 } }` is a nested use, and the inner one is not
                    // the last thing the body does.
                    sub(e.arguments)
                } else {
                    sub(e.function)
                    sub(e.arguments)
                }
            }

            // ---- a bare `cont` is a capture attempt: the runtime holds the
            //      continuation, so `cont` is only ever the receiver of a field
            is ParsedIdentifier -> if (isBareCont(e)) {
                if (inHook) rejectInHook(ScopedEffectConstants.CONT, opName, implLabel, e.loc)
                rejectAsValue(ScopedEffectConstants.CONT, opName, implLabel, e.loc)
            }

            // ---- everything else: children are never tail positions
            is ParsedLambda -> sub(e.body)
            is ParsedBinaryOp -> { sub(e.left); sub(e.right) }
            is ParsedPipe -> { sub(e.left); sub(e.right) }
            is ParsedRecordLit -> for (entry in e.entries) when (entry) {
                is ParsedRecordEntry.Field -> sub(entry.value)
                is ParsedRecordEntry.Spread -> sub(entry.expr)
            }
            is ParsedMemberAccess -> when {
                // `cont.resume` / `cont.abort` NOT called: stored or passed on
                bindingName(e) != null -> {
                    val name = bindingName(e)!!
                    if (inHook) rejectInHook(name, opName, implLabel, e.loc)
                    rejectAsValue(name, opName, implLabel, e.loc)
                }
                // `cont.park` — any position; a park is a value-returning call
                isBareCont(e.receiver) ->
                    if (inHook) rejectInHook("${ScopedEffectConstants.CONT}.${e.member}", opName, implLabel, e.loc)
                else -> sub(e.receiver)
            }
            is ParsedVariantConstruct -> sub(e.value)
            is ParsedNewtypeConstruct -> sub(e.value)
            is ParsedSeqLit -> for (el in e.elements) when (el) {
                is ParsedSeqElement.Regular -> sub(el.expr)
                is ParsedSeqElement.Spread -> sub(el.expr)
            }
            is ParsedPerform -> {
                for (h in e.handlers) sub(h.params)
                sub(e.body)
            }
            is ParsedEffectCall -> sub(e.arguments)
            is ParsedStringLit -> for (part in e.parts) {
                if (part is ParsedStringPart.Interpolation) sub(part.expr)
            }
            is ParsedQuote -> { for (s in e.splices) sub(s); sub(e.value) }
            is ParsedMacroCall -> Unit  // expansion is checked where it lands
            is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
            is ParsedCharLit, is ParsedPipePlaceholder -> Unit
        }
    }
}
