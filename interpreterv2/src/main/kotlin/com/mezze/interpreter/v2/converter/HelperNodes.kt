package com.mezze.interpreter.v2.converter

import com.mezze.interpreter.v2.nodes.MezzeNode
import com.mezze.interpreter.v2.values.MezzeUnit
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeList
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended
import com.mezze.interpreter.v2.runtime.MezzeLanguage
import com.mezze.interpreter.v2.runtime.MezzeContext
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.CallTarget
import com.mezze.interpreter.v2.nodes.MezzeRootNode
import com.mezze.interpreter.v2.nodes.function.FunctionDefinitionNode
import com.mezze.interpreter.v2.nodes.function.MezzeFunctionRootNode
import com.mezze.interpreter.v2.values.MezzeFunction

/**
 * Node that binds an imported value from a use declaration.
 */
@NodeInfo(shortName = "use-binding")
class UseBindingNode(
    private val slot: Int,
    private val qualifiedName: String,
    private val localName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        val value = lookupImport()
        frame.setObject(slot, value)
        return value
    }

    @TruffleBoundary
    private fun lookupImport(): Any {
        val context = MezzeContext.get(this)
        return context.lookupGlobal(qualifiedName) ?: MezzeUnit.INSTANCE
    }
}

/**
 * Node that looks up a global value (builtin function, etc).
 */
@NodeInfo(shortName = "global-lookup")
class GlobalLookupNode(
    private val name: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        return lookupGlobal()
    }

    @TruffleBoundary
    private fun lookupGlobal(): Any {
        val context = MezzeContext.get(this)
        return context.lookupGlobal(name) ?: MezzeUnit.INSTANCE
    }
}

/**
 * Node that throws a runtime error.
 */
@NodeInfo(shortName = "error")
class RuntimeErrorNode(
    private val message: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        throw RuntimeException(message)
    }
}

/**
 * Interpolated string node that concatenates parts.
 *
 * OPTIMIZED: Uses inline concatenation for JIT-friendly execution.
 * StringBuilder operations are pushed to final assembly outside the loop.
 */
@NodeInfo(shortName = "string-interp")
class InterpolatedStringNode(
    @field:Children private val parts: Array<MezzeNode>
) : MezzeNode() {

    @CompilationFinal
    private val partCount: Int = parts.size

    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any {
        if (partCount == 0) return ""
        if (partCount == 1) {
            val value = parts[0].execute(frame)
            return valueToString(value)
        }

        // Collect values first (JIT can unroll this)
        val values = arrayOfNulls<Any>(partCount)
        for (i in 0 until partCount) {
            values[i] = parts[i].execute(frame)
        }

        // Assemble string (boundary is outside the hot loop)
        return assembleString(values)
    }

    private fun valueToString(value: Any): String {
        return when (value) {
            is String -> value
            is Long -> value.toString()
            is Double -> value.toString()
            is Boolean -> value.toString()
            else -> slowToString(value)
        }
    }

    @TruffleBoundary
    private fun slowToString(value: Any): String {
        return value.toString()
    }

    @TruffleBoundary
    private fun assembleString(values: Array<Any?>): String {
        val sb = StringBuilder()
        for (value in values) {
            sb.append(value?.toString() ?: "null")
        }
        return sb.toString()
    }
}

/**
 * Array literal node that creates a MezzeList.
 */
@NodeInfo(shortName = "array-literal")
class ArrayLiteralNode(
    @field:Children private val elements: Array<MezzeNode>
) : MezzeNode() {

    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any {
        val values = arrayOfNulls<Any>(elements.size)
        for (i in elements.indices) {
            values[i] = elements[i].execute(frame)
        }
        return createList(values)
    }

    @TruffleBoundary
    private fun createList(values: Array<Any?>): MezzeList {
        @Suppress("UNCHECKED_CAST")
        return MezzeList(values as Array<Any>)
    }
}

/**
 * Builtin binary operation node that extracts l and r from a record argument.
 */
@NodeInfo(shortName = "builtin-binary")
class BuiltinBinaryOpNode(
    @field:Child private var argsChild: MezzeNode,
    private val operation: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        val args = argsChild.execute(frame)
        return executeOp(args)
    }

    @TruffleBoundary
    private fun executeOp(args: Any): Any {
        if (args !is MezzeRecord) {
            throw RuntimeException("Expected record argument for builtin operation, got: ${args::class.simpleName}")
        }

        val l = args.getField("l")
        val r = args.getField("r")

        return when (operation) {
            "add" -> performAdd(l, r)
            "subtract" -> performSubtract(l, r)
            "multiply" -> performMultiply(l, r)
            "divide" -> performDivide(l, r)
            "modulo" -> performModulo(l, r)
            "equal" -> l == r
            "notEqual" -> l != r
            "lessThan" -> compare(l, r) < 0
            "greaterThan" -> compare(l, r) > 0
            "lessEqual" -> compare(l, r) <= 0
            "greaterEqual" -> compare(l, r) >= 0
            "and" -> (l as Boolean) && (r as Boolean)
            "or" -> (l as Boolean) || (r as Boolean)
            else -> throw RuntimeException("Unknown operation: $operation")
        }
    }

    private fun performAdd(l: Any?, r: Any?): Any {
        return when {
            l is Long && r is Long -> l + r
            l is Double && r is Double -> l + r
            l is Long && r is Double -> l.toDouble() + r
            l is Double && r is Long -> l + r.toDouble()
            l is String || r is String -> l.toString() + r.toString()
            else -> throw RuntimeException("Cannot add $l and $r")
        }
    }

    private fun performSubtract(l: Any?, r: Any?): Any {
        return when {
            l is Long && r is Long -> l - r
            l is Double && r is Double -> l - r
            l is Long && r is Double -> l.toDouble() - r
            l is Double && r is Long -> l - r.toDouble()
            else -> throw RuntimeException("Cannot subtract $l and $r")
        }
    }

    private fun performMultiply(l: Any?, r: Any?): Any {
        return when {
            l is Long && r is Long -> l * r
            l is Double && r is Double -> l * r
            l is Long && r is Double -> l.toDouble() * r
            l is Double && r is Long -> l * r.toDouble()
            else -> throw RuntimeException("Cannot multiply $l and $r")
        }
    }

    private fun performDivide(l: Any?, r: Any?): Any {
        return when {
            l is Long && r is Long -> l / r
            l is Double && r is Double -> l / r
            l is Long && r is Double -> l.toDouble() / r
            l is Double && r is Long -> l / r.toDouble()
            else -> throw RuntimeException("Cannot divide $l and $r")
        }
    }

    private fun performModulo(l: Any?, r: Any?): Any {
        return when {
            l is Long && r is Long -> l % r
            l is Double && r is Double -> l % r
            l is Long && r is Double -> l.toDouble() % r
            l is Double && r is Long -> l % r.toDouble()
            else -> throw RuntimeException("Cannot modulo $l and $r")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(l: Any?, r: Any?): Int {
        return when {
            l is Long && r is Long -> l.compareTo(r)
            l is Double && r is Double -> l.compareTo(r)
            l is Long && r is Double -> l.toDouble().compareTo(r)
            l is Double && r is Long -> l.compareTo(r.toDouble())
            l is Comparable<*> && r != null -> (l as Comparable<Any>).compareTo(r)
            else -> throw RuntimeException("Cannot compare $l and $r")
        }
    }
}

/**
 * Builtin unary operation node that extracts n or val from a record argument.
 */
@NodeInfo(shortName = "builtin-unary")
class BuiltinUnaryOpNode(
    @field:Child private var argsChild: MezzeNode,
    private val operation: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        val args = argsChild.execute(frame)
        return executeOp(args)
    }

    @TruffleBoundary
    private fun executeOp(args: Any): Any {
        if (args !is MezzeRecord) {
            throw RuntimeException("Expected record argument for builtin operation, got: ${args::class.simpleName}")
        }

        // NumSub.neg uses field name 'n', LogicalNot.not uses 'val'
        val fieldName = if (operation == "not") "val" else "n"
        val value = args.getField(fieldName)

        return when (operation) {
            "negate" -> {
                when (value) {
                    is Long -> -value
                    is Double -> -value
                    else -> throw RuntimeException("Cannot negate $value")
                }
            }
            "not" -> {
                when (value) {
                    is Boolean -> !value
                    else -> throw RuntimeException("Cannot negate $value")
                }
            }
            else -> throw RuntimeException("Unknown operation: $operation")
        }
    }
}

/**
 * Resolved ability call node - looks up impl by content hash.
 */
@NodeInfo(shortName = "ability-call")
class ResolvedAbilityCallNode(
    @field:Child private var argsChild: MezzeNode,
    private val localSlot: Int?,
    private val implHash: String,
    private val operationName: String,
    private val abilityName: String
) : MezzeNode() {

    @CompilationFinal
    private var cachedCallTarget: CallTarget? = null

    override fun execute(frame: VirtualFrame): Any {
        val args = argsChild.execute(frame)
        return executeCall(frame, args)
    }

    @TruffleBoundary
    private fun executeCall(frame: VirtualFrame, args: Any): Any {
        // Try local slot first
        val impl = if (localSlot != null && localSlot >= 0) {
            frame.getObject(localSlot)
        } else {
            // Look up in context by hash
            val context = MezzeContext.get(this)
            context.lookupGlobal(implHash)
        }

        if (impl !is MezzeRecord) {
            throw RuntimeException("Impl not found for hash: $implHash")
        }

        val operationFunc = impl.getField(operationName)
        if (operationFunc !is MezzeFunction) {
            throw RuntimeException("Operation $operationName not found in ability $abilityName")
        }

        return operationFunc.callTarget.call(args)
    }
}

/**
 * Storage node for effect impl records.
 */
@NodeInfo(shortName = "effect-impl-storage")
class EffectImplStorageNode(
    @field:Child private var recordChild: MezzeNode,
    private val hashSlot: Int,
    private val performerSlot: Int,
    private val contentHash: String,
    private val performerName: String,
    private val effectName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        val value = recordChild.execute(frame)
        frame.setObject(hashSlot, value)
        frame.setObject(performerSlot, value)
        storeInContext(value)
        return value
    }

    @TruffleBoundary
    private fun storeInContext(value: Any) {
        val context = MezzeContext.get(this)
        context.defineGlobal(contentHash, value)
        context.defineLocal(performerName, value)
        // Also store under effect name for DynamicEffectCallNode to find when no explicit perform block.
        // Only do this for non-parameterized impls (this class is for non-parameterized),
        // since parameterized impls require the function to be called first.
        context.defineGlobal(effectName, value)
        context.defineLocal(effectName, value)
    }
}

/**
 * Storage node for ability impl records.
 */
@NodeInfo(shortName = "ability-impl-storage")
class AbilityImplStorageNode(
    @field:Child private var recordChild: MezzeNode,
    private val hashSlot: Int,
    private val contentHash: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        val value = recordChild.execute(frame)
        frame.setObject(hashSlot, value)
        storeInContext(value)
        return value
    }

    @TruffleBoundary
    private fun storeInContext(value: Any) {
        val context = MezzeContext.get(this)
        context.defineGlobal(contentHash, value)
    }
}

/**
 * Parameterized impl definition node.
 */
@NodeInfo(shortName = "param-impl-def")
class ParameterizedImplDefinitionNode(
    private val implName: String,
    private val implParamNames: List<String>,
    private val implParamSlots: Map<String, Int>,
    private val operationNames: List<String>,
    private val operationBodyNodes: List<MezzeNode>,
    private val operationParamNames: List<List<String>>,
    private val operationFrameDescriptors: List<FrameDescriptor>,
    private val operationClosureVarSlots: List<IntArray>,
    private val implFrameDescriptor: FrameDescriptor,
    // Optional scoped effect nodes - executed when impl is instantiated
    private val initializeScopeNode: MezzeNode? = null,
    private val initializeScopeClosureSlots: IntArray = intArrayOf()
) : MezzeNode() {

    @CompilationFinal
    private var _callTarget: CallTarget? = null

    override fun execute(frame: VirtualFrame): Any {
        return MezzeFunction(
            getOrCreateCallTarget(),
            "impl_$implName",
            emptyArray()
        )
    }

    @TruffleBoundary
    private fun getOrCreateCallTarget(): CallTarget {
        if (_callTarget == null) {
            val rootNode = ParameterizedImplRootNode(
                language = MezzeLanguage.get(this),
                implName = implName,
                implParamNames = implParamNames,
                implParamSlots = implParamSlots,
                operationNames = operationNames.toTypedArray(),
                operationBodyNodes = operationBodyNodes.toTypedArray(),
                operationParamNamesList = operationParamNames.map { it.toTypedArray() }.toTypedArray(),
                operationFrameDescriptors = operationFrameDescriptors.toTypedArray(),
                operationClosureVarSlots = operationClosureVarSlots.toTypedArray(),
                frameDescriptor = implFrameDescriptor,
                initializeScopeNode = initializeScopeNode,
                initializeScopeClosureSlots = initializeScopeClosureSlots
            )
            _callTarget = rootNode.callTarget
        }
        return _callTarget!!
    }
}

/**
 * Root node for parameterized impl execution.
 */
class ParameterizedImplRootNode(
    language: MezzeLanguage,
    private val implName: String,
    private val implParamNames: List<String>,
    implParamSlots: Map<String, Int>,
    private val operationNames: Array<String>,
    private val operationBodyNodes: Array<MezzeNode>,
    private val operationParamNamesList: Array<Array<String>>,  // Actual param names, not counts
    private val operationFrameDescriptors: Array<FrameDescriptor>,
    private val operationClosureVarSlots: Array<IntArray>,
    frameDescriptor: FrameDescriptor,
    // Optional scoped effect - executed when impl is instantiated (e.g., reset random state)
    @field:Child private var initializeScopeNode: MezzeNode? = null,
    @CompilationFinal(dimensions = 1)
    private val initializeScopeClosureSlots: IntArray = intArrayOf()
) : RootNode(language, frameDescriptor) {

    @CompilationFinal(dimensions = 1)
    private val implParamSlotIndices: IntArray = implParamNames.map { paramName ->
        implParamSlots[paramName] ?: -1
    }.toIntArray()

    override fun execute(frame: VirtualFrame): Any {
        val arguments = frame.arguments
        val paramsRecord = if (arguments.isNotEmpty()) {
            arguments[0] as? MezzeRecord ?: MezzeRecord.empty()
        } else {
            MezzeRecord.empty()
        }

        // Bind impl params
        val paramValues = extractImplParamValues(paramsRecord)
        for (index in implParamNames.indices) {
            val slot = implParamSlotIndices[index]
            if (slot >= 0) {
                frame.setObject(slot, paramValues[index])
            }
        }

        // Execute initialize_scope if present (e.g., reset random state for SeededRandom)
        // This runs BEFORE creating operation functions, so it happens at the start of each perform block
        if (initializeScopeNode != null) {
            // Create a frame for the initialize_scope with closure values bound
            val initFrame = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(arrayOf<Any>(), frameDescriptor)
            for (i in initializeScopeClosureSlots.indices) {
                val srcSlot = initializeScopeClosureSlots[i]
                if (srcSlot >= 0 && srcSlot < implParamSlotIndices.size) {
                    val paramSlot = implParamSlotIndices[srcSlot]
                    if (paramSlot >= 0) {
                        initFrame.setObject(i, frame.getObject(paramSlot))
                    }
                }
            }
            initializeScopeNode!!.execute(initFrame)
        }

        // Create operation functions
        val operationFunctions = Array(operationNames.size) { index ->
            val closureValues = Array(operationClosureVarSlots[index].size) { i ->
                frame.getObject(operationClosureVarSlots[index][i])
            }

            MezzeFunction(
                createOperationCallTarget(index),
                "${implName}_${operationNames[index]}",
                closureValues
            )
        }

        return buildOperationRecord(operationFunctions)
    }

    @TruffleBoundary
    private fun extractImplParamValues(paramsRecord: MezzeRecord): Array<Any?> {
        return Array(implParamNames.size) { index ->
            val paramName = implParamNames[index]
            paramsRecord.getField(paramName)
        }
    }

    @TruffleBoundary
    private fun createOperationCallTarget(index: Int): CallTarget {
        val paramNames = operationParamNamesList[index]
        val closureCount = operationClosureVarSlots[index].size

        // Use MezzeFunctionRootNode which properly unpacks record arguments
        val rootNode = MezzeFunctionRootNode(
            MezzeLanguage.get(this),
            operationFrameDescriptors[index],
            operationBodyNodes[index],
            "${implName}_${operationNames[index]}",
            paramNames,
            IntArray(closureCount) { it },  // Closure slots are 0..closureCount-1
            null  // Default slot types
        )
        return rootNode.callTarget
    }

    @TruffleBoundary
    private fun buildOperationRecord(operationFunctions: Array<MezzeFunction>): MezzeRecord {
        val fieldNames = operationNames.toList().toTypedArray()
        @Suppress("UNCHECKED_CAST")
        return MezzeRecord.fromFields(fieldNames, operationFunctions as Array<Any>)
    }

    override fun getName(): String = "impl_$implName"
}

/**
 * Storage node for parameterized impl.
 */
@NodeInfo(shortName = "param-impl-storage")
class ParameterizedImplStorageNode(
    @field:Child private var implDefChild: MezzeNode,
    private val hashSlot: Int,
    private val performerSlot: Int,
    private val contentHash: String,
    private val performerName: String,
    private val effectName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        val value = implDefChild.execute(frame)
        frame.setObject(hashSlot, value)
        frame.setObject(performerSlot, value)
        storeInContext(value)
        return value
    }

    @TruffleBoundary
    private fun storeInContext(value: Any) {
        val context = MezzeContext.get(this)
        context.defineGlobal(contentHash, value)
        context.defineLocal(performerName, value)
    }
}

/**
 * Dynamic handle node - looks up handler by content hash at runtime.
 *
 * This handles the case where the handler is not statically resolved in the converter
 * but the typechecker has resolved the handler implementation.
 *
 * CRITICAL: The handler must be stored under BOTH:
 * 1. The handler name (e.g., "Console") - for handler lookup
 * 2. The effect names it handles (e.g., "Log") - for effect operations
 *
 * Optimizations:
 * - Caches the handler lookup after first resolution
 * - Uses DirectCallNode for handler invocation
 */
@NodeInfo(shortName = "dynamic-handle")
class DynamicHandleNode(
    private val handlerName: String,
    private val effectNames: Array<String>,  // Effects this handler handles
    private val contentHashes: Array<String>,  // Content hashes for handler lookup
    @field:Child private var bodyChild: MezzeNode
) : MezzeNode() {

    // Secondary constructors for backward compatibility
    constructor(handlerName: String, bodyChild: MezzeNode) : this(handlerName, emptyArray(), emptyArray(), bodyChild)
    constructor(handlerName: String, effectNames: Array<String>, bodyChild: MezzeNode) : this(handlerName, effectNames, emptyArray(), bodyChild)

    @CompilationFinal
    private var cachedHandler: Any? = null

    @CompilationFinal
    private var initialized: Boolean = false

    override fun execute(frame: VirtualFrame): Any {
        // Try to get the handler from the frame or context
        val handler = getHandler(frame)

        if (handler != null) {
            // Install handler by name in frame for effect operations to find
            val context = MezzeContext.get(this)
            context.defineLocal(handlerName, handler)

            // CRITICAL: Also store under each effect name so DynamicEffectCallNode can find it
            for (effectName in effectNames) {
                context.defineLocal(effectName, handler)
            }
        }

        try {
            val result = bodyChild.execute(frame)
            return processResult(result)
        } finally {
            // Cleanup if needed
        }
    }

    /**
     * Process the result:
     * - For MezzeContinuationSuspended with Exception/Return effects, extract suspended value
     * - For Exception effect, wrap successful results in Ok variant
     */
    @TruffleBoundary
    private fun processResult(result: Any): Any {
        // Handle suspended continuations (for early return/exception)
        if (result is MezzeContinuationSuspended) {
            // Exception and Return effects use suspend_with to return early
            if (effectNames.contains("Exception") || effectNames.contains("Return")) {
                return result.suspendedValue
            }
            return result
        }

        // Wrap successful results in Ok for Exception effect
        if (effectNames.contains("Exception")) {
            return MezzeVariant.ok(result)
        }

        return result
    }

    @TruffleBoundary
    private fun getHandler(frame: VirtualFrame): Any? {
        // Try cached handler first
        if (initialized && cachedHandler != null) {
            return cachedHandler
        }

        val context = MezzeContext.get(this)

        // First try content hashes (most reliable)
        for (hash in contentHashes) {
            val handler = context.lookupGlobal(hash)
            if (handler != null) {
                cachedHandler = handler
                initialized = true
                return handler
            }
        }

        // Fall back to name lookup
        val handler = context.lookupLocal(handlerName) ?: context.lookupGlobal(handlerName)

        if (handler != null && !initialized) {
            cachedHandler = handler
            initialized = true
        }

        return handler
    }
}

/**
 * Handle node for parameterized effect implementations.
 *
 * When you write `perform MockTime { fixed_time = 1234 } in ...`:
 * 1. Look up the impl function (MockTime) by content hash
 * 2. Call it with the parameters ({ fixed_time = 1234 })
 * 3. Use the returned record as the handler
 *
 * This differs from DynamicHandleNode which expects the handler to already be a record.
 */
@NodeInfo(shortName = "parameterized-handle")
class ParameterizedHandleNode(
    private val handlerName: String,
    private val effectNames: Array<String>,
    private val contentHashes: Array<String>,
    @field:Child private var parametersNode: MezzeNode,  // Evaluates to the parameters record
    @field:Child private var bodyChild: MezzeNode
) : MezzeNode() {

    @field:Child private var callNode: com.oracle.truffle.api.nodes.IndirectCallNode =
        com.oracle.truffle.api.nodes.IndirectCallNode.create()

    override fun execute(frame: VirtualFrame): Any {
        // Evaluate parameters first
        val parameters = parametersNode.execute(frame)

        // Get the impl function and call it with parameters
        val handler = getAndCallImplFunction(parameters)

        if (handler != null) {
            // Install handler by name and effect names
            val context = MezzeContext.get(this)
            context.defineLocal(handlerName, handler)

            for (effectName in effectNames) {
                context.defineLocal(effectName, handler)
            }
        }

        try {
            val result = bodyChild.execute(frame)
            return processResult(result)
        } finally {
            // Cleanup handled by context scope
        }
    }

    /**
     * Process the result:
     * - For MezzeContinuationSuspended with Exception/Return effects, extract suspended value
     * - For Exception effect, wrap successful results in Ok variant
     */
    @TruffleBoundary
    private fun processResult(result: Any): Any {
        // Handle suspended continuations (for early return/exception)
        if (result is MezzeContinuationSuspended) {
            // Exception and Return effects use suspend_with to return early
            if (effectNames.contains("Exception") || effectNames.contains("Return")) {
                return result.suspendedValue
            }
            return result
        }

        // Wrap successful results in Ok for Exception effect
        if (effectNames.contains("Exception")) {
            return MezzeVariant.ok(result)
        }

        return result
    }

    @TruffleBoundary
    private fun getAndCallImplFunction(parameters: Any): Any? {
        val context = MezzeContext.get(this)

        // Look up the parameterized impl by content hash
        for (hash in contentHashes) {
            val implFunc = context.lookupGlobal(hash)
            if (implFunc is MezzeFunction) {
                // Call the impl function with parameters to get the handler record
                val closureValues = implFunc.closureValues
                val arguments = if (closureValues.isEmpty()) {
                    arrayOf(parameters)
                } else {
                    Array(closureValues.size + 1) { i ->
                        if (i < closureValues.size) closureValues[i] else parameters
                    }
                }
                // Use spread operator to pass array elements as individual arguments
                return callNode.call(implFunc.callTarget, *arguments)
            } else if (implFunc is MezzeRecord) {
                // Already a record (non-parameterized), use directly
                return implFunc
            }
        }

        // Fall back to name lookup
        val implFunc = context.lookupLocal(handlerName) ?: context.lookupGlobal(handlerName)
        if (implFunc is MezzeFunction) {
            val closureValues = implFunc.closureValues
            val arguments = if (closureValues.isEmpty()) {
                arrayOf(parameters)
            } else {
                Array(closureValues.size + 1) { i ->
                    if (i < closureValues.size) closureValues[i] else parameters
                }
            }
            // Use spread operator to pass array elements as individual arguments
            return callNode.call(implFunc.callTarget, *arguments)
        } else if (implFunc is MezzeRecord) {
            return implFunc
        }

        throw RuntimeException("Parameterized impl not found for: $handlerName")
    }
}

/**
 * State machine state node.
 */
@NodeInfo(shortName = "sm-state")
class StateMachineStateNode(
    val stateName: String,
    @field:Child private var bodyChild: MezzeNode
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        return bodyChild.execute(frame)
    }
}

/**
 * State machine node.
 */
@NodeInfo(shortName = "state-machine")
class StateMachineNode(
    @field:Children private val states: Array<StateMachineStateNode>,
    private val initialState: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): Any {
        // Find and execute initial state
        for (state in states) {
            if (state.stateName == initialState) {
                return state.execute(frame)
            }
        }
        throw RuntimeException("Initial state not found: $initialState")
    }
}
