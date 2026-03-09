package com.mezze.interpreter.v2.converter

import com.mezze.interpreter.v2.nodes.MezzeNode
import com.mezze.interpreter.v2.values.MezzeUnit
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeList
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.nodes.continuation.ForkedExecutionException
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
 * Uses content hash for O(1) lookup when available, falls back to qualified name.
 */
@NodeInfo(shortName = "use-binding")
class UseBindingNode(
    private val slot: Int,
    private val qualifiedName: String,
    private val localName: String,
    private val contentHash: String? = null
) : MezzeNode() {

    @CompilationFinal
    private var cachedValue: Any? = null

    @CompilationFinal
    private var initialized: Boolean = false

    override fun execute(frame: VirtualFrame): Any {
        // Fast path: use cached value after first lookup
        if (initialized && cachedValue != null) {
            frame.setObject(slot, cachedValue)
            return cachedValue!!
        }

        val value = lookupImport()
        frame.setObject(slot, value)
        return value
    }

    @TruffleBoundary
    private fun lookupImport(): Any {
        val context = MezzeContext.get(this)

        // Prefer content hash lookup (O(1), collision-free)
        if (contentHash != null) {
            val value = context.lookupGlobal(contentHash)
            if (value != null) {
                // Cache the value for subsequent calls
                cachedValue = value
                initialized = true
                return value
            }
        }

        // Fall back to qualified name lookup
        val value = context.lookupGlobal(qualifiedName) ?: MezzeUnit.INSTANCE
        cachedValue = value
        initialized = true
        return value
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
 * Seq literal node that creates a MezzeList.
 */
@NodeInfo(shortName = "seq-literal")
class SeqLiteralNode(
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
        return MezzeList.fromArray(values as Array<Any>)
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
    private val contentHash: String,
    private val abilityName: String
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
        // Register by ability name for runtime ability lookups (legacy)
        context.registerAbilityImpl(abilityName, value)
        // Register by content hash for dictionary passing (JIT-friendly)
        context.registerAbilityImplByHash(contentHash, value)
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
    private val initializeScopeClosureSlots: IntArray = intArrayOf(),
    private val initializeScopeFrameDescriptor: FrameDescriptor? = null,
    private val finalizeScopeNode: MezzeNode? = null,
    private val finalizeScopeClosureSlots: IntArray = intArrayOf(),
    private val finalizeScopeFrameDescriptor: FrameDescriptor? = null
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
                initializeScopeClosureSlots = initializeScopeClosureSlots,
                initializeScopeFrameDescriptor = initializeScopeFrameDescriptor,
                finalizeScopeNode = finalizeScopeNode,
                finalizeScopeClosureSlots = finalizeScopeClosureSlots,
                finalizeScopeFrameDescriptor = finalizeScopeFrameDescriptor
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
    // Optional scoped effect nodes - wrapped into callable functions in the handler record
    @field:Child private var initializeScopeNode: MezzeNode? = null,
    @CompilationFinal(dimensions = 1)
    private val initializeScopeClosureSlots: IntArray = intArrayOf(),
    private val initializeScopeFrameDescriptor: FrameDescriptor? = null,
    @field:Child private var finalizeScopeNode: MezzeNode? = null,
    @CompilationFinal(dimensions = 1)
    private val finalizeScopeClosureSlots: IntArray = intArrayOf(),
    private val finalizeScopeFrameDescriptor: FrameDescriptor? = null
) : RootNode(language, frameDescriptor) {

    @CompilationFinal(dimensions = 1)
    private val implParamSlotIndices: IntArray = implParamNames.map { paramName ->
        implParamSlots[paramName] ?: -1
    }.toIntArray()

    @CompilationFinal
    private var initializeScopeCallTarget: CallTarget? = null

    @CompilationFinal
    private var finalizeScopeCallTarget: CallTarget? = null

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

        // Create initialize_scope function if present
        val initScopeFunc: MezzeFunction? = if (initializeScopeNode != null) {
            val closureValues = Array(initializeScopeClosureSlots.size) { i ->
                frame.getObject(initializeScopeClosureSlots[i])
            }
            MezzeFunction(
                getOrCreateInitScopeCallTarget(),
                "${implName}__initialize_scope",
                closureValues
            )
        } else null

        // Create finalize_scope function if present
        val finalizeScopeFunc: MezzeFunction? = if (finalizeScopeNode != null) {
            val closureValues = Array(finalizeScopeClosureSlots.size) { i ->
                frame.getObject(finalizeScopeClosureSlots[i])
            }
            MezzeFunction(
                getOrCreateFinalizeScopeCallTarget(),
                "${implName}__finalize_scope",
                closureValues
            )
        } else null

        return buildOperationRecord(operationFunctions, initScopeFunc, finalizeScopeFunc)
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
    private fun getOrCreateInitScopeCallTarget(): CallTarget {
        if (initializeScopeCallTarget == null && initializeScopeNode != null) {
            val closureCount = initializeScopeClosureSlots.size
            val frameDesc = initializeScopeFrameDescriptor ?: run {
                val builder = FrameDescriptor.newBuilder()
                for (i in 0 until closureCount) {
                    builder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, "closure_$i", null)
                }
                builder.build()
            }
            val rootNode = MezzeFunctionRootNode(
                MezzeLanguage.get(this),
                frameDesc,
                initializeScopeNode!!,
                "${implName}__initialize_scope",
                emptyArray(),  // No parameters - takes {}
                IntArray(closureCount) { it },
                null
            )
            initializeScopeCallTarget = rootNode.callTarget
        }
        return initializeScopeCallTarget!!
    }

    @TruffleBoundary
    private fun getOrCreateFinalizeScopeCallTarget(): CallTarget {
        if (finalizeScopeCallTarget == null && finalizeScopeNode != null) {
            val closureCount = finalizeScopeClosureSlots.size
            val frameDesc = finalizeScopeFrameDescriptor ?: run {
                val builder = FrameDescriptor.newBuilder()
                for (i in 0 until closureCount) {
                    builder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, "closure_$i", null)
                }
                builder.build()
            }
            val rootNode = MezzeFunctionRootNode(
                MezzeLanguage.get(this),
                frameDesc,
                finalizeScopeNode!!,
                "${implName}__finalize_scope",
                emptyArray(),  // No parameters - takes {}
                IntArray(closureCount) { it },
                null
            )
            finalizeScopeCallTarget = rootNode.callTarget
        }
        return finalizeScopeCallTarget!!
    }

    @TruffleBoundary
    private fun buildOperationRecord(
        operationFunctions: Array<MezzeFunction>,
        initScopeFunc: MezzeFunction?,
        finalizeScopeFunc: MezzeFunction?
    ): MezzeRecord {
        val fieldNamesList = operationNames.toMutableList()
        val valuesList = operationFunctions.toMutableList<Any>()

        // Add scope lifecycle functions under special names
        if (initScopeFunc != null) {
            fieldNamesList.add("__initialize_scope__")
            valuesList.add(initScopeFunc)
        }
        if (finalizeScopeFunc != null) {
            fieldNamesList.add("__finalize_scope__")
            valuesList.add(finalizeScopeFunc)
        }

        return MezzeRecord.fromFields(fieldNamesList.toTypedArray(), valuesList.toTypedArray())
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

            // LAZY EFFECTS: Push handlers onto handler stack for lazy effects
            pushHandlersToStack(handler)
        }

        try {
            val result = bodyChild.execute(frame)
            return processResult(result)
        } finally {
            // LAZY EFFECTS: Pop handlers from stack
            if (handler != null) {
                popHandlersFromStack()
            }
        }
    }

    /**
     * Push handlers onto the context's handler stack for lazy effects.
     */
    @TruffleBoundary
    private fun pushHandlersToStack(handler: Any) {
        val context = MezzeContext.get(this)
        val handlers = mutableMapOf<String, Any>()
        handlers[handlerName] = handler
        for (effectName in effectNames) {
            handlers[effectName] = handler
        }
        context.pushHandlers(handlers)
    }

    /**
     * Pop handlers from the context's handler stack.
     */
    @TruffleBoundary
    private fun popHandlersFromStack() {
        val context = MezzeContext.get(this)
        context.popHandlers()
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

            // LAZY EFFECTS: Push handlers onto handler stack
            pushHandlersToStack(handler)
        }

        try {
            val result = bodyChild.execute(frame)
            return processResult(result)
        } catch (e: ForkedExecutionException) {
            // Fork completed - return the forked values as the result
            return e.values
        } finally {
            // LAZY EFFECTS: Pop handlers from stack
            if (handler != null) {
                popHandlersFromStack()
            }
        }
    }

    /**
     * Push handlers onto the context's handler stack for lazy effects.
     */
    @TruffleBoundary
    private fun pushHandlersToStack(handler: Any) {
        val context = MezzeContext.get(this)
        val handlers = mutableMapOf<String, Any>()
        handlers[handlerName] = handler
        for (effectName in effectNames) {
            handlers[effectName] = handler
        }
        context.pushHandlers(handlers)
    }

    /**
     * Pop handlers from the context's handler stack.
     */
    @TruffleBoundary
    private fun popHandlersFromStack() {
        val context = MezzeContext.get(this)
        context.popHandlers()
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
 * Handle node for parameterized effect impls with scoped effects (initialize_scope/finalize_scope).
 *
 * Combines parameterized impl invocation with scoped effect lifecycle:
 * 1. Evaluate parameters
 * 2. Call parameterized impl function to get handler record (which includes __initialize_scope__ and __finalize_scope__)
 * 3. Call __initialize_scope__ from handler record
 * 4. Execute body
 * 5. Call __finalize_scope__ from handler record (in finally block)
 *
 * If finalize_scope throws RetryingScopeException, the entire block is retried
 * (steps 3-5) up to maxRetries times. This enables STM-style automatic retry.
 *
 * The key insight is that initialize_scope and finalize_scope are included in the handler record
 * as callable MezzeFunctions with the impl parameters captured in their closures. This ensures
 * they have access to the impl parameters (like `seed` in SeededRandom).
 */
@NodeInfo(shortName = "parameterized-scoped-handle")
class ParameterizedScopedHandleNode(
    private val handlerName: String,
    private val effectNames: Array<String>,
    private val contentHashes: Array<String>,
    @field:Child private var parametersNode: MezzeNode,
    @field:Child private var bodyChild: MezzeNode,
    private val scopeSlot: Int,
    // These nodes are kept for backward compatibility but won't be used
    // when handler record contains __initialize_scope__ and __finalize_scope__
    @field:Child private var initializeScopeNode: MezzeNode?,
    @field:Child private var finalizeScopeNode: MezzeNode?
) : MezzeNode() {

    @field:Child private var callNode: com.oracle.truffle.api.nodes.IndirectCallNode =
        com.oracle.truffle.api.nodes.IndirectCallNode.create()

    @field:Child private var initScopeCallNode: com.oracle.truffle.api.nodes.IndirectCallNode =
        com.oracle.truffle.api.nodes.IndirectCallNode.create()

    @field:Child private var finalizeScopeCallNode: com.oracle.truffle.api.nodes.IndirectCallNode =
        com.oracle.truffle.api.nodes.IndirectCallNode.create()

    override fun execute(frame: VirtualFrame): Any {
        // Evaluate parameters first (only once, not on retry)
        val parameters = parametersNode.execute(frame)

        // Get the impl function and call it with parameters (only once, not on retry)
        // The handler record now includes __initialize_scope__ and __finalize_scope__ functions
        val handler = getAndCallImplFunction(parameters)
        val handlerRecord = handler as? MezzeRecord

        // Extract scope lifecycle functions from handler record (if present)
        val initScopeFunc = handlerRecord?.getField("__initialize_scope__") as? MezzeFunction
        val finalizeScopeFunc = handlerRecord?.getField("__finalize_scope__") as? MezzeFunction

        if (handler != null) {
            // Install handler by name and effect names
            val context = MezzeContext.get(this)
            context.defineLocal(handlerName, handler)

            for (effectName in effectNames) {
                context.defineLocal(effectName, handler)
            }

            // LAZY EFFECTS: Push handlers onto handler stack
            pushHandlersToStack(handler)
        }

        try {
            return executeWithRetry(frame, handler, handlerRecord, initScopeFunc, finalizeScopeFunc)
        } finally {
            // LAZY EFFECTS: Pop handlers from stack
            if (handler != null) {
                popHandlersFromStack()
            }
        }
    }

    private fun executeWithRetry(
        frame: VirtualFrame,
        handler: Any?,
        handlerRecord: MezzeRecord?,
        initScopeFunc: MezzeFunction?,
        finalizeScopeFunc: MezzeFunction?
    ): Any {
        // Retry loop for scoped effects that need retry (e.g., STM)
        var retryCount = 0
        var maxRetries = 100 // Default max retries
        val random = java.util.concurrent.ThreadLocalRandom.current()

        while (true) {
            // Execute initialize_scope if present
            var scope: Any? = null

            // Prefer calling from handler record (has impl params in closure)
            if (initScopeFunc != null) {
                scope = callScopeFunction(initScopeCallNode, initScopeFunc)
            } else if (initializeScopeNode != null) {
                // Fallback to direct node execution (for backward compatibility)
                scope = initializeScopeNode!!.execute(frame)
            }

            if (scope != null) {
                // Store scope in frame slot so operations can access it
                if (scopeSlot >= 0 && scopeSlot < frame.frameDescriptor.numberOfSlots) {
                    frame.setObject(scopeSlot, scope)
                }

                // Also register scope in context under both names:
                // - "scope" for user code references
                // - "__scope__" for internal use
                val context = MezzeContext.get(this)
                context.defineLocal("scope", scope)
                context.defineLocal("__scope__", scope)
            }

            var bodyException: Throwable? = null
            var result: Any? = null
            var needsRetry = false

            try {
                result = bodyChild.execute(frame)
            } catch (t: Throwable) {
                bodyException = t
            } finally {
                // Execute finalize_scope in finally block
                var finalizeException: Throwable? = null

                // Prefer calling from handler record (has impl params in closure)
                if (finalizeScopeFunc != null) {
                    try {
                        callScopeFunction(finalizeScopeCallNode, finalizeScopeFunc)
                    } catch (retry: com.mezze.interpreter.v2.nodes.effect.RetryingScopeException) {
                        if (bodyException == null) {
                            needsRetry = true
                            maxRetries = if (retry.maxRetries > 0) retry.maxRetries else maxRetries
                        }
                    } catch (t: Throwable) {
                        finalizeException = t
                    }
                } else if (finalizeScopeNode != null) {
                    // Fallback to direct node execution
                    try {
                        finalizeScopeNode!!.execute(frame)
                    } catch (retry: com.mezze.interpreter.v2.nodes.effect.RetryingScopeException) {
                        if (bodyException == null) {
                            needsRetry = true
                            maxRetries = if (retry.maxRetries > 0) retry.maxRetries else maxRetries
                        }
                    } catch (t: Throwable) {
                        finalizeException = t
                    }
                }

                // Clear scope from frame
                if (scopeSlot >= 0 && scopeSlot < frame.frameDescriptor.numberOfSlots) {
                    frame.setObject(scopeSlot, null)
                }

                // Handle exceptions (only if not retrying)
                if (!needsRetry) {
                    if (bodyException != null) {
                        if (finalizeException != null && finalizeException !== bodyException) {
                            bodyException.addSuppressed(finalizeException)
                        }
                        when (bodyException) {
                            is RuntimeException -> throw bodyException
                            is Error -> throw bodyException
                            else -> throw RuntimeException(bodyException)
                        }
                    } else if (finalizeException != null) {
                        when (finalizeException) {
                            is RuntimeException -> throw finalizeException
                            is Error -> throw finalizeException
                            else -> throw RuntimeException(finalizeException)
                        }
                    }
                }
            }

            // Check if we need to retry
            if (needsRetry) {
                retryCount++
                if (maxRetries > 0 && retryCount >= maxRetries) {
                    throw RuntimeException("Scoped effect retry limit exceeded ($maxRetries retries)")
                }
                // Exponential backoff with jitter to reduce contention
                // Base: 10 microseconds, doubles each retry, capped at 10ms
                // Jitter: random factor 0.5-1.5 to prevent thundering herd
                if (retryCount > 3) {
                    val baseDelayMicros = 10L * (1L shl minOf(retryCount - 3, 10)) // 10us * 2^n, capped
                    val maxDelayMicros = minOf(baseDelayMicros, 10_000L) // Cap at 10ms
                    val jitter = 0.5 + random.nextDouble() // 0.5 to 1.5
                    val delayNanos = (maxDelayMicros * jitter * 1000).toLong()
                    java.util.concurrent.locks.LockSupport.parkNanos(delayNanos)
                } else {
                    // First few retries: just yield to let other threads proceed
                    Thread.yield()
                }
                // Continue the loop to retry
                continue
            }

            // Success - return the result
            return processResult(result!!)
        }
    }

    @TruffleBoundary
    private fun callScopeFunction(
        scopeCallNode: com.oracle.truffle.api.nodes.IndirectCallNode,
        func: MezzeFunction
    ): Any {
        val closureValues = func.closureValues
        val emptyRecord = MezzeRecord.empty()
        val arguments = if (closureValues.isEmpty()) {
            arrayOf(emptyRecord)
        } else {
            Array(closureValues.size + 1) { i ->
                if (i < closureValues.size) closureValues[i] else emptyRecord
            }
        }
        return scopeCallNode.call(func.callTarget, *arguments)
    }

    @TruffleBoundary
    private fun processResult(result: Any): Any {
        if (result is MezzeContinuationSuspended) {
            if (effectNames.contains("Exception") || effectNames.contains("Return")) {
                return result.suspendedValue
            }
            return result
        }

        if (effectNames.contains("Exception")) {
            return MezzeVariant.ok(result)
        }

        return result
    }

    @TruffleBoundary
    private fun getAndCallImplFunction(parameters: Any): Any? {
        val context = MezzeContext.get(this)

        for (hash in contentHashes) {
            val implFunc = context.lookupGlobal(hash)
            if (implFunc is MezzeFunction) {
                val closureValues = implFunc.closureValues
                val arguments = if (closureValues.isEmpty()) {
                    arrayOf(parameters)
                } else {
                    Array(closureValues.size + 1) { i ->
                        if (i < closureValues.size) closureValues[i] else parameters
                    }
                }
                return callNode.call(implFunc.callTarget, *arguments)
            } else if (implFunc is MezzeRecord) {
                return implFunc
            }
        }

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
            return callNode.call(implFunc.callTarget, *arguments)
        } else if (implFunc is MezzeRecord) {
            return implFunc
        }

        throw RuntimeException("Parameterized impl not found for: $handlerName")
    }

    /**
     * Push handlers onto the context's handler stack for lazy effects.
     */
    @TruffleBoundary
    private fun pushHandlersToStack(handler: Any) {
        val context = MezzeContext.get(this)
        val handlers = mutableMapOf<String, Any>()
        handlers[handlerName] = handler
        for (effectName in effectNames) {
            handlers[effectName] = handler
        }
        context.pushHandlers(handlers)
    }

    /**
     * Pop handlers from the context's handler stack.
     */
    @TruffleBoundary
    private fun popHandlersFromStack() {
        val context = MezzeContext.get(this)
        context.popHandlers()
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
