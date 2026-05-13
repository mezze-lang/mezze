package com.mezze.interpreter.nodes.continuation;

/**
 * Marker wrapper the effect-handler driver uses to stash body's natural
 * return value so it can be distinguished from {@link OpCall} suspends
 * via {@link EffectFiber#takeSuspendedValue()}.
 *
 * <p>If a fiber's body completes without ever calling
 * {@link EffectFiber#suspend(Object) suspend}, the runner sees
 * {@link EffectFiber#isDone()} as true and reads {@code null} from
 * {@code takeSuspendedValue()}. To make completion uniform with suspends,
 * the driver wraps body's last expression in a {@code BodyResult} and
 * stashes it via {@link EffectFiber#setSuspendedValue(Object)} just
 * before returning from the body.
 */
public record BodyResult(Object value) {}
