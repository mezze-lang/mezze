package com.mezze.language.interpreter.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeRecord;
import com.mezze.language.interpreter.MezzeValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root node for partially applied functions.
 * This node captures the provided parameters and waits for the remaining parameters.
 * Written in Java to avoid Kotlin intrinsics that trigger JIT blockers.
 */
public class PartiallyAppliedFunctionRootNode extends RootNode {

    private final CallTarget originalCallTarget;
    private final String originalFunctionName;
    private final Map<String, MezzeValue> providedParams;
    private final List<String> missingParams;
    private final Map<String, MezzeValue> originalClosureVars;
    private final MaterializedFrame lexicalScope;
    private final Map<String, Integer> lexicalScopeSlotMap;

    public PartiallyAppliedFunctionRootNode(
            CallTarget originalCallTarget,
            String originalFunctionName,
            Map<String, MezzeValue> providedParams,
            List<String> missingParams,
            Map<String, MezzeValue> originalClosureVars,
            MaterializedFrame lexicalScope,
            Map<String, Integer> lexicalScopeSlotMap
    ) {
        super(null, new FrameDescriptor());
        this.originalCallTarget = originalCallTarget;
        this.originalFunctionName = originalFunctionName;
        this.providedParams = providedParams;
        this.missingParams = missingParams;
        this.originalClosureVars = originalClosureVars;
        this.lexicalScope = lexicalScope;
        this.lexicalScopeSlotMap = lexicalScopeSlotMap;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Get the new arguments provided to this partially applied function
        Object[] arguments = frame.getArguments();
        MezzeRecord newArgsRecord = null;
        if (arguments.length > 0 && arguments[0] instanceof MezzeRecord) {
            newArgsRecord = (MezzeRecord) arguments[0];
        }

        // Merge parameters and call original function - delegate to TruffleBoundary method
        return executeWithMergedParams(newArgsRecord);
    }

    /**
     * Merge parameters and call the original function.
     */
    @TruffleBoundary
    private MezzeValue executeWithMergedParams(MezzeRecord newArgsRecord) {
        // Merge the previously provided parameters with the new ones
        Map<String, MezzeValue> mergedParams = new HashMap<>(providedParams);

        if (newArgsRecord != null) {
            Map<String, MezzeValue> fields = newArgsRecord.getFields();
            for (String paramName : missingParams) {
                MezzeValue value = fields.get(paramName);
                if (value != null && !(value instanceof MezzeNull)) {
                    mergedParams.put(paramName, value);
                }
            }
        }

        // Create a record with all parameters and call the original function
        MezzeRecord allParamsRecord = MezzeRecord.invoke(mergedParams);

        // Call the original function with all parameters
        // MezzeFunctionRootNode expects 4 arguments: params, closureVars, lexicalScope, slot map
        Object result = originalCallTarget.call(
                allParamsRecord,
                originalClosureVars,
                lexicalScope,
                lexicalScopeSlotMap
        );

        return (MezzeValue) result;
    }

    @Override
    public String getName() {
        return originalFunctionName + "(partial)";
    }

    @Override
    public String toString() {
        return originalFunctionName + "(partial)";
    }
}
