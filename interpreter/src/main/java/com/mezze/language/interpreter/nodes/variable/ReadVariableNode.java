package com.mezze.language.interpreter.nodes.variable;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeContext;
import com.mezze.language.interpreter.MezzeLanguage;
import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeRecord;
import com.mezze.language.interpreter.MezzeSuspended;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

import java.util.Map;


/**
 * Node for reading variables from the frame.
 * Falls back to context.lookupGlobal() for module-imported functions that don't have frame slots.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "read-var")
public final class ReadVariableNode extends MezzeNode {

    private final int slot;
    private final String variableName;

    public ReadVariableNode(int slot, String variableName) {
        this.slot = slot;
        this.variableName = variableName;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        // First try reading from frame slot
        Object value = frame.getObject(slot);

        // Return the value if it's a valid MezzeValue (but NOT MezzeNull)
        // MezzeNull closure variables can occur with recursive functions
        if (value instanceof MezzeValue && !(value instanceof MezzeNull)) {
            // If the value is a suspended computation, try to execute with handlers
            if (value instanceof MezzeSuspended) {
                MezzeValue executed = tryExecuteSuspended((MezzeSuspended) value);
                return executed != null ? executed : (MezzeValue) value;
            }
            return (MezzeValue) value;
        }

        // If frame slot is null or MezzeNull, fall back to context lookup
        if (value == null || value instanceof MezzeNull) {
            return lookupFromContext();
        }

        // If value is not null but not a MezzeValue, it's an error
        if (value instanceof MezzeValue) {
            return (MezzeValue) value;
        } else {
            return throwInvalidType(value);
        }
    }

    @TruffleBoundary
    private MezzeValue tryExecuteSuspended(MezzeSuspended suspended) {
        MezzeContext context = MezzeLanguage.Companion.getCurrentContext(this);
        MezzeRecord handlers = context.getEffectHandler().getCurrentHandlers();
        if (handlers != null) {
            Map<String, MezzeValue> fields = handlers.getFields();
            return suspended.executeWithBoundary(fields, context);
        }
        return null;
    }

    @TruffleBoundary
    private MezzeValue lookupFromContext() {
        MezzeContext context = MezzeLanguage.Companion.getCurrentContext(this);
        MezzeValue globalValue = context.lookupGlobal(variableName);
        if (globalValue != null) {
            // If the global value is a suspended computation and handlers are available, execute it
            if (globalValue instanceof MezzeSuspended) {
                MezzeRecord handlers = context.getEffectHandler().getCurrentHandlers();
                if (handlers != null) {
                    return ((MezzeSuspended) globalValue).executeWithBoundary(handlers.getFields(), context);
                }
            }
            return globalValue;
        }

        // If monomorphized name not found (e.g., "is_empty$Unknown"), try generic name
        if (variableName.contains("$")) {
            String genericName = variableName.substring(0, variableName.indexOf('$'));
            MezzeValue genericValue = context.lookupGlobal(genericName);
            if (genericValue != null) {
                // If the generic value is a suspended computation and handlers are available, execute it
                if (genericValue instanceof MezzeSuspended) {
                    MezzeRecord handlers = context.getEffectHandler().getCurrentHandlers();
                    if (handlers != null) {
                        return ((MezzeSuspended) genericValue).executeWithBoundary(handlers.getFields(), context);
                    }
                }
                return genericValue;
            }
        }

        // If still not found, throw error
        throw new RuntimeException("Undefined variable '" + variableName + "' at runtime. This should have been caught by the type checker.");
    }

    @TruffleBoundary
    private MezzeValue throwInvalidType(Object value) {
        throw new RuntimeException("Invalid value type in frame slot: " + (value != null ? value.getClass() : "null"));
    }
}
