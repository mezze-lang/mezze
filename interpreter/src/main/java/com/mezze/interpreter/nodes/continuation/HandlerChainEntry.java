package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;

/**
 * One handler installed on {@link EffectFiber}'s per-thread handler chain,
 * tagged with the content hash of the effect declaration it implements.
 *
 * <p>The effect hash is essential for correct dispatch when two effects in
 * scope declare an op with the same simple name (#246). Without it, the
 * dispatcher walks the chain looking for any handler whose record carries
 * a field with the requested op name — and silently picks the wrong one
 * if two handlers both happen to have that field, even though they
 * implement different effects.
 *
 * <p>{@code effectHash} may be {@code null} only for legacy/synthetic
 * handlers that predate the runId-tag rollout; the dispatcher treats
 * null as "match any effect" for backward compatibility.
 *
 * @param handler    the impl record with fields op_name → MezzeFunction
 * @param effectHash content hash of the effect this handler implements
 */
public record HandlerChainEntry(MezzeRecord handler, Hash effectHash) {
}
