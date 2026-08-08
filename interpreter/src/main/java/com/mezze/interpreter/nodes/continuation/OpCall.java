package com.mezze.interpreter.nodes.continuation;

import com.mezze.language.typedast.Hash;

/**
 * Payload an op call site passes to {@link HandlerChainRegistry#suspend(Object)}
 * when invoking an effect operation.
 *
 * <p>The driver reads this from {@link HandlerChainRegistry#takeSuspendedValue()},
 * looks up {@code handler.<opName>}, and calls it with
 * {@code {args, resume}}. Whatever the op handler returns becomes the
 * value the fiber's suspend returns when {@link HandlerChainRegistry#run()} is
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
 *                   Must be non-null in every production caller; the
 *                   dispatcher treats {@code null} as <em>no match</em>
 *                   and emits {@code [chain-dispatch WARN #246/#55]} at
 *                   the leak site (task #55).
 */
public record OpCall(String opName, Object args, Hash effectHash) {
}
