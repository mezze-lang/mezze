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
            closureVariableNames = closureVariableNames
        )
        rootNode.callTarget
    }

    override fun execute(frame: VirtualFrame): FunkValue {
        // Capture closure variables from the current frame
        val closureVariables = mutableMapOf<String, FunkValue>()
        for (varName in closureVariableNames) {
            // Try to find the variable in the current frame or context
            val value = try {
                // CRITICAL FIX: First try to read from the current frame's slots
                // This allows nested functions to capture parameters and local variables from outer functions
                val slot = closureVariableSlots[varName]
                if (slot != null) {
                    // Read from the frame slot
                    val frameValue = frame.getObject(slot)

                    // If we got a valid value from the frame, use it
                    if (frameValue is FunkValue && frameValue !is FunkNull) {
                        frameValue
                    } else {
                        // Frame slot is null or FunkNull, fall back to global lookup
                        // This handles recursive functions and module-imported functions
                        val context = FunkLanguage.getCurrentContext(this)

                        val lookupName = if (varName.contains("$")) {
                            // Strip monomorphic suffix to get generic name
                            varName.substringBefore("$")
                        } else {
                            varName
                        }

                        val result = context.lookupGlobal(lookupName)
                        result
                    }
                } else {
                    // No slot information, fall back to global lookup
                    val context = FunkLanguage.getCurrentContext(this)

                    val lookupName = if (varName.contains("$")) {
                        // Strip monomorphic suffix to get generic name
                        varName.substringBefore("$")
                    } else {
                        varName
                    }

                    val result = context.lookupGlobal(lookupName)
                    result
                }
            } catch (e: Exception) {
                null
            }
            // Only capture non-null values
            // For recursive functions, the function won't exist yet, so we skip it
            // and let the variable read fall back to global lookup at runtime
            if (value != null && value !is FunkNull) {
                closureVariables[varName] = value
            }
        }

        return FunkTruffleFunction(callTarget, name, closureVariables)
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

        return when (function) {
            is FunkTruffleFunction -> {
                // Direct call to Truffle function with closure variables
                // Pass arguments and closure variables to the function
                function.callTarget.call(arguments, function.closureVariables) as FunkValue
            }
            is NativeFunkFunction -> {
                // Call native function
                function.call(listOf(arguments))
            }
            else -> {
                throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
            }
        }
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
    private val closureVariableNames: List<String> = emptyList()
) : RootNode(language, frameDescriptor) {

    override fun execute(frame: VirtualFrame): Any {
        // Arguments: [0] = FunkRecord of parameters, [1] = Map<String, FunkValue> of closure variables
        val arguments = frame.getArguments()

        // Get context for registering variables
        val context = FunkLanguage.getCurrentContext(this)

        // Extract closure variables if provided
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
                closureVariables
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

        // Bind closure variables to frame slots only (after parameters)
        val paramCount = parameterNames.size
        for ((index, varName) in closureVariableNames.withIndex()) {
            val slotIndex = paramCount + index
            val value = closureVariables[varName]
            // Only bind closure variables that were actually captured
            // For recursive functions, the function won't be in the closure map,
            // and the variable read will fall back to global lookup
            if (value != null && value !is FunkNull) {
                // Write to frame slot only
                // Closure variables should NOT be written to context.locals as they're function-local
                frame.setObject(slotIndex, value)
            } else {
                // Set slot to FunkNull so reads will fall back to global lookup
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
        originalClosureVars: Map<String, FunkValue>
    ): FunkTruffleFunction {
        // Create a new root node for the partially applied function
        val partialRootNode = PartiallyAppliedFunctionRootNode(
            originalCallTarget = this.callTarget,
            originalFunctionName = this.name,
            providedParams = providedParams,
            missingParams = missingParams,
            originalClosureVars = originalClosureVars
        )

        val partialCallTarget = partialRootNode.callTarget

        // Return a new function that takes the missing parameters
        return FunkTruffleFunction(
            partialCallTarget,
            "$name(partial)",
            emptyMap() // Closure variables are handled internally
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
    private val originalClosureVars: Map<String, FunkValue>
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
        return originalCallTarget.call(allParamsRecord, originalClosureVars) as FunkValue
    }

    override fun getName(): String = "${originalFunctionName}(partial)"

    override fun toString(): String = "${originalFunctionName}(partial)"
}