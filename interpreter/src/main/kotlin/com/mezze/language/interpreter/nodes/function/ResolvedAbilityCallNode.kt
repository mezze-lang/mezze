package com.mezze.language.interpreter.nodes.function

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.MezzeException
import com.mezze.language.interpreter.MezzeLanguage
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeTruffleFunction
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.nodes.MezzeNode

/**
 * Node for calling a resolved ability operation.
 *
 * This node looks up an ability implementation by content hash,
 * extracts the operation function, and calls it with the provided arguments.
 *
 * All Map operations (field lookups) are performed via @TruffleBoundary methods
 * to avoid JIT compilation issues with hashCode/equals on Truffle objects.
 */
@NodeInfo(shortName = "resolved-ability-call")
class ResolvedAbilityCallNode(
    @field:Child private var argumentsNode: MezzeNode,
    private val localSlot: Int?,
    private val implHashRequired: String,
    private val operationName: String,
    private val abilityName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Look up the implementation record (may be local slot or context lookup)
        val implRecord: MezzeValue = if (localSlot != null) {
            frame.getValue(localSlot) as? MezzeValue
                ?: throw MezzeException("Null value in implementation slot for hash: $implHashRequired")
        } else {
            // Cross-module: look up in context by hash (via TruffleBoundary)
            lookupImplInContext()
        }

        // Validate and extract operation (via TruffleBoundary)
        val operation = extractOperation(implRecord)

        // Execute arguments
        val args = argumentsNode.execute(frame)

        // Call the operation (via TruffleBoundary)
        return callOperation(operation, args)
    }

    /**
     * Look up the implementation record in the context.
     * Marked as @TruffleBoundary because context lookup uses Map operations.
     */
    @TruffleBoundary
    private fun lookupImplInContext(): MezzeValue {
        val runtimeContext = MezzeLanguage.getCurrentContext(this)
        return runtimeContext.lookupGlobal(implHashRequired)
            ?: throw MezzeException("Cannot find ability implementation for hash: $implHashRequired (ability: $abilityName)")
    }

    /**
     * Extract the operation function from the implementation record.
     * Marked as @TruffleBoundary because MezzeRecord.fields is a Map.
     */
    @TruffleBoundary
    private fun extractOperation(implRecord: MezzeValue): MezzeTruffleFunction {
        if (implRecord is MezzeNull) {
            throw MezzeException("Cannot find ability implementation for hash: $implHashRequired")
        }

        if (implRecord !is MezzeRecord) {
            throw MezzeException("Ability implementation must be a record, got: ${implRecord::class.simpleName}")
        }

        val operation = implRecord.fields[operationName]
        if (operation == null || operation is MezzeNull) {
            throw MezzeException("Cannot find operation '$operationName' in ability implementation (hash: $implHashRequired)")
        }

        if (operation !is MezzeTruffleFunction) {
            throw MezzeException("Ability operation must be a function, got: ${operation::class.simpleName}")
        }

        return operation
    }

    /**
     * Call the operation with the provided arguments.
     * Marked as @TruffleBoundary because callTarget.call may trigger JIT compilation issues.
     */
    @TruffleBoundary
    private fun callOperation(operation: MezzeTruffleFunction, args: MezzeValue): MezzeValue {
        return operation.callTarget.call(
            args,
            operation.closureVariables,
            operation.lexicalScope,
            operation.lexicalScopeSlotMap
        ) as MezzeValue
    }
}
