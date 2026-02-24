package com.mezze.language.interpreter.nodes.effect

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeTruffleFunction
import com.mezze.language.interpreter.NativeMezzeFunction
import com.mezze.language.interpreter.MezzeLanguage

/**
 * Node that dynamically decides at runtime whether a call is an effect call
 * or a regular function call.
 *
 * For effect calls, it delegates to the effect handler.
 * For regular calls, it looks up the receiver and invokes the operation.
 *
 * All context access, Map operations, and exception throwing are done via
 * @TruffleBoundary to avoid JIT blockers.
 */
@NodeInfo(shortName = "dynamic-effect-or-function-call")
class DynamicEffectOrFunctionCallNode(
    @field:Child private var argsChild: MezzeNode,
    private val receiverName: String,
    private val operationName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val arguments = argsChild.execute(frame)
        return executeCall(arguments)
    }

    @TruffleBoundary
    private fun executeCall(arguments: MezzeValue): MezzeValue {
        val context = MezzeLanguage.getCurrentContext(this)
        val effectHandler = context.effectHandler

        // Check if this is a declared effect
        if (effectHandler.isEffectDeclared(receiverName)) {
            // This is an effect call - use the effect handler
            // Build a mutable context map for the effect handler
            val effectContext = mutableMapOf<String, MezzeValue>()
            effectContext.putAll(context.captureEnvironment())

            // Execute the effect call through the handler
            return effectHandler.executeEffectCall(
                effectName = receiverName,
                operationName = operationName,
                arguments = arguments,
                context = effectContext
            )
        } else {
            // Not an effect - treat as regular member access and function call
            // This is a fallback for non-effect member access like myRecord.myFunction
            val receiver = context.lookupGlobal(receiverName)
                ?: throw RuntimeException("Undefined variable: $receiverName")

            if (receiver !is MezzeRecord) {
                throw RuntimeException("Cannot access member '$operationName' on non-record value: ${receiver::class.simpleName}")
            }

            val operation = receiver.fields[operationName]
                ?: throw RuntimeException("Field '$operationName' not found in record")

            // Handle both Truffle functions and native functions
            return when (operation) {
                is MezzeTruffleFunction -> {
                    // CRITICAL: MezzeFunctionRootNode expects 4 arguments:
                    // [0] = args record, [1] = closure variables map (legacy),
                    // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                    operation.callTarget.call(
                        arguments,
                        operation.closureVariables,
                        operation.lexicalScope,
                        operation.lexicalScopeSlotMap
                    ) as MezzeValue
                }
                is NativeMezzeFunction -> {
                    // Native functions expect the record as the first element in a list
                    operation.call(listOf(arguments))
                }
                else -> {
                    throw RuntimeException("Field '$operationName' is not a function, got: ${operation::class.simpleName}")
                }
            }
        }
    }
}
