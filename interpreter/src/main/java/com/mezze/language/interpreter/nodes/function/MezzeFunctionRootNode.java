package com.mezze.language.interpreter.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.RootNode;

import com.mezze.language.interpreter.MezzeContext;
import com.mezze.language.interpreter.MezzeLanguage;
import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeRecord;
import com.mezze.language.interpreter.MezzeTruffleFunction;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root node for Mezze functions - written in Java to avoid Kotlin intrinsics
 * that trigger JIT compilation blockers in GraalVM native image.
 *
 * TRUFFLE OPTIMIZATION NOTES:
 * - parameterNamesArray and closureVarNamesArray are marked @CompilationFinal(dimensions = 1)
 *   so Graal can constant-fold iterations over them during JIT compilation
 * - closureVarSlotIndices pre-computes slot indices to avoid Map lookups in hot paths
 * - All parameter/closure binding loops use while() for @ExplodeLoop compatibility
 */
public class MezzeFunctionRootNode extends RootNode {

    private final String name;

    @Child
    private MezzeNode bodyNode;

    @CompilationFinal(dimensions = 1)
    private final String[] parameterNamesArray;

    @CompilationFinal(dimensions = 1)
    private final String[] closureVarNamesArray;

    @CompilationFinal(dimensions = 1)
    private final int[] closureVarSlotIndices;

    @CompilationFinal
    private final boolean hasParameters;

    @CompilationFinal
    private final boolean hasClosureVariables;

    @CompilationFinal
    private final int paramCount;

    @CompilationFinal
    private final int closureVarCount;

    public MezzeFunctionRootNode(
            MezzeLanguage language,
            String name,
            List<String> parameterNames,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor,
            List<String> closureVariableNames,
            Map<String, Integer> closureVariableSlots
    ) {
        super(language, frameDescriptor);
        this.name = name;
        this.bodyNode = bodyNode;

        // Convert to arrays for @CompilationFinal annotation
        this.parameterNamesArray = parameterNames.toArray(new String[0]);
        this.closureVarNamesArray = closureVariableNames.toArray(new String[0]);

        // Pre-compute closure variable slot indices
        this.closureVarSlotIndices = new int[closureVariableNames.size()];
        for (int i = 0; i < closureVariableNames.size(); i++) {
            Integer slot = closureVariableSlots.get(closureVariableNames.get(i));
            this.closureVarSlotIndices[i] = (slot != null) ? slot : -1;
        }

        // Track sizes for fast path checks
        this.hasParameters = !parameterNames.isEmpty();
        this.hasClosureVariables = !closureVariableNames.isEmpty();
        this.paramCount = parameterNames.size();
        this.closureVarCount = closureVariableNames.size();
    }

    // Constructor with default empty values for closures
    public MezzeFunctionRootNode(
            MezzeLanguage language,
            String name,
            List<String> parameterNames,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor
    ) {
        this(language, name, parameterNames, bodyNode, frameDescriptor,
             Collections.emptyList(), Collections.emptyMap());
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Arguments: [0] = MezzeRecord of parameters, [1] = closure vars map, [2] = lexicalScope, [3] = slot map
        Object[] arguments = frame.getArguments();

        // Propagate effect handlers (outside hot path)
        propagateHandlers();

        // Fast path: zero-parameter functions with no closures
        if (!hasParameters && !hasClosureVariables) {
            return bodyNode.execute(frame);
        }

        // Extract closure variables map from argument [1]
        Map<String, MezzeValue> closureVariables = extractClosureVariables(arguments);

        // Extract arguments and check for partial application
        MezzeRecord argsRecord = null;
        if (arguments.length > 0 && arguments[0] instanceof MezzeRecord) {
            argsRecord = (MezzeRecord) arguments[0];
        }

        // Fast path for functions with all parameters provided
        if (hasParameters) {
            if (argsRecord != null) {
                // Check if all parameters are provided
                String missingParam = findMissingParameter(argsRecord);
                if (missingParam != null) {
                    // Partial application needed - delegate to boundary method
                    return handlePartialApplication(arguments, argsRecord, closureVariables);
                }
                // All params present - extract values via TruffleBoundary, bind in hot path
                MezzeValue[] paramValues = extractParameterValues(argsRecord);
                int i = 0;
                while (i < paramCount) {
                    frame.setObject(i, paramValues[i]);
                    i++;
                }
            } else {
                // No args provided at all - return partial function
                return handlePartialApplication(arguments, null, closureVariables);
            }
        }

        // Bind closure variables - extract via TruffleBoundary, bind in hot path
        if (hasClosureVariables) {
            MezzeValue[] closureValues = extractClosureVariableValues(closureVariables);
            int i = 0;
            while (i < closureVarCount) {
                frame.setObject(paramCount + i, closureValues[i]);
                i++;
            }
        }

        return bodyNode.execute(frame);
    }

    /**
     * Propagate effect handlers to this function's execution context.
     */
    @TruffleBoundary
    private void propagateHandlers() {
        MezzeContext context = MezzeLanguage.Companion.getCurrentContext(this);
        MezzeRecord activeHandlers = context.getEffectHandler().getCurrentHandlers();
        if (activeHandlers != null) {
            context.defineLocal("__handlers", activeHandlers);
        }
    }

    /**
     * Extract closure variables from arguments.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    private Map<String, MezzeValue> extractClosureVariables(Object[] arguments) {
        if (arguments.length > 1 && arguments[1] instanceof Map) {
            return (Map<String, MezzeValue>) arguments[1];
        }
        return Collections.emptyMap();
    }

    /**
     * Find the first missing parameter, or null if all are provided.
     */
    @TruffleBoundary
    private String findMissingParameter(MezzeRecord argsRecord) {
        Map<String, MezzeValue> fields = argsRecord.getFields();
        for (String paramName : parameterNamesArray) {
            MezzeValue value = fields.get(paramName);
            if (value == null || value instanceof MezzeNull) {
                return paramName;
            }
        }
        return null;
    }

    /**
     * Extract parameter values from args record into an array.
     */
    @TruffleBoundary
    private MezzeValue[] extractParameterValues(MezzeRecord argsRecord) {
        Map<String, MezzeValue> fields = argsRecord.getFields();
        MezzeValue[] result = new MezzeValue[parameterNamesArray.length];
        for (int i = 0; i < parameterNamesArray.length; i++) {
            MezzeValue value = fields.get(parameterNamesArray[i]);
            result[i] = (value != null) ? value : MezzeNull.INSTANCE;
        }
        return result;
    }

    /**
     * Extract closure variable values into an array.
     */
    @TruffleBoundary
    private MezzeValue[] extractClosureVariableValues(Map<String, MezzeValue> closureVariables) {
        MezzeValue[] result = new MezzeValue[closureVarNamesArray.length];
        for (int i = 0; i < closureVarNamesArray.length; i++) {
            MezzeValue value = closureVariables.get(closureVarNamesArray[i]);
            result[i] = (value != null) ? value : MezzeNull.INSTANCE;
        }
        return result;
    }

    /**
     * Handle partial application - creates a new function with some parameters filled in.
     */
    @TruffleBoundary
    private MezzeTruffleFunction handlePartialApplication(
            Object[] arguments,
            MezzeRecord argsRecord,
            Map<String, MezzeValue> closureVariables
    ) {
        Map<String, MezzeValue> providedParams = new HashMap<>();
        List<String> missingParams = new ArrayList<>();

        if (argsRecord != null) {
            Map<String, MezzeValue> fields = argsRecord.getFields();
            for (String paramName : parameterNamesArray) {
                MezzeValue value = fields.get(paramName);
                if (value != null && !(value instanceof MezzeNull)) {
                    providedParams.put(paramName, value);
                } else {
                    missingParams.add(paramName);
                }
            }
        } else {
            for (String paramName : parameterNamesArray) {
                missingParams.add(paramName);
            }
        }

        // Extract lexical scope and slot map
        MaterializedFrame lexicalScope = null;
        if (arguments.length > 2 && arguments[2] instanceof MaterializedFrame) {
            lexicalScope = (MaterializedFrame) arguments[2];
        }

        @SuppressWarnings("unchecked")
        Map<String, Integer> lexicalScopeSlotMap = Collections.emptyMap();
        if (arguments.length > 3 && arguments[3] instanceof Map) {
            lexicalScopeSlotMap = (Map<String, Integer>) arguments[3];
        }

        return createPartiallyAppliedFunction(
                providedParams,
                missingParams,
                closureVariables,
                lexicalScope,
                lexicalScopeSlotMap
        );
    }

    /**
     * Create a partially applied function that captures the provided parameters.
     */
    @TruffleBoundary
    private MezzeTruffleFunction createPartiallyAppliedFunction(
            Map<String, MezzeValue> providedParams,
            List<String> missingParams,
            Map<String, MezzeValue> originalClosureVars,
            MaterializedFrame lexicalScope,
            Map<String, Integer> lexicalScopeSlotMap
    ) {
        PartiallyAppliedFunctionRootNode partialRootNode = new PartiallyAppliedFunctionRootNode(
                this.getCallTarget(),
                this.name,
                providedParams,
                missingParams,
                originalClosureVars,
                lexicalScope,
                lexicalScopeSlotMap
        );

        CallTarget partialCallTarget = partialRootNode.getCallTarget();

        return new MezzeTruffleFunction(
                partialCallTarget,
                name + "(partial)",
                Collections.emptyMap(),
                null,
                Collections.emptyMap()
        );
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "MezzeFunction(" + name + ")";
    }
}
