package com.mezze.interpreter.nodes.continuation;

import com.mezze.language.typedast.Hash;

/**
 * Payload an op call site passes to {@link EffectFiber#suspend(Object)}
 * when invoking an effect operation.
 *
 * <p>The driver reads this from {@link EffectFiber#takeSuspendedValue()},
 * looks up {@code handler.<opName>}, and calls it with
 * {@code {args, resume}}. Whatever the op handler returns becomes the
 * value the fiber's suspend returns when {@link EffectFiber#run()} is
 * called again.
 *
 * <p>The 3-arg constructor is the ONLY constructor by design (#246,
 * #55): a 2-arg convenience form that defaulted {@code effectHash} to
 * {@code null} led to silent misroute when the runtime later fell
 * back to name-only match. Callers that genuinely have no hash must
 * pass {@code null} explicitly so the site is greppable.
 *
 * @param opName     the operation name (e.g. "raise", "yield")
 * @param args       the argument record passed by the call site
 * @param effectHash content hash of the effect declaration this op belongs to.
 *                   Used by the driver to filter the handler chain by
 *                   (effect_hash, op_name) — matching by op-name alone
 *                   silently picks the wrong handler when two effects
 *                   in scope declare an op with the same simple name.
 *                   {@code null} is a legacy escape hatch that degrades
 *                   to "match any effect"; new code MUST pass a real hash.
 */
public record OpCall(String opName, Object args, Hash effectHash) {
}
