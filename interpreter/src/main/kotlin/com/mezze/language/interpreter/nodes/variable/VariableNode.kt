package com.mezze.language.interpreter.nodes.variable

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.MezzeTruffleFunction

/**
 * Node for reading variables from the frame (simplified without DSL)
 * Falls back to context.lookupGlobal() for module-imported functions that don't have frame slots
 */
@NodeInfo(shortName = "read-var")
class ReadVariableNode(private val slot: Int, private val variableName: String) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        // First try reading from frame slot
        val value = frame.getObject(slot)

        // Return the value if it's a valid MezzeValue (but NOT MezzeNull)
        // MezzeNull closure variables can occur with recursive functions
        if (value is MezzeValue && value !is MezzeNull) {
            // If the value is a suspended computation and handlers are available, execute it
            if (value is com.mezze.language.interpreter.MezzeSuspended) {
                val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)
                val handlers = context.effectHandler.getCurrentHandlers()
                if (handlers != null) {
                    return value.executeWith(handlers.fields, context, frame)
                }
            }
            return value
        }

        // If frame slot is null or MezzeNull, fall back to context.lookupGlobal()
        // This handles module-imported functions and recursive functions
        if (value == null || value is MezzeNull) {
            val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)
            val globalValue = context.lookupGlobal(variableName)
            if (globalValue != null) {
                // If the global value is a suspended computation and handlers are available, execute it
                if (globalValue is com.mezze.language.interpreter.MezzeSuspended) {
                    val handlers = context.effectHandler.getCurrentHandlers()
                    if (handlers != null) {
                        return globalValue.executeWith(handlers.fields, context, frame)
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
                    if (genericValue is com.mezze.language.interpreter.MezzeSuspended) {
                        val handlers = context.effectHandler.getCurrentHandlers()
                        if (handlers != null) {
                            return genericValue.executeWith(handlers.fields, context, frame)
                        }
                    }
                    return genericValue
                }
            }

            // If still not found, throw error instead of returning MezzeNull
            // The typechecker should have caught undefined variables
            throw RuntimeException("Undefined variable '$variableName' at runtime. This should have been caught by the type checker.")
        }

        // If value is not null but not a MezzeValue, it's an error
        return if (value is MezzeValue) value else throw RuntimeException("Invalid value type in frame slot: ${value::class}")
    }
}

/**
 * Node for writing variables to the frame (simplified without DSL)
 */
@NodeInfo(shortName = "write-var")
class WriteVariableNode(
    private val slot: Int,
    @field:Child private var valueNode: MezzeNode
) : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = valueNode.execute(frame)
        frame.setObject(slot, value)
        return value
    }
}

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
            throw RuntimeException("Cannot access frame slot $slot for variable '$variableName' (frame has ${frame.frameDescriptor.numberOfSlots} slots)", e)
        }

        // Return the value if it's a valid MezzeValue (but NOT MezzeNull)
        // MezzeNull closure variables can occur with recursive functions where the function
        // references itself before it's defined
        if (value is MezzeValue && value !is MezzeNull) {
            // If the value is a suspended computation and handlers are available, execute it
            if (value is com.mezze.language.interpreter.MezzeSuspended) {
                val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)
                val handlers = context.effectHandler.getCurrentHandlers()
                if (handlers != null) {
                    return value.executeWith(handlers.fields, context, frame)
                }
            }
            return value
        }

        // If frame slot is null or MezzeNull, fall back to context.lookupGlobal()
        // This handles:
        // 1. Module-imported functions and globals that don't have frame slots
        // 2. Recursive functions that reference themselves in their closure
        if (value == null || value is MezzeNull) {
            val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)
            val globalValue = context.lookupGlobal(variableName)
            if (globalValue != null) {
                // If the global value is a suspended computation and handlers are available, execute it
                if (globalValue is com.mezze.language.interpreter.MezzeSuspended) {
                    val handlers = context.effectHandler.getCurrentHandlers()
                    if (handlers != null) {
                        return globalValue.executeWith(handlers.fields, context, frame)
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
                    if (genericValue is com.mezze.language.interpreter.MezzeSuspended) {
                        val handlers = context.effectHandler.getCurrentHandlers()
                        if (handlers != null) {
                            return genericValue.executeWith(handlers.fields, context, frame)
                        }
                    }
                    return genericValue
                }
            }

            // If still not found, throw error instead of returning MezzeNull
            // The typechecker should have caught undefined variables
            throw RuntimeException("Undefined variable '$variableName' at runtime. This should have been caught by the type checker.")
        }

        // If value is not null but not a MezzeValue, it's an error
        return if (value is MezzeValue) value else throw RuntimeException("Invalid value type in frame slot: ${value::class}")
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
        // Write to frame slot
        frame.setObject(slot, value)

        // ALSO write to context.locals so module exports can be extracted
        val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)
        context.defineLocal(variableName, value)

        return value
    }
}