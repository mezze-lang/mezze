package com.funk.language.interpreter.nodes.function

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.RootNode
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkLanguage
import com.funk.language.interpreter.FunkNull
import com.funk.language.interpreter.NativeFunkFunction
import com.funk.language.interpreter.FunkTruffleFunction

/**
 * Function definition node that creates a callable function with closure support
 */
@NodeInfo(shortName = "function-def")
class FunctionDefinitionNode(
    private val name: String,
    private val parameterNames: List<String>,
    @field:Child private var bodyNode: FunkNode,
    private val frameDescriptor: FrameDescriptor,
    private val closureVariableNames: List<String> = emptyList(),
    private val closureVariableSlots: Map<String, Int> = emptyMap()
) : FunkNode() {

    private val callTarget: CallTarget by lazy {
        val rootNode = FunkFunctionRootNode(
            language = getLanguage(),
            name = name,
            parameterNames = parameterNames,
            bodyNode = bodyNode,
            frameDescriptor = frameDescriptor,
            closureVariableNames = closureVariableNames,
            closureVariableSlots = closureVariableSlots
        )
        rootNode.callTarget
    }

    override fun execute(frame: VirtualFrame): FunkValue {
        // CRITICAL FIX: Eagerly capture closure variable VALUES at closure creation time.
        //
        // We must NOT just store a reference to the materialized frame, because:
        // 1. The frame slots may be modified by subsequent code (e.g., tail recursion)
        // 2. If a closure is executed later (e.g., by a virtual thread), it would see
        //    the modified values, not the values at the time of closure creation.
        //
        // This is especially important for concurrent code where closures may be
        // executed on different threads after the original function has returned
        // or modified its local variables.

        val closureVariables = if (closureVariableNames.isNotEmpty()) {
            // Eagerly copy values from the current frame
            closureVariableNames.mapIndexed { index, varName ->
                val slot = closureVariableSlots[varName]
                val value = if (slot != null && slot < frame.frameDescriptor.numberOfSlots) {
                    try {
                        frame.getObject(slot) as? FunkValue ?: FunkNull
                    } catch (e: Exception) {
                        FunkNull
                    }
                } else {
                    FunkNull
                }
                varName to value
            }.toMap()
        } else {
            emptyMap()
        }

        // Create function with eagerly captured closure variables
        // We no longer use MaterializedFrame since we've already captured the values
        return FunkTruffleFunction(
            callTarget,
            name,
            closureVariables,  // Eagerly captured values
            null,              // No materialized frame needed
            emptyMap()         // No slot map needed
        )
    }
}

/**
 * Function call node with manual call site optimization
 */
@NodeInfo(shortName = "function-call")
class FunctionCallNode(
    @field:Child private var functionNode: FunkNode,
    @field:Child private var argumentsNode: FunkNode
) : FunkNode() {

    override fun execute(frame: VirtualFrame): FunkValue {
        val function = functionNode.execute(frame)
        val arguments = argumentsNode.execute(frame)

        val result = when (function) {
            is FunkTruffleFunction -> {
                // Direct call to Truffle function with closure variables
                // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                // [0] = args record, [1] = closure variables map (legacy),
                // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                function.callTarget.call(
                    arguments,
                    function.closureVariables,
                    function.lexicalScope,
                    function.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> {
                // Call native function
                function.call(listOf(arguments))
            }
            else -> {
                throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
            }
        }

        return result
    }
}

/**
 * Root node for Funk functions
 */
class FunkFunctionRootNode(
    language: FunkLanguage,
    private val name: String,
    private val parameterNames: List<String>,
    @field:Child private var bodyNode: FunkNode,
    frameDescriptor: FrameDescriptor,
    private val closureVariableNames: List<String> = emptyList(),
    private val closureVariableSlots: Map<String, Int> = emptyMap()
) : RootNode(language, frameDescriptor) {

    override fun execute(frame: VirtualFrame): Any {
        // Arguments:
        // [0] = FunkRecord of parameters
        // [1] = Map<String, FunkValue> of closure variables (legacy, for backward compat)
        // [2] = MaterializedFrame lexical scope (new approach)
        // [3] = Map<String, Int> lexical scope slot mapping
        val arguments = frame.getArguments()



        // Get context for registering variables
        val context = FunkLanguage.getCurrentContext(this)

        // CRITICAL: Propagate handlers from the call site to this function's execution
        // This ensures that effects called within this function can access the active handlers
        // Without this, functions in modules can't use effects from perform blocks
        val effectHandler = context.effectHandler
        val activeHandlers = effectHandler.getCurrentHandlers()
        if (activeHandlers != null) {
            // Store handlers in context so nested effect calls can find them
            context.defineLocal("__handlers", activeHandlers)
        }

        // Extract lexical scope (MaterializedFrame) from argument [2]
        @Suppress("UNCHECKED_CAST")
        val lexicalScope = if (arguments.size > 2) {
            arguments[2] as? com.oracle.truffle.api.frame.MaterializedFrame
        } else {
            null
        }

        // Extract lexical scope slot mapping from argument [3]
        @Suppress("UNCHECKED_CAST")
        val lexicalScopeSlotMap = if (arguments.size > 3) {
            arguments[3] as? Map<String, Int> ?: emptyMap()
        } else {
            emptyMap()
        }

        // Extract closure variables map from argument [1] (for backward compatibility)
        @Suppress("UNCHECKED_CAST")
        val closureVariables = if (arguments.size > 1) {
            arguments[1] as? Map<String, FunkValue> ?: emptyMap()
        } else {
            emptyMap()
        }

        // Collect provided and missing parameters for partial application support
        val argsRecord = if (arguments.isNotEmpty()) arguments[0] as? FunkRecord else null
        val providedParams = mutableMapOf<String, FunkValue>()
        val missingParams = mutableListOf<String>()

        // DEBUG: Log parameter checking




        // Special case: zero-parameter functions should always execute
        if (parameterNames.isEmpty()) {
            // Zero-parameter function - execute normally
            // Continue to normal execution (no partial application)
        } else if (argsRecord != null) {
            for (paramName in parameterNames) {
                val value = argsRecord.fields[paramName]

                if (value != null && value !is FunkNull) {
                    providedParams[paramName] = value

                } else {
                    missingParams.add(paramName)

                }
            }

        } else {
            missingParams.addAll(parameterNames)

        }



        // If not all parameters are provided (and function has > 0 parameters), return a partially applied function
        if (parameterNames.isNotEmpty() && missingParams.isNotEmpty()) {
            // Create a new function that captures the provided parameters
            // and waits for the remaining parameters
            val partiallyAppliedFunc = createPartiallyAppliedFunction(
                providedParams,
                missingParams,
                closureVariables,
                lexicalScope,
                lexicalScopeSlotMap
            )
            return partiallyAppliedFunc
        }

        // All parameters provided - execute the function body
        // Bind parameters to frame slots only (NOT context.locals to avoid polluting global namespace)
        for ((index, paramName) in parameterNames.withIndex()) {
            val value = providedParams[paramName] ?: FunkNull
            // Write to frame slot only
            // Parameters should NOT be written to context.locals as they're function-local
            // Closure capture is handled via the closure variables mechanism
            frame.setObject(index, value)
        }

        // Bind closure variables from the eagerly captured closureVariables map
        // These values were captured at closure creation time, ensuring they
        // reflect the state when the closure was created, not when it's executed.
        val paramCount = parameterNames.size
        for ((index, varName) in closureVariableNames.withIndex()) {
            val slotIndex = paramCount + index
            val value = closureVariables[varName]
            if (value != null && value !is FunkNull) {
                frame.setObject(slotIndex, value)
            } else {
                frame.setObject(slotIndex, FunkNull)
            }
        }

        return bodyNode.execute(frame)
    }

    /**
     * Create a partially applied function that captures the provided parameters
     * and waits for the remaining parameters
     */
    private fun createPartiallyAppliedFunction(
        providedParams: Map<String, FunkValue>,
        missingParams: List<String>,
        originalClosureVars: Map<String, FunkValue>,
        lexicalScope: com.oracle.truffle.api.frame.MaterializedFrame?,
        lexicalScopeSlotMap: Map<String, Int>
    ): FunkTruffleFunction {
        // Create a new root node for the partially applied function
        val partialRootNode = PartiallyAppliedFunctionRootNode(
            originalCallTarget = this.callTarget,
            originalFunctionName = this.name,
            providedParams = providedParams,
            missingParams = missingParams,
            originalClosureVars = originalClosureVars,
            lexicalScope = lexicalScope,
            lexicalScopeSlotMap = lexicalScopeSlotMap
        )

        val partialCallTarget = partialRootNode.callTarget

        // Return a new function that takes the missing parameters
        return FunkTruffleFunction(
            partialCallTarget,
            "$name(partial)",
            emptyMap(), // Closure variables are handled internally
            null, // Lexical scope is handled internally
            emptyMap() // Slot mapping is handled internally
        )
    }

    override fun getName(): String = name

    override fun toString(): String = "FunkFunction($name)"
}

/**
 * Root node for partially applied functions
 * This node captures the provided parameters and waits for the remaining parameters
 */
class PartiallyAppliedFunctionRootNode(
    private val originalCallTarget: CallTarget,
    private val originalFunctionName: String,
    private val providedParams: Map<String, FunkValue>,
    private val missingParams: List<String>,
    private val originalClosureVars: Map<String, FunkValue>,
    private val lexicalScope: com.oracle.truffle.api.frame.MaterializedFrame?,
    private val lexicalScopeSlotMap: Map<String, Int>
) : RootNode(null, FrameDescriptor()) {

    override fun execute(frame: VirtualFrame): Any {
        // Get the new arguments provided to this partially applied function
        val arguments = frame.getArguments()
        val newArgsRecord = if (arguments.isNotEmpty()) arguments[0] as? FunkRecord else null

        // Merge the previously provided parameters with the new ones
        val mergedParams = mutableMapOf<String, FunkValue>()
        mergedParams.putAll(providedParams)

        if (newArgsRecord != null) {
            for (paramName in missingParams) {
                val value = newArgsRecord.fields[paramName]
                if (value != null && value !is FunkNull) {
                    mergedParams[paramName] = value
                }
            }
        }

        // Create a record with all parameters and call the original function
        val allParamsRecord = FunkRecord(mergedParams)

        // Call the original function with all parameters
        // FunkFunctionRootNode expects 4 arguments now: params, closureVars, lexicalScope, slot map
        return originalCallTarget.call(allParamsRecord, originalClosureVars, lexicalScope, lexicalScopeSlotMap) as FunkValue
    }

    override fun getName(): String = "${originalFunctionName}(partial)"

    override fun toString(): String = "${originalFunctionName}(partial)"
}