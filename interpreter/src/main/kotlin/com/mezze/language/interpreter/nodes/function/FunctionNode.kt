package com.mezze.language.interpreter.nodes.function

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeLanguage
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.NativeMezzeFunction
import com.mezze.language.interpreter.MezzeTruffleFunction

/**
 * Function definition node that creates a callable function with closure support.
 *
 * IMPORTANT: The CallTarget is created lazily on first access, but this is marked
 * as a TruffleBoundary to avoid triggering JIT compilation issues. The RootNode
 * creation happens outside the compilation path.
 *
 * TRUFFLE OPTIMIZATION NOTES:
 * - closureVarNamesArray is marked @CompilationFinal(dimensions = 1) so Graal
 *   can constant-fold iterations during JIT compilation
 * - closureSlotIndices pre-computes slot indices to avoid Map lookups
 */
@NodeInfo(shortName = "function-def")
class FunctionDefinitionNode(
    private val name: String,
    parameterNames: List<String>,
    @field:Child private var bodyNode: MezzeNode,
    private val frameDescriptor: FrameDescriptor,
    closureVariableNames: List<String> = emptyList(),
    closureVariableSlots: Map<String, Int> = emptyMap()
) : MezzeNode() {

    // Convert lists to arrays with @CompilationFinal for JIT optimization
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val parameterNamesArray: Array<String> = parameterNames.toTypedArray()

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val closureVarNamesArray: Array<String> = closureVariableNames.toTypedArray()

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private var _callTarget: CallTarget? = null

    /**
     * Get or create the CallTarget. Marked as TruffleBoundary to ensure
     * RootNode creation happens outside the JIT compilation path.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun getOrCreateCallTarget(): CallTarget {
        if (_callTarget == null) {
            // MezzeFunctionRootNode is a Java class, so we can't use named arguments
            val rootNode = MezzeFunctionRootNode(
                getLanguage(),
                name,
                parameterNamesArray.toList(),
                bodyNode,
                frameDescriptor,
                closureVarNamesArray.toList(),
                closureSlotMap
            )
            _callTarget = rootNode.callTarget
        }
        return _callTarget!!
    }

    // Store the original slots map for passing to RootNode (Map operations are in @TruffleBoundary)
    private val closureSlotMap: Map<String, Int> = closureVariableSlots

    // Pre-compute slot indices array at construction time to avoid Map lookups during execution
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val closureSlotIndices: IntArray = closureVarNamesArray.map { varName ->
        closureVariableSlots[varName] ?: -1
    }.toIntArray()

    // Number of closure variables (pre-computed for array allocation)
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val numClosureVars: Int = closureVarNamesArray.size

    // Whether we have closure variables
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val hasClosureVars: Boolean = closureVarNamesArray.isNotEmpty()

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Eagerly capture closure variable VALUES at closure creation time.
        // We use pre-computed slot indices to avoid Map lookups in the hot path.
        val closureVariables = if (hasClosureVars) {
            // Read values from frame first (can't pass VirtualFrame to TruffleBoundary)
            val values = Array(numClosureVars) { index ->
                val slot = closureSlotIndices[index]
                if (slot >= 0) {
                    try {
                        frame.getObject(slot) as? MezzeValue ?: MezzeNull
                    } catch (e: Exception) {
                        MezzeNull
                    }
                } else {
                    MezzeNull
                }
            }
            // Build the map via TruffleBoundary
            buildClosureMap(values)
        } else {
            emptyMap()
        }

        // Create function with eagerly captured closure variables
        return MezzeTruffleFunction(
            getOrCreateCallTarget(),
            name,
            closureVariables,
            null,
            emptyMap()
        )
    }

    /**
     * Build the closure variables map from pre-extracted values.
     * Marked as @TruffleBoundary to keep Map creation outside JIT path.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun buildClosureMap(values: Array<MezzeValue>): Map<String, MezzeValue> {
        val result = mutableMapOf<String, MezzeValue>()
        for (index in 0 until numClosureVars) {
            result[closureVarNamesArray[index]] = values[index]
        }
        return result
    }
}

/**
 * Function call node with manual call site optimization
 *
 * NOTE: The actual function call is done via @TruffleBoundary to avoid
 * JIT blockers from exception handling code paths (Kotlin null checks,
 * Intrinsics.throwJavaNpe, etc.)
 */
@NodeInfo(shortName = "function-call")
class FunctionCallNode(
    @field:Child private var functionNode: MezzeNode,
    @field:Child private var argumentsNode: MezzeNode
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val function = functionNode.execute(frame)
        val arguments = argumentsNode.execute(frame)
        return dispatchCall(function, arguments)
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun dispatchCall(function: MezzeValue, arguments: MezzeValue): MezzeValue {
        return when (function) {
            is MezzeTruffleFunction -> {
                // Direct call to Truffle function with closure variables
                // CRITICAL: MezzeFunctionRootNode expects 4 arguments:
                // [0] = args record, [1] = closure variables map (legacy),
                // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                val result = function.callTarget.call(
                    arguments,
                    function.closureVariables,
                    function.lexicalScope,
                    function.lexicalScopeSlotMap
                )
                result as? MezzeValue ?: MezzeNull
            }
            is NativeMezzeFunction -> {
                // Call native function
                function.call(listOf(arguments))
            }
            else -> {
                throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
            }
        }
    }
}

// MezzeFunctionRootNode and PartiallyAppliedFunctionRootNode are now implemented in Java
// to avoid Kotlin intrinsics that trigger JIT compilation blockers in GraalVM native image.
// See: src/main/java/com/mezze/language/interpreter/nodes/function/MezzeFunctionRootNode.java
// See: src/main/java/com/mezze/language/interpreter/nodes/function/PartiallyAppliedFunctionRootNode.java