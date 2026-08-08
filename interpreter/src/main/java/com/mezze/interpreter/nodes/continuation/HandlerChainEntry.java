package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;

/**
 * One handler installed on {@link HandlerChainRegistry}'s per-thread handler chain,
 * tagged with the content hash of the effect declaration it implements.
 *
 * <p>The effect hash is essential for correct dispatch when two effects in
 * scope declare an op with the same simple name (#246). Without it, the
 * dispatcher walks the chain looking for any handler whose record carries
 * a field with the requested op name — and silently picks the wrong one
 * if two handlers both happen to have that field, even though they
 * implement different effects.
 *
 * <p>{@code effectHash} must be non-null in every production caller:
 * task #55 tightened the dispatcher so a null hash on either side is
 * treated as <em>no match</em> and emits a one-shot
 * {@code [chain-dispatch WARN #246/#55]} naming the leak site. Any
 * warning at runtime means a new dispatch path forgot to thread the
 * hash — fix the caller, don't loosen the filter.
 *
 * @param handler    the impl record with fields op_name → MezzeFunction
 * @param effectHash content hash of the effect this handler implements
 */
public record HandlerChainEntry(MezzeRecord handler, Hash effectHash) {
}
