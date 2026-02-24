package com.mezze.language.interpreter.nodes.variable

// NOTE: Core variable nodes (ReadVariableNode, WriteVariableNode)
// have been moved to Java for JIT compatibility. See:
// - com.mezze.language.interpreter.nodes.variable.ReadVariableNode (Java)
// - com.mezze.language.interpreter.nodes.variable.WriteVariableNode (Java)

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.MezzeTruffleFunction
import com.mezze.language.interpreter.MezzeSuspended
import com.mezze.language.interpreter.MezzeContext
import com.mezze.language.interpreter.MezzeLanguage

/**
 * Simple variable read node without specialization (for compatibility)
 * Falls back to context.lookupGlobal() for module-imported functions that don't have frame slots
 */
@NodeInfo(shortName = "simple-read-var")
class SimpleReadVariableNode(private val slot: Int, private val variableName: String) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        // First try reading from frame slot
        val value = try {
            frame.getObject(slot)
        } catch (e: ArrayIndexOutOfBoundsException) {
            throwSlotError(e, frame.frameDescriptor.numberOfSlots)
        }

        // Return the value if it's a valid MezzeValue (but NOT MezzeNull)
        // MezzeNull closure variables can occur with recursive functions
        if (value is MezzeValue && value !is MezzeNull) {
            // If the value is a suspended computation, try to execute with handlers
            if (value is MezzeSuspended) {
                return tryExecuteSuspendedBoundary(value) ?: value
            }
            return value
        }

        // If frame slot is null or MezzeNull, fall back to context lookup
        if (value == null || value is MezzeNull) {
            return lookupFromContextBoundary()
        }

        // If value is not null but not a MezzeValue, it's an error
        return if (value is MezzeValue) value else throwInvalidTypeBoundary(value)
    }

    @TruffleBoundary
    private fun throwSlotError(e: ArrayIndexOutOfBoundsException, numSlots: Int): Nothing {
        throw RuntimeException("Cannot access frame slot $slot for variable '$variableName' (frame has $numSlots slots)", e)
    }

    @TruffleBoundary
    private fun tryExecuteSuspendedBoundary(suspended: MezzeSuspended): MezzeValue? {
        val context = MezzeLanguage.getCurrentContext(this)
        val handlers = context.effectHandler.getCurrentHandlers()
        return if (handlers != null) {
            suspended.executeWithBoundary(handlers.fields, context)
        } else {
            null
        }
    }

    @TruffleBoundary
    private fun lookupFromContextBoundary(): MezzeValue {
        val context = MezzeLanguage.getCurrentContext(this)
        val globalValue = context.lookupGlobal(variableName)
        if (globalValue != null) {
            // If the global value is a suspended computation and handlers are available, execute it
            if (globalValue is MezzeSuspended) {
                val handlers = context.effectHandler.getCurrentHandlers()
                if (handlers != null) {
                    return globalValue.executeWithBoundary(handlers.fields, context)
                }
            }
            return globalValue
        }

        // If monomorphized name not found (e.g., "is_empty$Unknown"), try generic name
        if (variableName.contains("$")) {
            val genericName = variableName.substringBefore("$")
            val genericValue = context.lookupGlobal(genericName)
            if (genericValue != null) {
                // If the generic value is a suspended computation and handlers are available, execute it
                if (genericValue is MezzeSuspended) {
                    val handlers = context.effectHandler.getCurrentHandlers()
                    if (handlers != null) {
                        return genericValue.executeWithBoundary(handlers.fields, context)
                    }
                }
                return genericValue
            }
        }

        // If still not found, throw error
        throw RuntimeException("Undefined variable '$variableName' at runtime. This should have been caught by the type checker.")
    }

    @TruffleBoundary
    private fun throwInvalidTypeBoundary(value: Any?): Nothing {
        throw RuntimeException("Invalid value type in frame slot: ${value?.let { it::class }}")
    }
}

/**
 * Simple variable write node without specialization (for compatibility)
 * Writes to both frame slots AND context.locals for module export support
 */
@NodeInfo(shortName = "simple-write-var")
class SimpleWriteVariableNode(
    private val slot: Int,
    @field:Child private var valueNode: MezzeNode,
    private val variableName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = valueNode.execute(frame)
        // Write to frame slot (fast path)
        frame.setObject(slot, value)

        // ALSO write to context.locals so module exports can be extracted
        defineLocalBoundary(value)

        return value
    }

    @TruffleBoundary
    private fun defineLocalBoundary(value: MezzeValue) {
        val context = MezzeLanguage.getCurrentContext(this)
        context.defineLocal(variableName, value)
    }
}

/**
 * Node for binding imported values from use declarations.
 * Looks up a value from the global context and binds it to a local frame slot and context.
 */
@NodeInfo(shortName = "use-binding")
class UseBindingNode(
    private val slot: Int,
    private val qualifiedImportName: String,
    private val localName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Look up value in @TruffleBoundary method, then bind in hot path
        val value = lookupImportedValueBoundary()

        // Bind it in the current frame (this is fast - just frame slot write)
        frame.setObject(slot, value)

        // Define in context (in @TruffleBoundary method)
        defineInContextBoundary(value)

        return MezzeNull
    }

    @TruffleBoundary
    private fun lookupImportedValueBoundary(): MezzeValue {
        val context = MezzeLanguage.getCurrentContext(this)
        return context.lookupGlobal(qualifiedImportName) ?: MezzeNull
    }

    @TruffleBoundary
    private fun defineInContextBoundary(value: MezzeValue) {
        val context = MezzeLanguage.getCurrentContext(this)
        // CRITICAL: Define in both locals AND globals
        // This ensures module imports are accessible to lambdas in impl declarations
        // which execute in different contexts and need global access to imports
        context.defineLocal(localName, value)
        context.defineGlobal(localName, value)
    }
}