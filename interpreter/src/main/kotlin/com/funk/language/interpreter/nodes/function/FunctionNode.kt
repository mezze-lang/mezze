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
    private val closureVariableNames: List<String> = emptyList()
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
                // First try frame slots
                val outerConverter = this.rootNode?.frameDescriptor
                // Since we don't have access to the outer converter's slots directly,
                // we'll use the context to get the value
                val context = FunkLanguage.getCurrentContext(this)
                context.lookupGlobal(varName)
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

        val func = FunkTruffleFunction(callTarget, name, closureVariables)
        println("DEBUG FunctionDefinitionNode($name): Created function = $func with ${closureVariables.size} closure vars: ${closureVariables.keys}")
        return func
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
                println("DEBUG FunctionCallNode: Calling function with ${function.closureVariables.size} closure variables")
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
        println("DEBUG FunkFunctionRootNode($name): execute called with ${arguments.size} arguments")

        // Get context for registering variables
        val context = FunkLanguage.getCurrentContext(this)

        // Extract closure variables if provided
        @Suppress("UNCHECKED_CAST")
        val closureVariables = if (arguments.size > 1) {
            arguments[1] as? Map<String, FunkValue> ?: emptyMap()
        } else {
            emptyMap()
        }

        println("DEBUG FunkFunctionRootNode($name): Got ${closureVariables.size} closure variables: ${closureVariables.keys}")

        // Collect provided and missing parameters for partial application support
        val argsRecord = if (arguments.isNotEmpty()) arguments[0] as? FunkRecord else null
        val providedParams = mutableMapOf<String, FunkValue>()
        val missingParams = mutableListOf<String>()

        // Special case: zero-parameter functions should always execute
        if (parameterNames.isEmpty()) {
            // Zero-parameter function - execute normally
            // Continue to normal execution (no partial application)
        } else if (argsRecord != null) {
            println("DEBUG FunkFunctionRootNode($name): argsRecord = ${argsRecord.fields.mapValues { "${it.value::class.simpleName}:$it.value" }}")
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
            println("DEBUG FunkFunctionRootNode($name): Partial application - provided ${providedParams.keys}, missing $missingParams")

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
        // Bind parameters to frame slots AND context.locals
        for ((index, paramName) in parameterNames.withIndex()) {
            val value = providedParams[paramName] ?: FunkNull
            println("DEBUG FunkFunctionRootNode($name): Setting parameter slot $index ($paramName) = ${value::class.simpleName}:$value")
            // Write to frame slot
            frame.setObject(index, value)
            // ALSO write to context.locals so nested functions can capture this variable
            context.defineLocal(paramName, value)
        }

        // Bind closure variables to frame slots AND context.locals after parameters
        val paramCount = parameterNames.size
        for ((index, varName) in closureVariableNames.withIndex()) {
            val slotIndex = paramCount + index
            val value = closureVariables[varName]
            // Only bind closure variables that were actually captured
            // For recursive functions, the function won't be in the closure map,
            // and the variable read will fall back to global lookup
            if (value != null && value !is FunkNull) {
                println("DEBUG FunkFunctionRootNode($name): Setting closure slot $slotIndex ($varName) = ${value::class.simpleName}:$value")
                // Write to frame slot
                frame.setObject(slotIndex, value)
                // ALSO write to context.locals so they're accessible
                context.defineLocal(varName, value)
            } else {
                println("DEBUG FunkFunctionRootNode($name): Skipping closure slot $slotIndex ($varName) - will use global lookup")
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

        println("DEBUG PartiallyAppliedFunctionRootNode: Called with args = ${newArgsRecord?.fields?.keys}")

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

        println("DEBUG PartiallyAppliedFunctionRootNode: Merged params = ${mergedParams.keys}")

        // Create a record with all parameters and call the original function
        val allParamsRecord = FunkRecord(mergedParams)

        // Call the original function with all parameters
        return originalCallTarget.call(allParamsRecord, originalClosureVars) as FunkValue
    }

    override fun getName(): String = "${originalFunctionName}(partial)"

    override fun toString(): String = "${originalFunctionName}(partial)"
}