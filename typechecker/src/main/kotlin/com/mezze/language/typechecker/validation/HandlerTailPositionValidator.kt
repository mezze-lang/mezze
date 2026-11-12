package com.mezze.language.typechecker.validation

import com.mezze.language.ast.EffectBindingKind
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError

/**
 * `resume` and `abort` may only appear as the LAST thing a performer op body
 * does, and may never be used as a value.
 *
 * The rule exists because of what they ARE at runtime, which is not what they
 * look like (see EffectHandlerDriver / EffectBindingNode):
 *
 *   - `resume { val = X }` is the IDENTITY FUNCTION. It transfers no control.
 *     The body's value becomes the op's result because the handler was called
 *     as an ordinary function at the op-call site.
 *   - `abort { val = X }` THROWS, caught at the enclosing `perform`.
 *
 * So Mezze has tail-resumptive handlers plus an escape continuation — not
 * one-shot continuations. There is no `k` to store, defer, or call twice. Every
 * spelling that assumes otherwise type-checks today and means something the
 * author did not write. MEASURED:
 *
 *     a = {} -> do
 *       resume { val = 1 }
 *       resume { val = 2 }
 *
 * prints `body ran, v = 2` — ONCE. The first call computed 1 as a discarded
 * statement; it did not run the body. In Eff/Koka/OCaml 5 that same handler is
 * multi-shot and the body runs twice. Likewise `resume { val = x }` followed by
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
        walk(body, inTail = true, opName = opName, implLabel = implLabel)
    }

    private fun bindingName(e: ParsedExpr): String? {
        if (e !is ParsedIdentifier) return null
        // A QUALIFIED name is an effect op, not the binding — `Abort.abort {}`
        // on a user effect whose op happens to be called `abort` is ordinary
        // code, and CustomEffectTest has one.
        if (e.qualifier != null) return null
        return if (EffectBindingKind.fromName(e.localName) == EffectBindingKind.RESUME ||
            EffectBindingKind.fromName(e.localName) == EffectBindingKind.ABORT
        ) e.localName else null
    }

    private fun rejectNonTail(name: String, opName: String, implLabel: String, loc: Loc): Nothing =
        throw TypeCheckError(
            "`$name` must be the last thing operation '$opName' in $implLabel does.\n" +
                if (name == ScopedEffectConstants.RESUME) {
                    "`resume` does not transfer control — it hands its value back as the " +
                        "operation's result and the body continues from there. Anything after " +
                        "it in this body runs BEFORE the resumed computation, and a second " +
                        "`resume` simply replaces the first value rather than running the body " +
                        "again. Move the work into the computation that performs the effect, or " +
                        "compute the value first and end with a single `resume`."
                } else {
                    "`abort` ends the whole `perform` block, so anything after it in this body " +
                        "is unreachable. Keep it as the last expression."
                },
            loc,
            code = ErrorCode.V0503_HANDLER_BINDING_NOT_IN_TAIL_POSITION,
        )

    private fun rejectAsValue(name: String, opName: String, implLabel: String, loc: Loc): Nothing =
        throw TypeCheckError(
            "`$name` can only be called, not used as a value, in operation '$opName' " +
                "in $implLabel.\n" +
                "It is not a continuation that can be stored and invoked later: `resume` is " +
                "the identity function and `abort` throws. Call it here, as the body's last " +
                "expression.",
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
    private fun walk(e: ParsedExpr, inTail: Boolean, opName: String, implLabel: String) {
        fun sub(x: ParsedExpr?, tail: Boolean = false) {
            if (x != null) walk(x, tail, opName, implLabel)
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
                    if (!inTail) rejectNonTail(name, opName, implLabel, e.loc)
                    // Arguments are not a tail position: `resume { val = resume
                    // { val = 1 } }` is a nested use, and the inner one is not
                    // the last thing the body does.
                    sub(e.arguments)
                } else {
                    sub(e.function)
                    sub(e.arguments)
                }
            }

            // ---- a bare mention is a capture attempt
            is ParsedIdentifier ->
                bindingName(e)?.let { rejectAsValue(it, opName, implLabel, e.loc) }

            // ---- everything else: children are never tail positions
            is ParsedLambda -> sub(e.body)
            is ParsedBinaryOp -> { sub(e.left); sub(e.right) }
            is ParsedPipe -> { sub(e.left); sub(e.right) }
            is ParsedRecordLit -> for (entry in e.entries) when (entry) {
                is ParsedRecordEntry.Field -> sub(entry.value)
                is ParsedRecordEntry.Spread -> sub(entry.expr)
            }
            is ParsedMemberAccess -> sub(e.receiver)
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
