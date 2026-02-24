package com.mezze.language.interpreter.nodes.impl

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeLanguage

/**
 * Node that stores a parameterized impl definition in frame slots and context.
 * Also registers the performer with the effect handler.
 *
 * All context access and performer registration is done via @TruffleBoundary
 * to avoid triggering JIT blockers from CopyOnWriteArrayList operations.
 */
@NodeInfo(shortName = "parameterized-impl-storage")
class ParameterizedImplStorageNode(
    @field:Child private var implDefChild: MezzeNode,
    private val hashSlot: Int,
    private val performerSlot: Int,
    private val contentHash: String,
    private val performerName: String,
    private val effectName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = implDefChild.execute(frame)

        // Store in frame slots (fast path - no boundary needed)
        frame.setObject(hashSlot, value)
        frame.setObject(performerSlot, value)

        // Store in context and register performer via TruffleBoundary
        storeInContextAndRegister(value)

        return value
    }

    /**
     * Store the impl value in context and register the performer.
     * Marked as @TruffleBoundary because context access and CopyOnWriteArrayList
     * operations trigger equals() checks that are on the JIT blocklist.
     */
    @TruffleBoundary
    private fun storeInContextAndRegister(value: MezzeValue) {
        val context = MezzeLanguage.getCurrentContext(this)
        context.defineGlobal(contentHash, value)
        context.defineLocal(performerName, value)
        context.effectHandler?.registerPerformer(performerName, effectName)
    }
}

/**
 * Node that stores an effect impl record in frame slots and context.
 * Also registers the performer with the effect handler.
 *
 * This is for non-parameterized impls like: impl Log for Console where { ... }
 *
 * All context access and performer registration is done via @TruffleBoundary
 * to avoid triggering JIT blockers from Map operations.
 */
@NodeInfo(shortName = "effect-impl-storage")
class EffectImplStorageNode(
    @field:Child private var recordChild: MezzeNode,
    private val hashSlot: Int,
    private val performerSlot: Int,
    private val contentHash: String,
    private val performerName: String,
    private val effectName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = recordChild.execute(frame)

        // Store in frame slots (fast path - no boundary needed)
        frame.setObject(hashSlot, value)
        frame.setObject(performerSlot, value)

        // Store in context and register performer via TruffleBoundary
        storeInContextAndRegister(value)

        return value
    }

    /**
     * Store the impl value in context and register the performer.
     * Marked as @TruffleBoundary because context access and CopyOnWriteArrayList
     * operations trigger equals() checks that are on the JIT blocklist.
     */
    @TruffleBoundary
    private fun storeInContextAndRegister(value: MezzeValue) {
        val context = MezzeLanguage.getCurrentContext(this)
        context.defineGlobal(contentHash, value)
        context.defineLocal(performerName, value)
        context.effectHandler?.registerPerformer(performerName, effectName)
    }
}

/**
 * Node that stores an ability impl record in frame slots and context.
 *
 * All context access is done via @TruffleBoundary to avoid triggering
 * JIT blockers from Map operations.
 */
@NodeInfo(shortName = "ability-impl-storage")
class AbilityImplStorageNode(
    @field:Child private var recordChild: MezzeNode,
    private val hashSlot: Int,
    private val contentHash: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = recordChild.execute(frame)

        // Store in frame slot (fast path - no boundary needed)
        frame.setObject(hashSlot, value)

        // Store in context via TruffleBoundary
        storeInContext(value)

        return value
    }

    /**
     * Store the impl value in context.
     * Marked as @TruffleBoundary because context access triggers
     * Map operations that are on the JIT blocklist.
     */
    @TruffleBoundary
    private fun storeInContext(value: MezzeValue) {
        val context = MezzeLanguage.getCurrentContext(this)
        context.defineGlobal(contentHash, value)
    }
}
