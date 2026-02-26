package com.mezze.language.interpreter.nodes.effect

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeLanguage
import com.mezze.language.interpreter.NativeMezzeFunction
import com.mezze.language.interpreter.MezzeTruffleFunction
import com.mezze.language.interpreter.MezzeSuspended
import com.mezze.language.interpreter.MezzeContinuationSuspended
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.interpreter.MezzeArrayList
import com.mezze.language.interpreter.stdlib.effects.ForkedExecutionException

/**
 * Dynamic perform expression node that looks up effect handlers at runtime.
 *
 * All Map lookups and context operations are done via @TruffleBoundary methods
 * to avoid triggering JIT blockers.
 */
@NodeInfo(shortName = "dynamic-perform")
class DynamicPerformNode(
    @field:Child private var exprChild: MezzeNode,
    @field:Children private val handlerParamNodeArray: Array<MezzeNode?>,
    private val handlerNames: Array<String>,
    private val useResolvedHandlers: Boolean,
    // For resolved handlers mode
    private val resolvedEffectNames: Array<String>,
    private val resolvedPerformerNames: Array<String>,
    private val resolvedImplHashes: Array<String>,
    // For mapping mode
    private val capturedMapping: Map<String, Pair<String, String>>,
    private val capturedHashSlots: Map<String, Int>
) : MezzeNode() {

    @CompilationFinal
    private val numHandlers: Int = handlerNames.size

    // Pre-compute whether we have an STM handler for transaction commit
    @CompilationFinal
    private val hasSTMHandler: Boolean = if (useResolvedHandlers) {
        resolvedEffectNames.any { it.contains("STM") }
    } else {
        handlerNames.any { it.contains("STM") }
    }

    // Pre-compute arrays for hash slot lookups
    @CompilationFinal(dimensions = 1)
    private val resolvedHashSlots: IntArray = if (useResolvedHandlers) {
        resolvedImplHashes.map { hash -> capturedHashSlots[hash] ?: -1 }.toIntArray()
    } else {
        IntArray(0)
    }

    @CompilationFinal(dimensions = 1)
    private val mappedHashSlots: IntArray = if (!useResolvedHandlers) {
        handlerNames.map { name ->
            val mapping = capturedMapping[name]
            if (mapping != null) capturedHashSlots[mapping.second] ?: -1 else -1
        }.toIntArray()
    } else {
        IntArray(0)
    }

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Execute handler parameter nodes and collect values in the hot path
        val paramValues = Array<MezzeValue?>(numHandlers) { index ->
            handlerParamNodeArray[index]?.execute(frame)
        }

        // Read frame slot values in the hot path (before TruffleBoundary)
        val slotValues = if (useResolvedHandlers) {
            Array<MezzeValue?>(numHandlers) { index ->
                val slot = resolvedHashSlots[index]
                if (slot >= 0) {
                    try { frame.getValue(slot) as? MezzeValue } catch (e: Exception) { null }
                } else null
            }
        } else {
            Array<MezzeValue?>(numHandlers) { index ->
                val slot = mappedHashSlots[index]
                if (slot >= 0) {
                    try { frame.getValue(slot) as? MezzeValue } catch (e: Exception) { null }
                } else null
            }
        }

        // Build handlers, push onto stack, execute expression
        val runtimeHandlers = buildRuntimeHandlers(paramValues, slotValues)

        // Execute with handlers
        return executeWithHandlers(runtimeHandlers, frame)
    }

    /**
     * Build the runtime handlers map.
     * Marked as @TruffleBoundary because this involves Map operations and context access.
     */
    @TruffleBoundary
    private fun buildRuntimeHandlers(
        paramValues: Array<MezzeValue?>,
        slotValues: Array<MezzeValue?>
    ): MutableMap<String, MezzeValue> {
        val context = MezzeLanguage.getCurrentContext(this)
        val runtimeHandlers = mutableMapOf<String, MezzeValue>()

        if (useResolvedHandlers) {
            for (i in 0 until numHandlers) {
                val effectName = resolvedEffectNames[i]
                val performerName = resolvedPerformerNames[i]
                val implHash = resolvedImplHashes[i]

                // Try slot value first, then context lookup
                var performerValue: MezzeValue = slotValues[i]
                    ?: context.lookupGlobal(implHash)
                    ?: throw RuntimeException(
                        "Handler implementation not found for hash: $implHash (effect: $effectName, performer: $performerName)"
                    )

                // Handle parameterized handlers
                val paramsValue = paramValues[i]
                if (paramsValue != null) {
                    performerValue = callParameterizedPerformer(performerValue, paramsValue, context)
                }

                runtimeHandlers[effectName] = performerValue
            }
        } else {
            for (i in 0 until numHandlers) {
                val handlerName = handlerNames[i]

                val mapping = capturedMapping[handlerName]
                    ?: throw RuntimeException(
                        "Handler '$handlerName' not found in effect mapping. Available handlers: ${capturedMapping.keys}"
                    )

                val (effectName, implHash) = mapping

                // Try slot value first, then context lookup
                var performerValue: MezzeValue = slotValues[i]
                    ?: context.lookupGlobal(implHash)
                    ?: throw RuntimeException("Handler implementation not found for hash: $implHash (handler: $handlerName)")

                // Handle parameterized handlers
                val paramsValue = paramValues[i]
                if (paramsValue != null) {
                    performerValue = callParameterizedPerformer(performerValue, paramsValue, context)
                }

                runtimeHandlers[effectName] = performerValue
            }
        }

        return runtimeHandlers
    }

    /**
     * Execute the expression with handlers pushed onto the stack.
     * NOTE: This method is NOT @TruffleBoundary because it needs to execute the child node
     * and call methods that require VirtualFrame.
     *
     * The handlers are managed via @TruffleBoundary methods to keep collection operations
     * out of the JIT compilation path.
     */
    private fun executeWithHandlers(runtimeHandlers: MutableMap<String, MezzeValue>, frame: VirtualFrame): MezzeValue {
        // Get context and push handlers in boundary method
        val effectHandler = pushHandlersAndGetHandler(runtimeHandlers)

        val result: MezzeValue
        var forkedValues: MezzeArrayList? = null

        try {
            result = exprChild.execute(frame)
        } catch (e: ForkedExecutionException) {
            forkedValues = MezzeArrayList(e.values)
            // Pop handlers before returning
            popHandlersBoundary(effectHandler)
            return forkedValues
        }

        // Pop handlers after execution (in boundary method)
        popHandlersBoundary(effectHandler)

        // Handle MezzeContinuationSuspended (for early return)
        if (result is MezzeContinuationSuspended) {
            return handleContinuationSuspended(result, runtimeHandlers)
        }

        // LAZY EFFECTS MODEL: Check if result is MezzeSuspended
        if (result is MezzeSuspended) {
            // Execute suspended computation - delegates to boundary method
            return executeSuspendedWithHandlers(result, runtimeHandlers)
        }

        // EXCEPTION EFFECT: Wrap successful results in Ok for ToResult handler
        return wrapForExceptionEffect(result, runtimeHandlers)
    }

    /**
     * Push handlers and return the effect handler.
     * Marked @TruffleBoundary to exclude context access and collection operations.
     */
    @TruffleBoundary
    private fun pushHandlersAndGetHandler(runtimeHandlers: MutableMap<String, MezzeValue>): com.mezze.language.interpreter.EffectHandler {
        val context = MezzeLanguage.getCurrentContext(this)
        val effectHandler = context.effectHandler
        mergeAndPushHandlers(effectHandler, runtimeHandlers)
        return effectHandler
    }

    /**
     * Pop handlers from the stack and commit any pending STM transactions.
     * Marked @TruffleBoundary to exclude collection operations.
     */
    @TruffleBoundary
    private fun popHandlersBoundary(effectHandler: com.mezze.language.interpreter.EffectHandler) {
        // Commit STM transaction when the perform block ends
        // Check if any of the handlers are STM-related
        if (hasSTMHandler) {
            com.mezze.language.interpreter.stdlib.concurrent.STMImplementations.commitAndClearTransaction()
        }
        effectHandler.popHandlers()
    }

    /**
     * Execute suspended computation with handlers.
     * Marked @TruffleBoundary because it involves Map operations and context access.
     */
    @TruffleBoundary
    private fun executeSuspendedWithHandlers(
        suspended: MezzeSuspended,
        runtimeHandlers: MutableMap<String, MezzeValue>
    ): MezzeValue {
        val context = MezzeLanguage.getCurrentContext(this)
        // Use executeWithBoundary which doesn't require VirtualFrame
        return suspended.executeWithBoundary(runtimeHandlers, context)
    }

    /**
     * Merge with existing handlers and push onto stack.
     */
    @TruffleBoundary
    private fun mergeAndPushHandlers(
        effectHandler: com.mezze.language.interpreter.EffectHandler,
        runtimeHandlers: MutableMap<String, MezzeValue>
    ) {
        val existingHandlers = effectHandler.getCurrentHandlers()
        val mergedHandlers = if (existingHandlers != null) {
            existingHandlers.fields.toMutableMap().apply {
                putAll(runtimeHandlers)
            }
        } else {
            runtimeHandlers
        }

        val handlersRecord = MezzeRecord(mergedHandlers)
        effectHandler.pushHandlers(handlersRecord)
    }

    /**
     * Handle MezzeContinuationSuspended result.
     */
    @TruffleBoundary
    private fun handleContinuationSuspended(
        result: MezzeContinuationSuspended,
        runtimeHandlers: Map<String, MezzeValue>
    ): MezzeValue {
        if (runtimeHandlers.containsKey("Return")) {
            return result.suspendedValue
        }
        if (runtimeHandlers.containsKey("Exception")) {
            return result.suspendedValue
        }
        return result
    }

    /**
     * Wrap result for Exception effect.
     */
    @TruffleBoundary
    private fun wrapForExceptionEffect(
        result: MezzeValue,
        runtimeHandlers: Map<String, MezzeValue>
    ): MezzeValue {
        if (runtimeHandlers.containsKey("Exception")) {
            return MezzeVariant(
                "Ok",
                MezzeRecord(mapOf("val" to result))
            )
        }
        return result
    }

    /**
     * Call a parameterized performer function with the given parameters.
     */
    private fun callParameterizedPerformer(
        performerValue: MezzeValue,
        paramsValue: MezzeValue,
        context: com.mezze.language.interpreter.MezzeContext
    ): MezzeValue {
        if (paramsValue !is MezzeRecord) {
            throw RuntimeException("Handler parameters must be a record, got: ${paramsValue::class.simpleName}")
        }

        return when (performerValue) {
            is NativeMezzeFunction -> {
                performerValue.call(listOf(paramsValue))
            }
            is MezzeTruffleFunction -> {
                performerValue.callTarget.call(
                    paramsValue,
                    performerValue.closureVariables,
                    performerValue.lexicalScope,
                    performerValue.lexicalScopeSlotMap
                ) as MezzeValue
            }
            else -> {
                // Fallback: Old behavior for non-parameterized handlers
                for ((paramName, paramValue) in paramsValue.fields) {
                    context.defineLocal(paramName, paramValue)
                }
                performerValue
            }
        }
    }
}
