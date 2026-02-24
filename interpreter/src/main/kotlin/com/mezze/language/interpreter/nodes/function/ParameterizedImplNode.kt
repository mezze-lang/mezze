package com.mezze.language.interpreter.nodes.function

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.RootNode
import com.mezze.language.interpreter.MezzeLanguage
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeTruffleFunction
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.nodes.MezzeNode

/**
 * Node that creates a parameterized impl function.
 *
 * At execute() time, returns a MezzeTruffleFunction that, when called with
 * impl parameters like {cont: value}, returns a record of operation functions.
 *
 * This is Truffle-compliant because:
 * - All FrameDescriptors are created at parse time
 * - No frame materialization happens in execute()
 * - Uses the standard FunctionDefinitionNode pattern for closure capture
 *
 * Example:
 *   impl Choice for AllPaths { cont: Continuation } where { ... }
 *
 * When AllPaths {cont = value} is called:
 *   1. ParameterizedImplRootNode.execute() binds 'cont' to a frame slot
 *   2. Each operation's FunctionDefinitionNode executes, capturing 'cont' as closure variable
 *   3. Returns MezzeRecord of operation functions with captured closures
 */
@NodeInfo(shortName = "parameterized-impl-def")
class ParameterizedImplDefinitionNode(
    private val implName: String,
    private val implParamNames: List<String>,
    private val implParamSlots: Map<String, Int>,
    operationNames: List<String>,
    operationBodyNodes: List<MezzeNode>,
    operationParamNames: List<List<String>>,
    operationFrameDescriptors: List<FrameDescriptor>,
    operationClosureVarNames: List<List<String>>,
    operationClosureVarSlots: List<Map<String, Int>>,
    private val implFrameDescriptor: FrameDescriptor
) : MezzeNode() {

    // Store operation info for RootNode construction
    private val operationNamesArray = operationNames.toTypedArray()
    private val operationBodyNodesArray = operationBodyNodes.toTypedArray()
    private val operationParamNamesArray = operationParamNames.toTypedArray()
    private val operationFrameDescriptorsArray = operationFrameDescriptors.toTypedArray()
    private val operationClosureVarNamesArray = operationClosureVarNames.toTypedArray()
    private val operationClosureVarSlotsArray = operationClosureVarSlots.toTypedArray()

    // Lazily create the CallTarget to avoid constructor issues with getLanguage()
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private var _callTarget: CallTarget? = null

    /**
     * Get or create the CallTarget. Marked as TruffleBoundary to ensure
     * RootNode creation happens outside the JIT compilation path.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun getOrCreateCallTarget(): CallTarget {
        if (_callTarget == null) {
            val rootNode = ParameterizedImplRootNode(
                language = getLanguage(),
                implName = implName,
                implParamNames = implParamNames,
                implParamSlots = implParamSlots,
                operationNames = operationNamesArray,
                operationBodyNodes = operationBodyNodesArray,
                operationParamNames = operationParamNamesArray,
                operationFrameDescriptors = operationFrameDescriptorsArray,
                operationClosureVarNames = operationClosureVarNamesArray,
                operationClosureVarSlots = operationClosureVarSlotsArray,
                frameDescriptor = implFrameDescriptor
            )
            _callTarget = rootNode.callTarget
        }
        return _callTarget!!
    }

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Return the impl as a MezzeTruffleFunction
        // When called with {cont = value}, it returns a handler record
        return MezzeTruffleFunction(
            callTarget = getOrCreateCallTarget(),
            name = "impl_$implName",
            closureVariables = emptyMap(),
            lexicalScope = null,
            lexicalScopeSlotMap = emptyMap()
        )
    }
}

/**
 * RootNode for executing a parameterized impl.
 * Called with impl parameters, returns a record of operation functions.
 *
 * The frame layout:
 *   slot 0..n-1: impl parameters (e.g., 'cont')
 *
 * When executed:
 *   1. Binds impl params from args to frame slots
 *   2. Executes FunctionDefinitionNodes which read closure vars from frame
 *   3. Returns record of operation functions with captured closures
 */
class ParameterizedImplRootNode(
    language: MezzeLanguage,
    private val implName: String,
    private val implParamNames: List<String>,
    implParamSlots: Map<String, Int>,
    private val operationNames: Array<String>,
    operationBodyNodes: Array<MezzeNode>,
    operationParamNames: Array<List<String>>,
    operationFrameDescriptors: Array<FrameDescriptor>,
    operationClosureVarNames: Array<List<String>>,
    operationClosureVarSlots: Array<Map<String, Int>>,
    frameDescriptor: FrameDescriptor
) : RootNode(language, frameDescriptor) {

    // Pre-compute slot indices at construction time to avoid Map lookups during execution
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val implParamSlotIndices: IntArray = implParamNames.map { paramName ->
        implParamSlots[paramName] ?: -1
    }.toIntArray()

    // Pre-create FunctionDefinitionNodes for each operation as @Children
    // This ensures Truffle properly adopts them into the AST
    @field:Children
    private val operationFuncNodes: Array<FunctionDefinitionNode> =
        operationNames.indices.map { i ->
            FunctionDefinitionNode(
                name = "${implName}_${operationNames[i]}",
                parameterNames = operationParamNames[i],
                bodyNode = operationBodyNodes[i],
                frameDescriptor = operationFrameDescriptors[i],
                closureVariableNames = operationClosureVarNames[i],
                closureVariableSlots = operationClosureVarSlots[i]
            )
        }.toTypedArray()

    override fun execute(frame: VirtualFrame): Any {
        // Arguments: [0] = MezzeRecord of impl parameters
        val arguments = frame.arguments
        val paramsRecord = if (arguments.isNotEmpty()) {
            arguments[0] as? MezzeRecord ?: MezzeRecord(emptyMap())
        } else {
            MezzeRecord(emptyMap())
        }

        // Extract impl parameters via TruffleBoundary to avoid Map operations in JIT path
        val paramValues = extractImplParamValues(paramsRecord)
        // Bind using pre-computed slot indices
        for (index in implParamNames.indices) {
            val slot = implParamSlotIndices[index]
            if (slot >= 0) {
                frame.setObject(slot, paramValues[index])
            }
        }

        // Execute each operation FunctionDefinitionNode to create operation functions
        // The FunctionDefinitionNode.execute() will capture impl params as closure variables
        // from the frame slots we just populated
        val operationFunctions = Array(operationNames.size) { index ->
            operationFuncNodes[index].execute(frame)
        }

        // Build the record via TruffleBoundary to avoid Map operations in JIT path
        return buildOperationRecord(operationFunctions)
    }

    /**
     * Extract impl parameter values from the params record.
     * Marked as TruffleBoundary because MezzeRecord.fields is a Map.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun extractImplParamValues(paramsRecord: MezzeRecord): Array<MezzeValue> {
        return Array(implParamNames.size) { index ->
            val paramName = implParamNames[index]
            paramsRecord.fields[paramName] ?: MezzeNull
        }
    }

    /**
     * Build the operation record from pre-executed operation functions.
     * Marked as TruffleBoundary because this involves Map creation.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun buildOperationRecord(operationFunctions: Array<MezzeValue>): MezzeRecord {
        val operationFields = mutableMapOf<String, MezzeValue>()
        for ((index, opName) in operationNames.withIndex()) {
            operationFields[opName] = operationFunctions[index]
        }
        return MezzeRecord(operationFields)
    }

    override fun getName(): String = "impl_$implName"
}
