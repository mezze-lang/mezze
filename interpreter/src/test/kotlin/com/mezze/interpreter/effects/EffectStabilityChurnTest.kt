package com.mezze.interpreter.effects

import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An effect whose handler is pushed and popped over and over stops offering a
 * stability assumption.
 *
 * Cached effect dispatch is guarded by a per-effect assumption that every push
 * and pop of that effect's handler invalidates. Before this, each invalidation
 * handed out a fresh assumption, the site re-cached against it, and code that
 * installed a handler per call (`perform Console in …` in a recursive function)
 * recompiled and was thrown away on every call, along with everything else
 * compiled into the same root. A fused list fold beside it ran ~10x slower.
 */
class EffectStabilityChurnTest {

    /** Unique per test, so no real effect and no other test shares the state. */
    private fun freshEffect(seed: Char) = Hash(seed.toString().repeat(64))

    private fun pushPop(effect: Hash) {
        HandlerChainRegistry.pushSimple(null, effect)
        HandlerChainRegistry.popSimple()
    }

    @Test
    fun `an effect churned past the limit hands out an assumption that is never valid`() {
        val effect = freshEffect('9')
        repeat(50) {
            HandlerChainRegistry.getEffectStableAssumption(effect) // a site caches against it
            pushPop(effect)                                         // and it is invalidated
        }
        assertFalse(HandlerChainRegistry.getEffectStableAssumption(effect).isValid,
            "a site re-caching after every push/pop recompiles forever")
    }

    @Test
    fun `an effect pushed and popped with nothing cached against it stays stable`() {
        val effect = freshEffect('8')
        repeat(50) { pushPop(effect) }
        assertTrue(HandlerChainRegistry.getEffectStableAssumption(effect).isValid,
            "churn counts only assumptions some site was holding")
    }

    @Test
    fun `a push still invalidates the assumption a site is holding`() {
        val effect = freshEffect('7')
        val held = HandlerChainRegistry.getEffectStableAssumption(effect)
        pushPop(effect)
        assertFalse(held.isValid, "a cached handler must not survive a change of handler")
    }
}
