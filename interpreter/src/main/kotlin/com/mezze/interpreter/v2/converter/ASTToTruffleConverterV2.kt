package com.mezze.interpreter.v2.converter

import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.mezze.language.ast.*
import com.mezze.interpreter.v2.nodes.MezzeNode
import com.mezze.interpreter.v2.nodes.control.BlockNode
import com.mezze.interpreter.v2.nodes.control.IfNode
import com.mezze.interpreter.v2.nodes.literal.*
import com.mezze.interpreter.v2.nodes.variable.ReadLocalNode
import com.mezze.interpreter.v2.nodes.variable.ReadLocalWithFallbackNode
import com.mezze.interpreter.v2.nodes.variable.WriteLocalNode
import com.mezze.interpreter.v2.nodes.variable.WriteLocalWithContextNode
import com.mezze.interpreter.v2.nodes.function.FunctionDefinitionNode
import com.mezze.interpreter.v2.nodes.function.InvokeNode
import com.mezze.interpreter.v2.nodes.function.MonomorphicCallNode
import com.mezze.interpreter.v2.nodes.function.TailCallNode
import com.mezze.interpreter.v2.nodes.function.TailCallLoopNode
import com.mezze.interpreter.v2.nodes.record.RecordLiteralNode
import com.mezze.interpreter.v2.nodes.record.RecordSpreadNode
import com.mezze.interpreter.v2.nodes.record.RecordFieldAccessNode
import com.mezze.interpreter.v2.nodes.variant.VariantConstructNode
import com.mezze.interpreter.v2.nodes.variant.MatchNode
import com.mezze.interpreter.v2.nodes.variant.MatchBranchNode
import com.mezze.interpreter.v2.nodes.control.IfLetBindingNode
import com.mezze.interpreter.v2.nodes.array.ArrayAccessNode
import com.mezze.interpreter.v2.nodes.array.ArrayLiteralNode as OptimizedArrayLiteralNode
import com.mezze.interpreter.v2.nodes.array.ArraySliceNode
import com.mezze.interpreter.v2.nodes.array.ArraySpreadNode
import com.mezze.interpreter.v2.nodes.effect.HandleNode
import com.mezze.interpreter.v2.nodes.effect.DynamicEffectCallNode
import com.mezze.interpreter.v2.nodes.builtin.BuiltinBinaryNodes.*
import com.mezze.interpreter.v2.nodes.builtin.BuiltinUnaryNodes.*
import com.mezze.interpreter.v2.nodes.builtin.NativeFunctions
import com.mezze.interpreter.v2.nodes.inline.*
import com.mezze.interpreter.v2.nodes.ability.ResolvedAbilityCallNode as OptimizedAbilityCallNode
import com.mezze.interpreter.v2.nodes.ability.DictionaryAbilityCallNode
import com.mezze.interpreter.v2.nodes.ability.DirectDictionaryCallNode
import com.mezze.interpreter.v2.nodes.function.RuntimeAbilityCallNode
import com.mezze.interpreter.v2.nodes.function.DictionaryPassingInvokeNode
import com.mezze.interpreter.v2.values.MezzeFunction
import com.mezze.interpreter.v2.nodes.continuation.*
import com.mezze.interpreter.v2.nodes.statemachine.StateMachineNode
import com.mezze.interpreter.v2.runtime.MezzeContext
import com.mezze.language.typechecker.scope.detectScopedEffectMembers
import com.mezze.language.typechecker.scope.ScopedEffectConstants

/**
 * Converts Mezze AST to optimized Truffle V2 nodes.
 *
 * Design principles:
 * 1. Uses typed frame slots based on inferredType (KIND_LONG, KIND_DOUBLE, KIND_BOOLEAN, KIND_OBJECT)
 * 2. Uses content-hash for ability/impl dispatch (StaticAbilityCallNode)
 * 3. NO @TruffleBoundary in hot paths
 * 4. Monomorphic function calls via resolvedFunctionName
 */
class ASTToTruffleConverterV2(
    private val context: MezzeContext? = null,
    parentHandlerMapping: Map<String, Pair<String, String>>? = null,
    private val parentVariableSlots: Map<String, Int>? = null,
    parentScopedEffectInfo: Map<String, ScopedImplInfo>? = null
) {

    private var frameDescriptorBuilder = FrameDescriptor.newBuilder()

    /**
     * Combined slot info - stores slot index, kind, and various flags in a single lookup.
     * Reduces multiple map lookups to 1 for each variable access.
     *
     * needsFallback: Set for variables that need fallback global lookup because they're
     * from an outer scope (module level, or referenced before being defined in current scope)
     */
    data class SlotInfo(
        val slot: Int,
        val kind: Int,
        val isClosure: Boolean = false,
        val isTopLevel: Boolean = false,
        val needsFallback: Boolean = false
    )

    private val variableInfo = mutableMapOf<String, SlotInfo>()

    private val handlerToEffectMapping = mutableMapOf<String, Pair<String, String>>().apply {
        parentHandlerMapping?.let { putAll(it) }
    }

    private val contentHashToSlot = mutableMapOf<String, Int>()

    /**
     * Tracks scoped effect info for impls by content hash.
     * Maps contentHash -> ScopedImplInfo
     */
    data class ScopedImplInfo(
        val isScopedEffect: Boolean,
        val initializeScopeNode: MezzeNode?,
        val finalizeScopeNode: MezzeNode?,
        val scopeSlot: Int
    )
    private val scopedEffectInfo = mutableMapOf<String, ScopedImplInfo>().apply {
        parentScopedEffectInfo?.let { putAll(it) }
    }

    /**
     * Tracks ability dictionary operation slots for polymorphic functions.
     *
     * When a function has ability constraints like `with Encode Json 'a`,
     * and calls operations like `Encode.encode`, we need to pass the
     * resolved implementation's operation function via closure.
     *
     * Key: (constraintIndex, operationName) - identifies which constraint and which operation
     * Value: frame slot index where the operation function will be stored
     *
     * The constraintIndex corresponds to the position in the function's abilityConstraints list.
     * At call sites, we map resolved abilities (by content hash) to the correct slot.
     */
    data class AbilityOperationKey(val constraintIndex: Int, val operationName: String)
    private val abilityOperationSlots = mutableMapOf<AbilityOperationKey, Int>()

    /**
     * Maps constraint index to its expected ability name.
     * Used to match resolved abilities at call sites to the correct constraint slots.
     */
    private val constraintAbilityNames = mutableListOf<String>()

    /**
     * Find or create a slot for an ability operation.
     * Returns the slot index for the operation function.
     */
    private fun getOrCreateAbilityOperationSlot(constraintIndex: Int, operationName: String): Int {
        val key = AbilityOperationKey(constraintIndex, operationName)
        return abilityOperationSlots.getOrPut(key) {
            frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
        }
    }

    /**
     * Get the slot for an ability operation if it exists.
     * Returns null if no slot has been allocated for this operation.
     */
    private fun findAbilityOperationSlot(constraintIndex: Int, operationName: String): Int? {
        return abilityOperationSlots[AbilityOperationKey(constraintIndex, operationName)]
    }

    /**
     * Get all ability operation slots in order for closure capture.
     * Returns list of (constraintIndex, operationName, slotIndex) tuples.
     */
    private fun getAbilityOperationSlotsOrdered(): List<Triple<Int, String, Int>> {
        return abilityOperationSlots.entries
            .sortedBy { it.value }  // Sort by slot index for deterministic order
            .map { Triple(it.key.constraintIndex, it.key.operationName, it.value) }
    }

    /**
     * Convert a program to a Truffle node.
     */
    fun convertProgram(program: Program): MezzeNode {
        val itemNodes = program.items.map { convertProgramItem(it) }.toTypedArray()
        return BlockNode(itemNodes)
    }

    private fun convertProgramItem(item: ProgramItem): MezzeNode {
        return when (item) {
            is LetBinding -> convertLetBinding(item)
            is TopLevelExpression -> convertExpression(item.expression)
            is TypeDeclaration -> UnitLiteralNode()
            is EffectDeclaration -> UnitLiteralNode()
            is ImplDeclaration -> convertImplDeclaration(item)
            is AbilityDeclaration -> UnitLiteralNode()
            is ImplAbilityDeclaration -> convertImplAbilityDeclaration(item)
            is UseDeclaration -> convertUseDeclaration(item)
            is PubUseDeclaration -> convertPubUseDeclaration(item)
            else -> throw RuntimeException("Unsupported program item: ${item::class.simpleName}")
        }
    }

    private fun convertUseDeclaration(useDecl: UseDeclaration): MezzeNode {
        return when (val usePath = useDecl.path) {
            is SelectivePath -> {
                val modulePath = usePath.module.joinToString("::")
                val bindingNodes = usePath.items.map { importItem ->
                    val importedName = importItem.name
                    val localName = importItem.alias ?: importedName
                    val slot = getOrCreateSlot(localName)
                    val qualifiedImportName = "$modulePath::$importedName"
                    UseBindingNode(slot, qualifiedImportName, localName)
                }
                if (bindingNodes.isEmpty()) {
                    UnitLiteralNode()
                } else {
                    BlockNode(bindingNodes.toTypedArray())
                }
            }
            is ModulePath -> UnitLiteralNode()
        }
    }

    private fun convertPubUseDeclaration(pubUse: PubUseDeclaration): MezzeNode {
        val equiv = UseDeclaration(pubUse.path, pubUse.alias)
        return convertUseDeclaration(equiv)
    }

    private fun convertImplDeclaration(impl: ImplDeclaration): MezzeNode {
        if (impl.performerParams.isNotEmpty()) {
            return convertParameterizedImplDeclaration(impl)
        }

        // Detect scoped effects (initialize_scope / finalize_scope)
        val scopedInfo = detectScopedEffectMembers(impl)

        val fieldNames = mutableListOf<String>()
        val fieldValueNodes = mutableListOf<MezzeNode>()

        // Convert regular operations (excluding initialize_scope and finalize_scope)
        for (op in scopedInfo.regularOperations) {
            fieldNames.add(op.name)
            fieldValueNodes.add(convertExpression(op.implementation))
        }

        val recordNode = RecordLiteralNode.allObjects(
            fieldNames.toTypedArray(),
            fieldValueNodes.toTypedArray()
        )

        val contentHash = impl.contentHash
            ?: throw RuntimeException("Content hash required for effect handler: ${impl.effectName}/${impl.performerName}")

        handlerToEffectMapping[impl.performerName] = impl.effectName to contentHash

        val hashSlot = getOrCreateSlot(contentHash)
        contentHashToSlot[contentHash] = hashSlot
        val performerSlot = getOrCreateSlot(impl.performerName)

        // Track scoped effect info for use in convertPerformExpression
        if (scopedInfo.isScopedEffect) {
            val scopeSlot = getOrCreateSlot("__scope_${contentHash}__")

            // initialize_scope and finalize_scope are lambdas: {} -> ...
            // We need to invoke them with {} as argument to get the actual scope/unit
            val initNode = scopedInfo.initializeScope?.let { op ->
                val lambdaNode = convertExpression(op.implementation)
                val emptyRecordArg = RecordLiteralNode.allObjects(emptyArray(), emptyArray())
                InvokeNode.create(lambdaNode, arrayOf(emptyRecordArg))
            }
            val finalizeNode = scopedInfo.finalizeScope?.let { op ->
                val lambdaNode = convertExpression(op.implementation)
                val emptyRecordArg = RecordLiteralNode.allObjects(emptyArray(), emptyArray())
                InvokeNode.create(lambdaNode, arrayOf(emptyRecordArg))
            }

            scopedEffectInfo[contentHash] = ScopedImplInfo(
                isScopedEffect = true,
                initializeScopeNode = initNode,
                finalizeScopeNode = finalizeNode,
                scopeSlot = scopeSlot
            )
        }

        return EffectImplStorageNode(
            recordChild = recordNode,
            hashSlot = hashSlot,
            performerSlot = performerSlot,
            contentHash = contentHash,
            performerName = impl.performerName,
            effectName = impl.effectName
        )
    }

    private fun convertParameterizedImplDeclaration(impl: ImplDeclaration): MezzeNode {
        val contentHash = impl.contentHash
            ?: throw RuntimeException("Content hash required for parameterized effect handler")

        handlerToEffectMapping[impl.performerName] = impl.effectName to contentHash

        val implParamNames = impl.performerParams.map { it.name }

        val implFrameBuilder = FrameDescriptor.newBuilder()
        val implParamSlots = mutableMapOf<String, Int>()

        for (paramName in implParamNames) {
            val slot = implFrameBuilder.addSlot(FrameSlotKind.Object, paramName, null)
            implParamSlots[paramName] = slot
        }
        val implFrameDescriptor = implFrameBuilder.build()

        // Detect scoped effects (initialize_scope / finalize_scope)
        val scopedInfo = detectScopedEffectMembers(impl)

        val operationNames = mutableListOf<String>()
        val operationBodyNodes = mutableListOf<MezzeNode>()
        val operationParamNamesList = mutableListOf<List<String>>()
        val operationFrameDescriptors = mutableListOf<FrameDescriptor>()
        val operationClosureVarSlots = mutableListOf<IntArray>()

        // Process regular operations only (excluding initialize_scope and finalize_scope)
        for (op in scopedInfo.regularOperations) {
            val opExpr = op.implementation
            if (opExpr !is LambdaExpression) {
                throw RuntimeException("Operation implementation must be a lambda for parameterized impl")
            }

            val opParamNames = when (val params = opExpr.parameters) {
                is UntypedParameters -> params.names
                is TypedParameters -> params.recordType.entries.map { it.name }
            }

            val allVars = collectVariableReferences(opExpr.body)
            val freeVars = allVars.filter { it !in opParamNames }.distinct()

            // Only vars that are impl params become closures - others use GlobalLookup
            val closureVars = freeVars.filter { it in implParamSlots.keys }

            val opConverter = ASTToTruffleConverterV2(
                context = this.context,
                parentHandlerMapping = this.handlerToEffectMapping,
                parentVariableSlots = implParamSlots.toMap(),
                parentScopedEffectInfo = this.scopedEffectInfo.toMap()
            )

            // IMPORTANT: Closure vars FIRST (slots 0..closureCount-1), then params (closureCount..)
            // This matches MezzeFunctionRootNode's argument binding order
            // Only create slots for actual closure vars (impl params), not globals like __native_*
            closureVars.forEach { varName ->
                opConverter.getOrCreateSlot(varName)
            }
            opParamNames.forEach { paramName ->
                opConverter.getOrCreateSlot(paramName)
            }

            val bodyNode = opConverter.convertExpression(opExpr.body)
            val opFrameDesc = opConverter.getFrameDescriptor()

            val closureSlots = closureVars.mapNotNull { varName ->
                implParamSlots[varName]
            }.toIntArray()

            operationNames.add(op.name)
            operationBodyNodes.add(bodyNode)
            operationParamNamesList.add(opParamNames)
            operationFrameDescriptors.add(opFrameDesc)
            operationClosureVarSlots.add(closureSlots)
        }

        // Convert initialize_scope for scoped effects (runs when impl is instantiated)
        var initializeScopeNode: MezzeNode? = null
        var initializeScopeClosureSlots = intArrayOf()
        var initializeScopeFrameDescriptor: FrameDescriptor? = null

        // Convert finalize_scope for scoped effects
        var finalizeScopeNode: MezzeNode? = null
        var finalizeScopeClosureSlots = intArrayOf()
        var finalizeScopeFrameDescriptor: FrameDescriptor? = null

        if (scopedInfo.isScopedEffect) {
            val initOp = scopedInfo.initializeScope
            if (initOp != null) {
                val initExpr = initOp.implementation
                if (initExpr is LambdaExpression) {
                    // Add impl params as closure vars for the lambda body
                    val allVars = collectVariableReferences(initExpr.body)
                    val closureVars = allVars.filter { it in implParamSlots.keys }.distinct()

                    // Create converter with impl params available
                    val initConverter = ASTToTruffleConverterV2(
                        context = this.context,
                        parentHandlerMapping = this.handlerToEffectMapping,
                        parentVariableSlots = implParamSlots.toMap(),
                        parentScopedEffectInfo = this.scopedEffectInfo.toMap()
                    )

                    // First, create slots for closure vars (they come first in the frame)
                    closureVars.forEach { varName ->
                        initConverter.getOrCreateSlot(varName)
                    }

                    // Convert the expression body - this may add additional slots for other vars
                    initializeScopeNode = initConverter.convertExpression(initExpr.body)

                    // Map closure var names to their slot indices in implParamSlots (source slots)
                    initializeScopeClosureSlots = closureVars.mapNotNull { varName ->
                        implParamSlots[varName]
                    }.toIntArray()

                    // Use the converter's frame descriptor which includes ALL slots
                    initializeScopeFrameDescriptor = initConverter.getFrameDescriptor()
                } else {
                    initializeScopeNode = convertExpression(initExpr)
                }
            }

            // Convert finalize_scope
            // NOTE: finalize_scope can reference "scope" which is provided at runtime via context
            // We need to ensure "scope" references use context lookup, not local slot lookup
            val finalOp = scopedInfo.finalizeScope
            if (finalOp != null) {
                val finalExpr = finalOp.implementation
                if (finalExpr is LambdaExpression) {
                    val allVars = collectVariableReferences(finalExpr.body)
                    // Filter out "scope" from closure vars - it will be looked up from context
                    val closureVars = allVars.filter { it in implParamSlots.keys && it != "scope" }.distinct()

                    val finalConverter = ASTToTruffleConverterV2(
                        context = this.context,
                        parentHandlerMapping = this.handlerToEffectMapping,
                        parentVariableSlots = implParamSlots.toMap(),
                        parentScopedEffectInfo = this.scopedEffectInfo.toMap()
                    )

                    // First, create slots for closure vars (they come first in the frame)
                    closureVars.forEach { varName ->
                        finalConverter.getOrCreateSlot(varName)
                    }

                    // Convert the expression body - this may add additional slots for fallback vars like "scope"
                    finalizeScopeNode = finalConverter.convertExpression(finalExpr.body)

                    // Map closure var names to their slot indices in implParamSlots (source slots)
                    finalizeScopeClosureSlots = closureVars.mapNotNull { varName ->
                        implParamSlots[varName]
                    }.toIntArray()

                    // Use the converter's frame descriptor which includes ALL slots
                    // (both closure vars and fallback vars like "scope")
                    finalizeScopeFrameDescriptor = finalConverter.getFrameDescriptor()
                } else {
                    finalizeScopeNode = convertExpression(finalExpr)
                }
            }
        }

        val implDefinitionNode = ParameterizedImplDefinitionNode(
            implName = impl.performerName,
            implParamNames = implParamNames,
            implParamSlots = implParamSlots,
            operationNames = operationNames,
            operationBodyNodes = operationBodyNodes,
            operationParamNames = operationParamNamesList,
            operationFrameDescriptors = operationFrameDescriptors,
            operationClosureVarSlots = operationClosureVarSlots,
            implFrameDescriptor = implFrameDescriptor,
            initializeScopeNode = initializeScopeNode,
            initializeScopeClosureSlots = initializeScopeClosureSlots,
            initializeScopeFrameDescriptor = initializeScopeFrameDescriptor,
            finalizeScopeNode = finalizeScopeNode,
            finalizeScopeClosureSlots = finalizeScopeClosureSlots,
            finalizeScopeFrameDescriptor = finalizeScopeFrameDescriptor
        )

        val hashSlot = getOrCreateSlot(contentHash)
        contentHashToSlot[contentHash] = hashSlot
        val performerSlot = getOrCreateSlot(impl.performerName)

        // Track scoped effect info for use in convertPerformExpression (for non-parameterized effects)
        if (scopedInfo.isScopedEffect) {
            val scopeSlot = getOrCreateSlot("__scope_${contentHash}__")

            // Use the already-converted finalizeScopeNode from above
            scopedEffectInfo[contentHash] = ScopedImplInfo(
                isScopedEffect = true,
                initializeScopeNode = initializeScopeNode,
                finalizeScopeNode = finalizeScopeNode,
                scopeSlot = scopeSlot
            )
        }

        return ParameterizedImplStorageNode(
            implDefChild = implDefinitionNode,
            hashSlot = hashSlot,
            performerSlot = performerSlot,
            contentHash = contentHash,
            performerName = impl.performerName,
            effectName = impl.effectName
        )
    }

    private fun convertImplAbilityDeclaration(impl: ImplAbilityDeclaration): MezzeNode {
        val fieldNames = mutableListOf<String>()
        val fieldValueNodes = mutableListOf<MezzeNode>()

        for (op in impl.operations) {
            fieldNames.add(op.name)
            fieldValueNodes.add(convertExpression(op.implementation))
        }

        val recordNode = RecordLiteralNode.allObjects(
            fieldNames.toTypedArray(),
            fieldValueNodes.toTypedArray()
        )

        val contentHash = impl.contentHash
            ?: throw RuntimeException("Content hash required for ability implementation: ${impl.abilityName}")

        val hashSlot = getOrCreateSlot(contentHash)
        contentHashToSlot[contentHash] = hashSlot

        return AbilityImplStorageNode(
            recordChild = recordNode,
            hashSlot = hashSlot,
            contentHash = contentHash,
            abilityName = impl.abilityName
        )
    }

    /**
     * True if this converter is at the top level (not inside a function).
     * Top-level bindings must be registered in the global context so they
     * can be accessed by nested functions via ReadLocalWithFallbackNode.
     */
    private val isTopLevel: Boolean = parentVariableSlots == null

    private fun convertLetBinding(letBinding: LetBinding): MezzeNode {
        return when (val pattern = letBinding.pattern) {
            is VariablePattern -> {
                val kind = inferKind(letBinding.expression)
                val slot = getOrCreateSlotWithKind(pattern.name, kind)
                // Pass content hash to lambda conversion for CallTarget caching
                val valueNode = if (letBinding.expression is LambdaExpression) {
                    convertLambdaExpression(
                        letBinding.expression as LambdaExpression,
                        pattern.name,
                        letBinding.contentHash
                    )
                } else {
                    convertExpression(letBinding.expression)
                }
                // Use WriteLocalWithContextNode for:
                // 1. Function bindings to support recursion (function references itself)
                // 2. ALL top-level bindings so nested functions can access them via fallback lookup
                // When a nested function references a top-level variable, the frame slot may be
                // empty, but the fallback lookup will find it in the global context.
                if (letBinding.expression is LambdaExpression || isTopLevel) {
                    WriteLocalWithContextNode.create(valueNode, slot, kind, pattern.name, letBinding.contentHash)
                } else {
                    WriteLocalNode.create(valueNode, slot, kind)
                }
            }
            is RecordPattern -> convertRecordPatternBinding(pattern, letBinding.expression)
            is WildcardPattern -> convertExpression(letBinding.expression)
            else -> throw RuntimeException("Unsupported pattern in let binding: ${pattern::class.simpleName}")
        }
    }

    private fun convertRecordPatternBinding(pattern: RecordPattern, valueExpr: Expression): MezzeNode {
        val valueNode = convertExpression(valueExpr)
        val bindings = collectRecordPatternBindings(pattern)

        val writeNodes = mutableListOf<MezzeNode>()
        // Allocate anonymous slot for temp record (no string name needed)
        val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)

        writeNodes.add(WriteLocalNode.createObject(valueNode, tempSlot))

        for ((fieldPath, varName) in bindings) {
            val slot = getOrCreateSlot(varName)
            var accessNode: MezzeNode = ReadLocalNode.createObject(tempSlot)
            for (fieldName in fieldPath) {
                accessNode = RecordFieldAccessNode.createByName(accessNode, fieldName)
            }
            writeNodes.add(WriteLocalNode.createObject(accessNode, slot))
        }

        return BlockNode(writeNodes.toTypedArray())
    }

    private fun collectRecordPatternBindings(pattern: RecordPattern): List<Pair<List<String>, String>> {
        val bindings = mutableListOf<Pair<List<String>, String>>()

        for (entry in pattern.entries) {
            val fieldPattern = entry.pattern
            when (fieldPattern) {
                is VariablePattern -> {
                    bindings.add(listOf(entry.name) to fieldPattern.name)
                }
                is RecordPattern -> {
                    val nested = collectRecordPatternBindings(fieldPattern)
                    for ((nestedPath, varName) in nested) {
                        bindings.add((listOf(entry.name) + nestedPath) to varName)
                    }
                }
                is WildcardPattern -> {
                    // No binding needed
                }
                null -> {
                    // Shorthand {x} means {x = x}
                    bindings.add(listOf(entry.name) to entry.name)
                }
                else -> throw RuntimeException("Unsupported pattern in record: ${fieldPattern::class.simpleName}")
            }
        }

        return bindings
    }

    /**
     * Convert an expression to a Truffle node.
     */
    fun convertExpression(expr: Expression): MezzeNode {
        return when (expr) {
            is IntLiteral -> IntLiteralNode(expr.value)
            is NatLiteral -> com.mezze.interpreter.v2.nodes.literal.NatLiteralNode(expr.value.toLong())
            is FloatLiteral -> FloatLiteralNode(expr.value)
            is FractionLiteral -> FracLiteralNode(expr.numerator, expr.denominator)
            is StringLiteral -> convertStringLiteral(expr)
            is BooleanLiteral -> BoolLiteralNode(expr.value)
            is Identifier -> convertIdentifier(expr)
            is IfExpression -> convertIfExpression(expr)
            is MatchExpression -> convertMatchExpression(expr)
            is FunctionCall -> convertFunctionCall(expr)
            is LambdaExpression -> convertLambdaExpression(expr)
            is RecordLiteral -> convertRecordLiteral(expr)
            is ArrayLiteral -> convertArrayLiteral(expr)
            is ArrayAccess -> convertArrayAccess(expr)
            is MemberAccess -> convertMemberAccess(expr)
            is VariantConstruction -> convertVariantConstruction(expr)
            is DoExpression -> convertDoExpression(expr)
            is PerformExpression -> convertPerformExpression(expr)
            is StateMachineExpression -> convertStateMachineExpression(expr)
            else -> throw RuntimeException("Unsupported expression: ${expr::class.simpleName}")
        }
    }

    private fun convertStringLiteral(expr: StringLiteral): MezzeNode {
        val parts = expr.parts
        if (expr.isInterpolated && parts != null) {
            val partNodes = parts.map { part ->
                when (part) {
                    is StringPart -> StringLiteralNode(part.value)
                    is ExpressionPart -> convertExpression(part.expression)
                    else -> throw RuntimeException("Unknown string part: ${part::class.simpleName}")
                }
            }.toTypedArray()
            return InterpolatedStringNode(partNodes)
        }
        return StringLiteralNode(expr.value)
    }

    private fun convertIdentifier(identifier: Identifier): MezzeNode {
        if (identifier.name == "__state_machine_input__") {
            val slot = getOrCreateSlot("__state_machine_input__")
            return ReadLocalNode.createObject(slot)
        }

        // Content hash from typechecker indicates cross-module reference
        val contentHash = identifier.resolvedContentHash

        val info = variableInfo[identifier.name]
        if (info != null) {
            // Cross-module references have content hash - use strict content hash lookup
            if (contentHash != null) {
                return ReadLocalWithFallbackNode.create(info.slot, identifier.name, contentHash)
            }

            // Closure variables need fallback lookup for recursive function support.
            // When a function captures itself, the closure value may be null at capture time,
            // but the function will be in the global context (written by WriteLocalWithContextNode).
            if (info.isClosure) {
                return ReadLocalWithFallbackNode.create(info.slot, identifier.name)
            }

            // Top-level (module-level) variables need fallback lookup because they may be
            // referenced from nested scopes (like inside function bodies or state machines)
            // where the frame slot would be null.
            if (info.isTopLevel) {
                return ReadLocalWithFallbackNode.create(info.slot, identifier.name)
            }

            // Variables that need fallback (created from outer scope reference)
            if (info.needsFallback) {
                return ReadLocalWithFallbackNode.create(info.slot, identifier.name, contentHash)
            }

            return ReadLocalNode(info.slot, info.kind)
        }

        if (isBuiltinFunction(identifier.name)) {
            return GlobalLookupNode(identifier.name)
        }

        val abilityNames = listOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord", "LogicalAnd", "LogicalOr", "LogicalNot")
        if (identifier.name in abilityNames) {
            return RuntimeErrorNode(
                "Undefined variable '${identifier.name}' at runtime. " +
                "Note: '${identifier.name}' is a builtin ability name."
            )
        }

        // Variable not found in local variableInfo - it's from an outer scope (module level or parent function)
        // Create a slot and mark it as needing fallback lookup.
        // This handles:
        // 1. Module-level functions referenced from function bodies
        // 2. Module-level constants referenced from function bodies
        // 3. Any other outer-scope references
        val slot = getOrCreateSlotWithFallback(identifier.name)
        return ReadLocalWithFallbackNode.create(slot, identifier.name, contentHash)
    }

    private fun isBuiltinFunction(name: String): Boolean {
        if (name.startsWith("__native_")) return true
        return when (name) {
            "intToString", "floatToString", "boolToString", "stringToInt",
            "print", "debug", "abs", "max", "min", "length", "concat",
            "arrayLength", "arrayGet", "arraySet", "arrayAdd", "arrayRemove",
            "arrayConcat", "arrayIsEmpty" -> true
            else -> false
        }
    }

    private fun convertIfExpression(ifExpr: IfExpression): MezzeNode {
        val conditionNode = convertExpression(ifExpr.condition)
        val thenNode = convertExpression(ifExpr.thenBranch)
        val elseNode = convertExpression(ifExpr.elseBranch)
        return IfNode.create(conditionNode, thenNode, elseNode)
    }

    private fun convertMatchExpression(matchExpr: MatchExpression): MezzeNode {
        val valueNode = convertExpression(matchExpr.expression)
        val branches = mutableListOf<MatchBranchNode>()
        var wildcardIndex = -1

        // Get the matched type for type-aware pattern bindings
        val matchedType = matchExpr.expression.inferredType

        for ((index, case) in matchExpr.cases.withIndex()) {
            // IMPORTANT: Pre-create slots for pattern variables BEFORE converting guards/if-let bindings.
            // This ensures that expressions like `lookup {key = k}` in if-let bindings
            // can find the slot for `k` from the main pattern.
            preCreatePatternSlotsWithType(case.patterns, matchedType)

            // Now convert guard and if-let bindings (they can reference pattern variables)
            val guardNode = case.guard?.let { convertExpression(it) }
            val ifLetNodes = convertIfLetBindings(case.ifLetBindings)
            val hasGuardsOrIfLet = guardNode != null || ifLetNodes.isNotEmpty()

            // Handle or-patterns: all patterns in case.patterns share the same body
            for (pattern in case.patterns) {
            when (pattern) {
                is ConstructorPattern -> {
                    // Special case: True and False are boolean patterns, not variant patterns
                    // This matches how V2 converts True/False constructors to boolean literals
                    if (pattern.constructor == "True" && pattern.recordPattern == null) {
                        val bodyNode = convertExpression(case.expression)
                        if (hasGuardsOrIfLet) {
                            branches.add(MatchBranchNode.literalPatternWithGuard(true, bodyNode, guardNode, ifLetNodes))
                        } else {
                            branches.add(MatchBranchNode.literalPattern(true, bodyNode))
                        }
                    } else if (pattern.constructor == "False" && pattern.recordPattern == null) {
                        val bodyNode = convertExpression(case.expression)
                        if (hasGuardsOrIfLet) {
                            branches.add(MatchBranchNode.literalPatternWithGuard(false, bodyNode, guardNode, ifLetNodes))
                        } else {
                            branches.add(MatchBranchNode.literalPattern(false, bodyNode))
                        }
                    } else {
                        val tag = getTagForConstructor(pattern.constructor)
                        // Collect both field names and slots for proper record extraction
                        // Also collect nested pattern info for wrapped body generation
                        val nestedPatterns = mutableListOf<Pair<Int, RecordPattern>>() // (tempSlot, nestedPattern)

                        // Get the payload type for this constructor from the matched type
                        val payloadType = getConstructorPayloadType(matchedType, pattern.constructor)

                        val fieldNames = pattern.recordPattern?.let { recordPattern ->
                            recordPattern.entries.map { it.name }.toTypedArray()
                        } ?: arrayOf()
                        val fieldSlots = pattern.recordPattern?.let { recordPattern ->
                            recordPattern.entries.mapIndexed { entryIdx, entry ->
                                // Get field type from payload if available
                                val fieldType = getFieldTypeFromPayload(payloadType, entry.name)
                                val kind = if (fieldType != null) kindFromType(fieldType) else ReadLocalNode.KIND_OBJECT

                                when (val fp = entry.pattern) {
                                    is VariablePattern -> getOrCreateSlotWithKind(fp.name, kind)
                                    null -> getOrCreateSlotWithKind(entry.name, kind)
                                    is RecordPattern -> {
                                        // Nested record pattern: allocate anonymous slot by index
                                        val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                                        nestedPatterns.add(tempSlot to fp)
                                        tempSlot
                                    }
                                    else -> -1
                                }
                            }.toIntArray()
                        } ?: intArrayOf()

                        // Create body with nested pattern extraction
                        var bodyNode = convertExpression(case.expression)

                        // When there are nested patterns AND a guard, we need to extract nested
                        // fields BEFORE the guard runs. We wrap the guard with extractions.
                        var actualGuardNode = guardNode
                        if (nestedPatterns.isNotEmpty() && guardNode != null) {
                            // Create extraction statements
                            val extractionStatements = mutableListOf<MezzeNode>()
                            for ((tempSlot, nestedPattern) in nestedPatterns) {
                                collectNestedExtractionStatements(tempSlot, nestedPattern, extractionStatements)
                            }
                            // Wrap the guard: do extractions, then evaluate guard and return result
                            extractionStatements.add(guardNode)
                            actualGuardNode = BlockNode(extractionStatements.toTypedArray())
                        }

                        // Wrap body with nested record field extractions (in reverse order)
                        for ((tempSlot, nestedPattern) in nestedPatterns.reversed()) {
                            bodyNode = wrapBodyWithNestedRecordExtraction(tempSlot, nestedPattern, bodyNode)
                        }

                        if (hasGuardsOrIfLet) {
                            branches.add(MatchBranchNode.variantPatternWithGuard(tag, fieldNames, fieldSlots, bodyNode, actualGuardNode, ifLetNodes))
                        } else {
                            branches.add(MatchBranchNode.variantPatternWithFieldNames(tag, fieldNames, fieldSlots, bodyNode))
                        }
                    }
                }
                is VariablePattern -> {
                    val slot = getOrCreateSlot(pattern.name)
                    val bodyNode = convertExpression(case.expression)
                    if (hasGuardsOrIfLet) {
                        branches.add(MatchBranchNode.wildcardWithGuard(slot, bodyNode, guardNode, ifLetNodes))
                    } else {
                        branches.add(MatchBranchNode.wildcard(slot, bodyNode))
                    }
                    wildcardIndex = index
                }
                is WildcardPattern -> {
                    val bodyNode = convertExpression(case.expression)
                    if (hasGuardsOrIfLet) {
                        branches.add(MatchBranchNode.wildcardWithGuard(-1, bodyNode, guardNode, ifLetNodes))
                    } else {
                        branches.add(MatchBranchNode.wildcardNoBinding(bodyNode))
                    }
                    wildcardIndex = index
                }
                is LiteralPattern -> {
                    val litValue: Any = when (val lit = pattern.value) {
                        is IntLiteralValue -> lit.value
                        is NatLiteralValue -> lit.value.toLong()
                        is FloatLiteralValue -> lit.value
                        is StringLiteralValue -> lit.value
                        is BooleanLiteralValue -> lit.value
                    }
                    val bodyNode = convertExpression(case.expression)
                    if (hasGuardsOrIfLet) {
                        branches.add(MatchBranchNode.literalPatternWithGuard(litValue, bodyNode, guardNode, ifLetNodes))
                    } else {
                        branches.add(MatchBranchNode.literalPattern(litValue, bodyNode))
                    }
                }
                is ArrayPattern -> {
                    // Array pattern: [x, y], [head, ...tail], [], [[nested]], [Ok {val}], [True, False], etc.
                    // Track nested array patterns: (elementIndex, tempSlot, nestedPattern)
                    val nestedArrayPatterns = mutableListOf<Triple<Int, Int, ArrayPattern>>()
                    // Track nested constructor patterns: (elementIndex, tempSlot, nestedPattern)
                    val nestedConstructorPatterns = mutableListOf<Triple<Int, Int, ConstructorPattern>>()
                    // Track nested literal patterns: (elementIndex, tempSlot, nestedPattern)
                    val nestedLiteralPatterns = mutableListOf<Triple<Int, Int, LiteralPattern>>()

                    val elementSlots = pattern.elements.mapIndexed { idx, elemPattern ->
                        when (elemPattern) {
                            is VariablePattern -> getOrCreateSlot(elemPattern.name)
                            is WildcardPattern -> -1 // No binding needed
                            is ArrayPattern -> {
                                // Nested array pattern - allocate temp slot for intermediate array
                                val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                                nestedArrayPatterns.add(Triple(idx, tempSlot, elemPattern))
                                tempSlot
                            }
                            is ConstructorPattern -> {
                                // Check for True/False constructor patterns - these are boolean literals
                                // In Mezze, True and False are variant constructors that represent booleans
                                if ((elemPattern.constructor == "True" || elemPattern.constructor == "False")
                                    && elemPattern.recordPattern == null) {
                                    // Treat True/False as literal boolean patterns
                                    val boolValue = elemPattern.constructor == "True"
                                    val literalPattern = LiteralPattern(BooleanLiteralValue(boolValue))
                                    val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                                    nestedLiteralPatterns.add(Triple(idx, tempSlot, literalPattern))
                                    tempSlot
                                } else {
                                    // Nested constructor pattern - allocate temp slot for intermediate variant
                                    val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                                    nestedConstructorPatterns.add(Triple(idx, tempSlot, elemPattern))
                                    tempSlot
                                }
                            }
                            is LiteralPattern -> {
                                // Nested literal pattern - allocate temp slot for element value
                                val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                                nestedLiteralPatterns.add(Triple(idx, tempSlot, elemPattern))
                                tempSlot
                            }
                            else -> -1
                        }
                    }.toIntArray()

                    val hasRestPattern = pattern.restPattern != null
                    val restSlot = pattern.restPattern?.let { getOrCreateSlot(it) } ?: -1

                    var bodyNode = convertExpression(case.expression)

                    // Wrap body with nested array pattern extraction (in reverse order)
                    for ((_, tempSlot, nestedPattern) in nestedArrayPatterns.reversed()) {
                        bodyNode = wrapBodyWithNestedArrayExtraction(tempSlot, nestedPattern, bodyNode)
                    }

                    // Wrap body with nested constructor pattern extraction (in reverse order)
                    for ((_, tempSlot, nestedPattern) in nestedConstructorPatterns.reversed()) {
                        bodyNode = wrapBodyWithNestedConstructorExtraction(tempSlot, nestedPattern, bodyNode)
                    }

                    // Wrap body with nested literal pattern checks (in reverse order)
                    for ((_, tempSlot, nestedPattern) in nestedLiteralPatterns.reversed()) {
                        bodyNode = wrapBodyWithNestedLiteralCheck(tempSlot, nestedPattern, bodyNode)
                    }

                    branches.add(MatchBranchNode.arrayPattern(elementSlots, restSlot, hasRestPattern, bodyNode))
                }
                is RecordPattern -> {
                    // Record pattern: {x, y} or {name = n, age = a}
                    // Also handle nested patterns like { config = { port = p } }
                    val nestedPatterns = mutableListOf<Pair<Int, RecordPattern>>()

                    val fieldNames = pattern.entries.map { it.name }.toTypedArray()
                    val fieldSlots = pattern.entries.map { entry ->
                        when (val p = entry.pattern) {
                            is VariablePattern -> getOrCreateSlot(p.name)
                            null -> getOrCreateSlot(entry.name) // Shorthand {x} = {x = x}
                            is RecordPattern -> {
                                // Nested record pattern: allocate temp slot and track for later extraction
                                val tempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                                nestedPatterns.add(tempSlot to p)
                                tempSlot
                            }
                            else -> -1
                        }
                    }.toIntArray()
                    val isOpen = pattern.isOpen

                    // Create body with nested pattern extraction
                    var bodyNode = convertExpression(case.expression)

                    // Wrap body with nested record field extractions (in reverse order)
                    for ((tempSlot, nestedPattern) in nestedPatterns.reversed()) {
                        bodyNode = wrapBodyWithNestedRecordExtraction(tempSlot, nestedPattern, bodyNode)
                    }

                    if (hasGuardsOrIfLet) {
                        branches.add(MatchBranchNode.recordPatternWithGuard(fieldNames, fieldSlots, isOpen, bodyNode, guardNode, ifLetNodes))
                    } else {
                        branches.add(MatchBranchNode.recordPattern(fieldNames, fieldSlots, isOpen, bodyNode))
                    }
                }
                is StringInterpolationPattern -> {
                    // String interpolation pattern: "hello $(name)" or "$(a)-$(b)"
                    val partTypes = pattern.parts.map { part ->
                        when (part) {
                            is StringPatternLiteral -> 0 // LITERAL
                            is StringPatternCapture -> 1 // CAPTURE
                        }
                    }.toIntArray()

                    val literals = pattern.parts.map { part ->
                        when (part) {
                            is StringPatternLiteral -> part.value
                            is StringPatternCapture -> null
                        }
                    }.toTypedArray()

                    val captureSlots = pattern.parts.mapIndexed { idx, part ->
                        when (part) {
                            is StringPatternCapture -> getOrCreateSlot(part.variableName)
                            is StringPatternLiteral -> -1
                        }
                    }.toIntArray()

                    val bodyNode = convertExpression(case.expression)
                    if (hasGuardsOrIfLet) {
                        branches.add(MatchBranchNode.stringPatternWithGuard(partTypes, literals, captureSlots, bodyNode, guardNode, ifLetNodes))
                    } else {
                        branches.add(MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode))
                    }
                }
                else -> throw RuntimeException("Unsupported pattern: ${pattern::class.simpleName}")
            }
            } // end of inner for loop over case.patterns
        }

        return MatchNode(valueNode, branches.toTypedArray(), wildcardIndex)
    }

    /**
     * Pre-create frame slots for all variables bound by match patterns.
     * This must be done BEFORE converting guards/if-let bindings so that
     * expressions like `lookup {key = k}` can reference pattern variable `k`.
     */
    private fun preCreatePatternSlots(patterns: List<Pattern>) {
        for (pattern in patterns) {
            preCreatePatternSlotsRecursive(pattern)
        }
    }

    private fun preCreatePatternSlotsRecursive(pattern: Pattern) {
        when (pattern) {
            is VariablePattern -> getOrCreateSlot(pattern.name)
            is ConstructorPattern -> {
                pattern.recordPattern?.let { preCreatePatternSlotsRecursive(it) }
            }
            is RecordPattern -> {
                for (entry in pattern.entries) {
                    when (val p = entry.pattern) {
                        is VariablePattern -> getOrCreateSlot(p.name)
                        is RecordPattern -> preCreatePatternSlotsRecursive(p)
                        null -> getOrCreateSlot(entry.name) // shorthand {x} = {x = x}
                        else -> {} // WildcardPattern etc - no slot needed
                    }
                }
            }
            is ArrayPattern -> {
                for (elemPattern in pattern.elements) {
                    when (elemPattern) {
                        is VariablePattern -> getOrCreateSlot(elemPattern.name)
                        is ArrayPattern -> preCreatePatternSlotsRecursive(elemPattern) // Nested array pattern
                        is ConstructorPattern -> preCreatePatternSlotsRecursive(elemPattern) // Nested constructor pattern
                        else -> {} // WildcardPattern etc
                    }
                }
                pattern.restPattern?.let { getOrCreateSlot(it) }
            }
            is StringInterpolationPattern -> {
                for (part in pattern.parts) {
                    if (part is StringPatternCapture) {
                        getOrCreateSlot(part.variableName)
                    }
                }
            }
            // LiteralPattern, WildcardPattern - no slots needed
            else -> {}
        }
    }

    /**
     * Pre-create frame slots for pattern variables with type information.
     * Uses the matched expression's type to infer proper slot kinds.
     */
    private fun preCreatePatternSlotsWithType(patterns: List<Pattern>, matchedType: MezzeType?) {
        for (pattern in patterns) {
            preCreatePatternSlotsWithTypeRecursive(pattern, matchedType)
        }
    }

    private fun preCreatePatternSlotsWithTypeRecursive(pattern: Pattern, matchedType: MezzeType?) {
        when (pattern) {
            is VariablePattern -> {
                // For a variable pattern, the entire matched type is bound to it
                val kind = if (matchedType != null) kindFromType(matchedType) else ReadLocalNode.KIND_OBJECT
                getOrCreateSlotWithKind(pattern.name, kind)
            }
            is ConstructorPattern -> {
                // Get payload type for this constructor
                val payloadType = getConstructorPayloadType(matchedType, pattern.constructor)
                pattern.recordPattern?.let { preCreatePatternSlotsWithTypeRecursive(it, payloadType) }
            }
            is RecordPattern -> {
                // For record patterns, get field types from the record type
                val recordType = matchedType as? MezzeType.RecordType
                for (entry in pattern.entries) {
                    val fieldType = recordType?.fields?.get(entry.name)
                    val kind = if (fieldType != null) kindFromType(fieldType) else ReadLocalNode.KIND_OBJECT
                    when (val p = entry.pattern) {
                        is VariablePattern -> getOrCreateSlotWithKind(p.name, kind)
                        is RecordPattern -> preCreatePatternSlotsWithTypeRecursive(p, fieldType)
                        null -> getOrCreateSlotWithKind(entry.name, kind) // shorthand {x} = {x = x}
                        else -> {} // WildcardPattern etc - no slot needed
                    }
                }
            }
            is ArrayPattern -> {
                // Get element type from array type
                val elemType = (matchedType as? MezzeType.ArrayType)?.elementType
                    ?: (matchedType as? MezzeType.ListType)?.elementType
                for (elemPattern in pattern.elements) {
                    when (elemPattern) {
                        is VariablePattern -> {
                            val kind = if (elemType != null) kindFromType(elemType) else ReadLocalNode.KIND_OBJECT
                            getOrCreateSlotWithKind(elemPattern.name, kind)
                        }
                        is ArrayPattern -> preCreatePatternSlotsWithTypeRecursive(elemPattern, elemType)
                        is ConstructorPattern -> preCreatePatternSlotsWithTypeRecursive(elemPattern, elemType)
                        else -> {}
                    }
                }
                // Rest pattern captures remaining elements as an array/list
                pattern.restPattern?.let { getOrCreateSlot(it) } // Always object for arrays/lists
            }
            is StringInterpolationPattern -> {
                // String captures are always strings
                for (part in pattern.parts) {
                    if (part is StringPatternCapture) {
                        getOrCreateSlotWithKind(part.variableName, ReadLocalNode.KIND_OBJECT) // Strings are objects
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Get the payload type for a constructor from a variant type.
     */
    private fun getConstructorPayloadType(matchedType: MezzeType?, constructor: String): MezzeType? {
        val variantType = matchedType as? MezzeType.VariantType ?: return null
        return variantType.constructors[constructor]
    }

    /**
     * Get the type of a field from a payload type (which should be a RecordType).
     */
    private fun getFieldTypeFromPayload(payloadType: MezzeType?, fieldName: String): MezzeType? {
        val recordType = payloadType as? MezzeType.RecordType ?: return null
        return recordType.fields[fieldName]
    }

    private fun getTagForConstructor(constructor: String): Int {
        // Use the global tag registry for consistent tag assignment
        return com.mezze.interpreter.v2.values.MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(constructor)
    }

    private fun convertFunctionCall(funcCall: FunctionCall): MezzeNode {
        // Check for native functions FIRST (continuation and console)
        if (funcCall.function is Identifier) {
            val funcName = (funcCall.function as Identifier).name
            val nativeNode = convertNativeFunctionCall(funcName, funcCall.arguments)
            if (nativeNode != null) {
                return nativeNode
            }
        }

        if (funcCall.resolvedFunctionName != null) {
            // Also check if resolved function is a native function
            val nativeNode = convertNativeFunctionCall(funcCall.resolvedFunctionName!!, funcCall.arguments)
            if (nativeNode != null) {
                return nativeNode
            }
            return convertMonomorphicFunctionCall(funcCall)
        }

        // IMPORTANT: Check for effect calls BEFORE resolvedAbilities
        // Effect calls look like FileSystem.write_file and should use dynamic dispatch
        // because the handler is determined by the enclosing perform block
        if (funcCall.function is MemberAccess) {
            val memberAccess = funcCall.function as MemberAccess
            val receiver = memberAccess.receiver
            val receiverName = when (receiver) {
                is Identifier -> receiver.name
                is VariantConstruction -> receiver.constructor
                else -> null
            }

            if (receiverName != null) {
                val operationName = memberAccess.member

                // Check if this is a known builtin ability+operation combination
                // Only use builtin path if we have a matching builtin operation
                val builtinNode = tryCreateBuiltinOp(receiverName, operationName, funcCall)
                if (builtinNode != null) {
                    return builtinNode
                }

                // CRITICAL: Check resolvedAbilities FIRST before falling back to effect dispatch
                // The type checker resolves ability method calls and populates resolvedAbilities
                // If present, this is an ability call, not an effect call
                if (funcCall.resolvedAbilities.isNotEmpty()) {
                    return convertResolvedAbilityCall(funcCall)
                }

                // Check if this is an ability call inside a polymorphic function
                // (i.e., we're inside a function with ability constraints like "with Encoder 'a")
                // This handles BOTH builtin codec abilities AND user-defined abilities.
                val constraintIndex = constraintAbilityNames.indexOf(receiverName)
                if (constraintIndex >= 0) {
                    // We're inside a polymorphic function with this ability constraint.
                    // Use dictionary passing - the caller will provide the operation function.
                    val operationSlot = getOrCreateAbilityOperationSlot(constraintIndex, operationName)
                    val argsNode = convertExpression(funcCall.arguments)
                    // DirectDictionaryCallNode reads the operation function from the frame slot
                    return DirectDictionaryCallNode.create(operationSlot, receiverName, operationName, argsNode)
                }

                // Check if this is a codec ability call outside a polymorphic function
                // These use runtime lookup based on argument types
                val codecAbilities = setOf(
                    "Encode", "Decode",
                    "EncodeFormat", "DecodeFormat",
                    "StreamEncodeFormat", "StreamDecodeFormat"
                )
                if (receiverName in codecAbilities) {
                    // Not inside a polymorphic function - fall back to runtime lookup
                    val argsNode = convertExpression(funcCall.arguments)
                    return RuntimeAbilityCallNode.create(receiverName, operationName, argsNode)
                }

                // If the receiver is a VariantConstruction (like Log, FileSystem), it's an effect call
                // Even if the effect name is in variableInfo (from use declarations), we need dynamic dispatch
                // because the actual handler is determined by the enclosing perform block
                val isEffectCall = receiver is VariantConstruction || !variableInfo.containsKey(receiverName)
                if (isEffectCall) {
                    val argsNode = convertExpression(funcCall.arguments)
                    return DynamicEffectCallNode.create(receiverName, operationName, argsNode)
                }
            }
        }

        // Handle resolvedAbilities for non-member access ability calls
        if (funcCall.resolvedAbilities.isNotEmpty()) {
            return convertResolvedAbilityCall(funcCall)
        }

        val argumentsNode = convertExpression(funcCall.arguments)
        val functionNode = convertExpression(funcCall.function)

        if (funcCall.isTailCall) {
            return TailCallNode.create(functionNode, arrayOf(argumentsNode))
        }

        return InvokeNode.create(functionNode, arrayOf(argumentsNode))
    }

    private fun convertMonomorphicFunctionCall(funcCall: FunctionCall): MezzeNode {
        val monomorphicName = funcCall.resolvedFunctionName!!
        val argumentsNode = convertExpression(funcCall.arguments)
        val contentHash = funcCall.resolvedFunctionContentHash

        // Check if this call has resolved abilities that need dictionary passing
        // Filter out builtin abilities - they don't need dictionary passing
        val userDefinedAbilities = funcCall.resolvedAbilities.values
            .filter { !it.isBuiltin && it.implementationHash != null }

        if (userDefinedAbilities.isNotEmpty()) {
            // Dictionary passing: pass ability implementations to polymorphic callee
            val abilityNames = userDefinedAbilities.map { it.abilityName }.toTypedArray()
            val implHashes = userDefinedAbilities.map { it.implementationHash!! }.toTypedArray()

            // Get function node - either by content hash or local lookup
            val functionNode = if (contentHash != null) {
                // Cross-module: use ReadLocalWithFallbackNode with the content hash as lookup key
                ReadLocalWithFallbackNode.create(getOrCreateSlot(monomorphicName), contentHash)
            } else {
                // Local: use frame lookup with fallback
                val slot = getOrCreateSlot(monomorphicName)
                ReadLocalWithFallbackNode.create(slot, monomorphicName)
            }

            return DictionaryPassingInvokeNode.create(
                functionNode,
                argumentsNode,
                abilityNames,
                implHashes
            )
        }

        // No dictionary passing needed - use standard call nodes
        // Cross-module references have content hash - use MonomorphicCallNode
        // which caches the function lookup and DirectCallNode for maximum performance
        if (contentHash != null) {
            return MonomorphicCallNode.create(monomorphicName, contentHash, argumentsNode)
        }

        // For local monomorphic names, use frame lookup with fallback
        val slot = getOrCreateSlot(monomorphicName)
        val functionNode = ReadLocalWithFallbackNode.create(slot, monomorphicName)
        return InvokeNode.create(functionNode, arrayOf(argumentsNode))
    }

    private fun convertResolvedAbilityCall(funcCall: FunctionCall): MezzeNode {
        val resolvedAbility = funcCall.resolvedAbilities.values.firstOrNull() ?: run {
            val functionNode = convertExpression(funcCall.function)
            val argumentsNode = convertExpression(funcCall.arguments)
            return InvokeNode.create(functionNode, arrayOf(argumentsNode))
        }

        if (resolvedAbility.isBuiltin) {
            if (funcCall.function is MemberAccess) {
                val memberAccess = funcCall.function as MemberAccess
                val operationName = memberAccess.member
                val abilityName = resolvedAbility.abilityName

                // Try to use direct nodes when we have {l, r} record literal
                val directNode = tryCreateDirectBinaryOp(abilityName, operationName, funcCall.arguments)
                if (directNode != null) {
                    return directNode
                }

                // Fall back to builtin nodes that extract from record
                val argsNode = convertExpression(funcCall.arguments)
                return createBuiltinBinaryOp(abilityName, operationName, argsNode)
            }
        } else {
            if (funcCall.function is MemberAccess) {
                val memberAccess = funcCall.function as MemberAccess
                val operationName = memberAccess.member

                val implementationHash = resolvedAbility.implementationHash
                    ?: throw RuntimeException("Content hash required for ability call: ${resolvedAbility.abilityName}")

                val argsNode = convertExpression(funcCall.arguments)
                val localSlot = contentHashToSlot[implementationHash]

                // Use OPTIMIZED ability call node with DirectCallNode caching
                return OptimizedAbilityCallNode.create(
                    argsNode,
                    localSlot ?: -1,
                    implementationHash,
                    operationName
                )
            }
        }

        // LET-POLYMORPHISM: If calling a function (not ability method) with resolved abilities,
        // use dictionary passing to provide ability implementations to the callee
        val userDefinedAbilities = funcCall.resolvedAbilities.values
            .filter { !it.isBuiltin && it.implementationHash != null }

        if (userDefinedAbilities.isNotEmpty()) {
            val functionNode = convertExpression(funcCall.function)
            val argumentsNode = convertExpression(funcCall.arguments)

            // Dictionary passing: pass ability implementations to polymorphic callee
            val abilityNames = userDefinedAbilities.map { it.abilityName }.toTypedArray()
            val implHashes = userDefinedAbilities.map { it.implementationHash!! }.toTypedArray()

            return DictionaryPassingInvokeNode.create(
                functionNode,
                argumentsNode,
                abilityNames,
                implHashes
            )
        }

        val functionNode = convertExpression(funcCall.function)
        val argumentsNode = convertExpression(funcCall.arguments)
        return InvokeNode.create(functionNode, arrayOf(argumentsNode))
    }

    /**
     * Try to create a builtin operation node.
     * Returns null if this is not a known builtin combination.
     * This allows user-defined abilities with builtin names (like Eq) to use regular ability dispatch
     * if they have different operation names than the builtins.
     */
    private fun tryCreateBuiltinOp(abilityName: String, operationName: String, funcCall: FunctionCall): MezzeNode? {
        // Check if this is a known builtin combination
        val isBuiltin = when {
            abilityName == "NumAdd" && operationName == "add" -> true
            abilityName == "NumSub" && operationName in listOf("sub", "neg") -> true
            abilityName == "NumMul" && operationName == "mul" -> true
            abilityName == "NumDiv" && operationName == "div" -> true
            abilityName == "NumMod" && operationName == "mod" -> true
            abilityName == "Eq" && operationName in listOf("eq", "neq") -> true
            abilityName == "Ord" && operationName in listOf("lt", "gt", "le", "ge") -> true
            abilityName == "LogicalAnd" && operationName == "and" -> true
            abilityName == "LogicalOr" && operationName == "or" -> true
            abilityName == "LogicalNot" && operationName == "not" -> true
            abilityName == "Inspect" && operationName == "to_str" -> true
            else -> false
        }

        if (!isBuiltin) return null

        val argsNode = convertExpression(funcCall.arguments)
        return createBuiltinBinaryOp(abilityName, operationName, argsNode)
    }

    private fun createBuiltinBinaryOp(abilityName: String, operationName: String, argsNode: MezzeNode): MezzeNode {
        // Use optimized Truffle DSL nodes - NO @TruffleBoundary in hot paths!
        return when {
            abilityName == "NumAdd" && operationName == "add" -> BuiltinAddNode.create(argsNode)
            abilityName == "NumSub" && operationName == "sub" -> BuiltinSubNode.create(argsNode)
            abilityName == "NumSub" && operationName == "neg" -> BuiltinNegNode.create(argsNode)
            abilityName == "NumMul" && operationName == "mul" -> BuiltinMulNode.create(argsNode)
            abilityName == "NumDiv" && operationName == "div" -> BuiltinDivNode.create(argsNode)
            abilityName == "NumMod" && operationName == "mod" -> BuiltinModNode.create(argsNode)
            abilityName == "Eq" && operationName == "eq" -> BuiltinEqNode.create(argsNode)
            abilityName == "Eq" && operationName == "neq" -> BuiltinNeqNode.create(argsNode)
            abilityName == "Ord" && operationName == "lt" -> BuiltinLtNode.create(argsNode)
            abilityName == "Ord" && operationName == "gt" -> BuiltinGtNode.create(argsNode)
            abilityName == "Ord" && operationName == "le" -> BuiltinLeNode.create(argsNode)
            abilityName == "Ord" && operationName == "ge" -> BuiltinGeNode.create(argsNode)
            abilityName == "LogicalAnd" && operationName == "and" -> BuiltinAndNode.create(argsNode)
            abilityName == "LogicalOr" && operationName == "or" -> BuiltinOrNode.create(argsNode)
            abilityName == "LogicalNot" && operationName == "not" -> BuiltinNotNode.create(argsNode)
            abilityName == "Inspect" && operationName == "to_str" -> BuiltinInspectToStrNode.create(argsNode)
            else -> throw RuntimeException("Unknown builtin: $abilityName.$operationName")
        }
    }

    /**
     * Try to create a direct binary operation node.
     *
     * When the arguments are a RecordLiteral with {l, r} fields, we can create
     * DirectAddNode/DirectSubtractNode/etc. that take operands directly,
     * avoiding:
     * 1. Record allocation for {l, r}
     * 2. Field extraction at runtime
     * 3. Runtime type dispatch
     *
     * Returns null if the arguments are not in the expected form.
     */
    private fun tryCreateDirectBinaryOp(abilityName: String, operationName: String, args: Expression): MezzeNode? {
        // Check if args is a RecordLiteral with l and r fields
        if (args !is RecordLiteral) return null

        // Extract field entries (filter out spread entries)
        val fieldEntries = args.entries.filterIsInstance<RecordFieldEntry>()

        val lField = fieldEntries.find { entry -> entry.name == "l" }
        val rField = fieldEntries.find { entry -> entry.name == "r" }

        // For binary operations, we need both l and r
        if (abilityName in listOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord")) {
            if (lField == null || rField == null) return null

            // RecordFieldEntry.value can be null for shorthand {x} - in that case, fall back
            val lValue = lField.value ?: return null
            val rValue = rField.value ?: return null

            val leftNode = convertExpression(lValue)
            val rightNode = convertExpression(rValue)

            return when {
                abilityName == "NumAdd" && operationName == "add" -> DirectAddNode.create(leftNode, rightNode)
                abilityName == "NumSub" && operationName == "sub" -> DirectSubtractNode.create(leftNode, rightNode)
                abilityName == "NumMul" && operationName == "mul" -> DirectMultiplyNode.create(leftNode, rightNode)
                abilityName == "NumDiv" && operationName == "div" -> DirectDivideNode.create(leftNode, rightNode)
                abilityName == "NumMod" && operationName == "mod" -> DirectModuloNode.create(leftNode, rightNode)
                abilityName == "Eq" && operationName == "eq" -> DirectEqualNode.create(leftNode, rightNode)
                abilityName == "Eq" && operationName == "neq" -> DirectNotEqualNode.create(leftNode, rightNode)
                abilityName == "Ord" && operationName == "lt" -> DirectLessThanNode.create(leftNode, rightNode)
                abilityName == "Ord" && operationName == "gt" -> DirectGreaterThanNode.create(leftNode, rightNode)
                abilityName == "Ord" && operationName == "le" -> DirectLessThanOrEqualNode.create(leftNode, rightNode)
                abilityName == "Ord" && operationName == "ge" -> DirectGreaterThanOrEqualNode.create(leftNode, rightNode)
                else -> null
            }
        }

        // For unary operations like neg, we need val field
        if (abilityName == "NumSub" && operationName == "neg") {
            val valField = fieldEntries.find { entry -> entry.name == "val" }
            if (valField != null) {
                val valValue = valField.value ?: return null
                val operandNode = convertExpression(valValue)
                return DirectNegateNode.create(operandNode)
            }
        }

        return null
    }

    // ===== IF-LET GUARD CONVERSION =====

    /**
     * Convert if-let bindings from AST to IfLetBindingNode array.
     * Handles nested patterns by generating multiple sequential IfLetBindingNodes.
     */
    private fun convertIfLetBindings(ifLetGuards: List<IfLetGuard>): Array<IfLetBindingNode> {
        if (ifLetGuards.isEmpty()) {
            return emptyArray()
        }
        val allNodes = mutableListOf<IfLetBindingNode>()
        for (guard in ifLetGuards) {
            convertIfLetBindingWithNested(guard, allNodes)
        }
        return allNodes.toTypedArray()
    }

    /**
     * Convert a single if-let binding, potentially generating multiple nodes for nested patterns.
     * Adds all generated nodes to the provided list.
     */
    private fun convertIfLetBindingWithNested(binding: IfLetGuard, nodes: MutableList<IfLetBindingNode>) {
        val expressionNode = convertExpression(binding.expression)
        when (val pattern = binding.pattern) {
            is ConstructorPattern -> {
                val tag = getTagForConstructor(pattern.constructor)
                val (fieldNames, fieldSlots, nestedSlots) = extractPatternFieldsAndSlotsWithNested(pattern.recordPattern)
                nodes.add(IfLetBindingNode.variantPattern(expressionNode, tag, fieldNames, fieldSlots))
                // Generate additional nodes for nested record patterns
                addNestedRecordBindings(pattern.recordPattern, nestedSlots, nodes)
            }
            is RecordPattern -> {
                val (fieldNames, fieldSlots, nestedSlots) = extractPatternFieldsAndSlotsWithNested(pattern)
                nodes.add(IfLetBindingNode.recordPattern(expressionNode, fieldNames, fieldSlots))
                addNestedRecordBindings(pattern, nestedSlots, nodes)
            }
            is VariablePattern -> {
                val slot = getOrCreateSlot(pattern.name)
                nodes.add(IfLetBindingNode.wildcardPattern(expressionNode, slot))
            }
            is WildcardPattern -> {
                nodes.add(IfLetBindingNode.wildcardPattern(expressionNode, -1))
            }
            else -> throw RuntimeException("Unsupported if-let pattern type: ${pattern::class.simpleName}")
        }
    }

    /**
     * Extract field names and slots from a record pattern.
     * For nested record patterns, allocates intermediate slots and returns them separately.
     * Returns (fieldNames, fieldSlots, nestedSlotIndices) where nestedSlotIndices[i] is the
     * slot allocated for a nested pattern at entry i, or -1 if entry i is not a nested pattern.
     */
    private fun extractPatternFieldsAndSlotsWithNested(recordPattern: RecordPattern?): Triple<Array<String>, IntArray, IntArray> {
        if (recordPattern == null) {
            return Triple(emptyArray(), intArrayOf(), intArrayOf())
        }
        val fieldNames = recordPattern.entries.map { it.name }.toTypedArray()
        val fieldSlots = IntArray(recordPattern.entries.size)
        val nestedSlots = IntArray(recordPattern.entries.size) { -1 }

        recordPattern.entries.forEachIndexed { idx, entry ->
            when (val p = entry.pattern) {
                is VariablePattern -> {
                    fieldSlots[idx] = getOrCreateSlot(p.name)
                }
                null -> {
                    // shorthand: {x} = {x = x}
                    fieldSlots[idx] = getOrCreateSlot(entry.name)
                }
                is RecordPattern -> {
                    // Nested record pattern - allocate slot by index, not name
                    val slot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                    fieldSlots[idx] = slot
                    nestedSlots[idx] = slot
                }
                else -> {
                    // WildcardPattern etc - no binding needed
                    fieldSlots[idx] = -1
                }
            }
        }
        return Triple(fieldNames, fieldSlots, nestedSlots)
    }

    /**
     * Generate IfLetBindingNodes for nested record patterns.
     * Reads from the intermediate slots and extracts nested fields.
     */
    private fun addNestedRecordBindings(
        recordPattern: RecordPattern?,
        nestedSlots: IntArray,
        nodes: MutableList<IfLetBindingNode>
    ) {
        if (recordPattern == null) return
        recordPattern.entries.forEachIndexed { idx, entry ->
            val slot = nestedSlots[idx]
            if (slot >= 0 && entry.pattern is RecordPattern) {
                val nestedPattern = entry.pattern as RecordPattern
                // Create a ReadLocalNode to read the intermediate slot
                val readNode = ReadLocalNode.createObject(slot)
                val (nestedFieldNames, nestedFieldSlots, furtherNestedSlots) =
                    extractPatternFieldsAndSlotsWithNested(nestedPattern)
                nodes.add(IfLetBindingNode.recordPattern(readNode, nestedFieldNames, nestedFieldSlots))
                // Recursively handle further nesting
                addNestedRecordBindings(nestedPattern, furtherNestedSlots, nodes)
            }
        }
    }

    /**
     * Convert lambda expression without content hash (anonymous lambdas).
     */
    private fun convertLambdaExpression(lambda: LambdaExpression): MezzeNode {
        return convertLambdaExpression(lambda, "lambda", null)
    }

    /**
     * Convert lambda expression with optional name and content hash for CallTarget caching.
     * When a content hash is provided, the CallTarget can be cached and reused across
     * identical function definitions.
     */
    private fun convertLambdaExpression(
        lambda: LambdaExpression,
        functionName: String,
        contentHash: String?
    ): MezzeNode {
        val paramNames = when (val params = lambda.parameters) {
            is UntypedParameters -> params.names
            is TypedParameters -> params.recordType.entries.map { it.name }
        }

        val allVariables = collectVariableReferences(lambda.body)
        val freeVariables = allVariables.filter { it !in paramNames }.distinct()

        // Separate variables that can be captured from parent frame vs those that need global lookup
        val capturableVars = freeVariables.filter { variableInfo.containsKey(it) }

        val functionConverter = ASTToTruffleConverterV2(
            context = this.context,
            parentHandlerMapping = this.handlerToEffectMapping,
            parentVariableSlots = this.getVariableSlots(),
            parentScopedEffectInfo = this.scopedEffectInfo.toMap()
        )

        // Extract ability constraints from the lambda's inferred type
        // These are populated by the typechecker for polymorphic functions
        val abilityConstraints = (lambda.inferredType as? MezzeType.FunctionType)?.abilityConstraints ?: emptyList()

        // Populate the function converter's constraint names BEFORE converting the body.
        // This allows ability calls inside the body to find their constraint indices.
        abilityConstraints.forEach { constraint ->
            functionConverter.constraintAbilityNames.add(constraint.abilityName)
        }

        // Closure slots come first (only for capturable vars), then parameter slots
        // Layout: [closure0, closure1, ..., abilityOp0, abilityOp1, ..., param0, param1, ...]
        // Use the same kind as the parent scope so typed reads work correctly
        capturableVars.forEach { varName ->
            val parentKind = variableInfo[varName]!!.kind
            functionConverter.getOrCreateSlotWithKind(varName, parentKind)
            // Mark as closure variable so convertIdentifier uses fallback lookup.
            // This is critical for recursive functions where the closure value is null.
            functionConverter.markAsClosure(varName)
        }
        paramNames.forEach { functionConverter.getOrCreateSlot(it) }
        // Global vars get slots after parameters (will be read via GlobalLookupNode)
        // Don't allocate slots for them - let convertIdentifier handle them with fallback

        val rawBodyNode = functionConverter.convertExpression(lambda.body)
        val hasTailCalls = containsTailCalls(lambda.body)

        // Gather all closure slots: regular variables + ability operation functions
        val regularClosureSlots = capturableVars.map { variableInfo[it]!!.slot }.toIntArray()
        val regularClosureKinds = capturableVars.map { variableInfo[it]!!.kind }.toIntArray()

        // Get ability operation slots that were allocated during body conversion
        // These are slots inside the function's frame where operation functions will be stored
        val abilityOpSlotInfo = functionConverter.getAbilityOperationSlotsOrdered()

        val bodyNode = if (hasTailCalls) {
            // Pass parameter names so TailCallLoopNode can unpack record arguments correctly
            TailCallLoopNode(rawBodyNode, paramNames.size, regularClosureSlots.size, paramNames.toTypedArray())
        } else {
            rawBodyNode
        }

        val functionFrameDescriptor = functionConverter.getFrameDescriptor()

        // Build ability slot requirements from the slots allocated during body conversion
        val abilitySlotRequirements = if (abilityOpSlotInfo.isNotEmpty()) {
            abilityOpSlotInfo.map { (constraintIdx, opName, slotIdx) ->
                val abilityName = abilityConstraints.getOrNull(constraintIdx)?.abilityName ?: "unknown"
                MezzeFunction.AbilitySlotRequirement(constraintIdx, abilityName, opName, slotIdx)
            }.toTypedArray()
        } else {
            null
        }

        // Pass content hash, closure kinds, and ability slot requirements
        return FunctionDefinitionNode(
            functionName,
            contentHash,
            regularClosureSlots,
            regularClosureKinds,
            paramNames.toTypedArray(),
            bodyNode,
            functionFrameDescriptor,
            abilitySlotRequirements
        )
    }

    private fun containsTailCalls(expr: Expression): Boolean {
        fun visit(e: Expression): Boolean {
            return when (e) {
                is FunctionCall -> e.isTailCall || visit(e.function) || visit(e.arguments)
                is IfExpression -> visit(e.condition) || visit(e.thenBranch) || visit(e.elseBranch)
                is MatchExpression -> visit(e.expression) || e.cases.any { visit(it.expression) }
                is DoExpression -> e.statements.any { stmt ->
                    when (stmt) {
                        is LetStatement -> visit(stmt.expression)
                        is ExpressionStatement -> visit(stmt.expression)
                    }
                } || visit(e.result)
                is LambdaExpression -> false
                is RecordLiteral -> e.getFieldEntries().any { it.value?.let { v -> visit(v) } == true }
                is ArrayLiteral -> e.elements.any { element ->
                    when (element) {
                        is ArrayRegularElement -> visit(element.expression)
                        is ArraySpreadElement -> visit(element.expression)
                    }
                }
                is MemberAccess -> visit(e.receiver)
                is ArrayAccess -> visit(e.array) || visit(e.index)
                is VariantConstruction -> e.value?.let { visit(it) } == true
                is PerformExpression -> visit(e.expression)
                else -> false
            }
        }
        return visit(expr)
    }

    private fun collectVariableReferences(expr: Expression): List<String> {
        val variables = mutableListOf<String>()

        fun visit(e: Expression) {
            when (e) {
                is Identifier -> variables.add(e.name)
                is IntLiteral, is NatLiteral, is FloatLiteral, is FractionLiteral,
                is BooleanLiteral -> Unit
                is StringLiteral -> {
                    if (e.isInterpolated) {
                        e.parts?.forEach { part ->
                            when (part) {
                                is StringPart -> Unit
                                is ExpressionPart -> visit(part.expression)
                            }
                        }
                    }
                }
                is IfExpression -> {
                    visit(e.condition)
                    visit(e.thenBranch)
                    visit(e.elseBranch)
                }
                is MatchExpression -> {
                    visit(e.expression)
                    e.cases.forEach { case ->
                        visit(case.expression)
                        case.guard?.let { visit(it) }
                    }
                }
                is FunctionCall -> {
                    if (e.resolvedFunctionName != null && e.function is Identifier) {
                        variables.add(e.resolvedFunctionName!!)
                    } else {
                        visit(e.function)
                    }
                    visit(e.arguments)
                }
                is LambdaExpression -> {
                    val nestedParams = when (val params = e.parameters) {
                        is UntypedParameters -> params.names
                        is TypedParameters -> params.recordType.entries.map { it.name }
                    }
                    val nestedVars = collectVariableReferences(e.body)
                    val nestedFreeVars = nestedVars.filter { it !in nestedParams }
                    variables.addAll(nestedFreeVars)
                }
                is RecordLiteral -> {
                    // Visit field entries
                    e.getFieldEntries().forEach { entry ->
                        if (entry.value != null) {
                            visit(entry.value!!)
                        } else {
                            variables.add(entry.name)
                        }
                    }
                    // Visit spread entries (e.g., {...point} captures 'point')
                    e.getSpreadEntries().forEach { spread ->
                        visit(spread.expression)
                    }
                }
                is ArrayLiteral -> {
                    e.elements.forEach { element ->
                        when (element) {
                            is ArrayRegularElement -> visit(element.expression)
                            is ArraySpreadElement -> visit(element.expression)
                        }
                    }
                }
                is ArrayAccess -> {
                    visit(e.array)
                    visit(e.index)
                }
                is MemberAccess -> {
                    if (e.receiver is Identifier) {
                        val receiverName = (e.receiver as Identifier).name
                        val builtinAbilities = listOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod",
                            "Eq", "Ord", "LogicalAnd", "LogicalOr", "LogicalNot")
                        if (receiverName !in builtinAbilities) {
                            visit(e.receiver)
                        }
                    } else {
                        visit(e.receiver)
                    }
                }
                is VariantConstruction -> {
                    e.value?.let { visit(it) }
                }
                is DoExpression -> {
                    e.statements.forEach { stmt ->
                        when (stmt) {
                            is LetStatement -> visit(stmt.expression)
                            is ExpressionStatement -> visit(stmt.expression)
                        }
                    }
                    visit(e.result)
                }
                is PerformExpression -> visit(e.expression)
                is StateMachineExpression -> {
                    // Visit all expressions within state machine states
                    for (state in e.states) {
                        when (state) {
                            is ComputeState -> visit(state.computation)
                            is YieldState -> visit(state.valueExpression)
                            is CompleteState -> visit(state.resultExpression)
                        }
                    }
                    // Also include captured variables directly - they represent
                    // free variables computed during continuation transformation
                    variables.addAll(e.capturedVariables)
                }
                else -> Unit
            }
        }

        visit(expr)
        return variables
    }

    private fun convertRecordLiteral(recordLit: RecordLiteral): MezzeNode {
        val spreadEntries = recordLit.getSpreadEntries()

        // If there are spread entries, use RecordSpreadNode
        if (spreadEntries.isNotEmpty()) {
            val spreadNodes = mutableListOf<MezzeNode>()
            val fieldNames = mutableListOf<String>()
            val fieldValueNodes = mutableListOf<MezzeNode>()

            // Process entries in order - spreads and fields may be interleaved
            // but RecordSpreadNode handles this: later fields override earlier ones
            for (entry in recordLit.entries) {
                when (entry) {
                    is RecordSpreadEntry -> {
                        spreadNodes.add(convertExpression(entry.expression))
                    }
                    is RecordFieldEntry -> {
                        fieldNames.add(entry.name)
                        val valueNode = if (entry.value != null) {
                            convertExpression(entry.value!!)
                        } else {
                            convertIdentifier(Identifier(entry.name))
                        }
                        fieldValueNodes.add(valueNode)
                    }
                }
            }

            return RecordSpreadNode.create(
                spreadNodes.toTypedArray(),
                fieldNames.toTypedArray(),
                fieldValueNodes.toTypedArray()
            )
        }

        // No spreads - use simple RecordLiteralNode
        val fieldNames = mutableListOf<String>()
        val fieldValueNodes = mutableListOf<MezzeNode>()

        for (entry in recordLit.getFieldEntries()) {
            fieldNames.add(entry.name)
            val valueNode = if (entry.value != null) {
                convertExpression(entry.value!!)
            } else {
                convertIdentifier(Identifier(entry.name))
            }
            fieldValueNodes.add(valueNode)
        }

        // Note: We could use typed storage here based on inferredType, but the current
        // BuiltinBinaryNodes use hardcoded field indices that assume Object storage.
        // Using allObjects ensures compatibility with existing builtin implementations.
        // Future optimization: update BuiltinNodes to use Shape-based field access,
        // then enable typed record creation here.
        return RecordLiteralNode.allObjects(
            fieldNames.toTypedArray(),
            fieldValueNodes.toTypedArray()
        )
    }

    private fun convertArrayLiteral(arrayLit: ArrayLiteral): MezzeNode {
        // Check if there are spread elements
        val hasSpread = arrayLit.elements.any { it is ArraySpreadElement }

        if (hasSpread) {
            // Array has spread elements - use ArraySpreadNode
            // Spreads come first, then regular elements (enforced by parser)
            val spreadArrays = mutableListOf<MezzeNode>()
            val appendElements = mutableListOf<MezzeNode>()

            for (element in arrayLit.elements) {
                when (element) {
                    is ArraySpreadElement -> spreadArrays.add(convertExpression(element.expression))
                    is ArrayRegularElement -> appendElements.add(convertExpression(element.expression))
                }
            }

            return ArraySpreadNode.create(
                spreadArrays.toTypedArray(),
                appendElements.toTypedArray()
            )
        } else {
            // Simple array without spread - use ArrayLiteralNode
            val elementNodes = arrayLit.elements.map { element ->
                (element as ArrayRegularElement).expression
            }.map { convertExpression(it) }.toTypedArray()

            return OptimizedArrayLiteralNode.create(elementNodes)
        }
    }

    private fun convertArrayAccess(arrayAccess: ArrayAccess): MezzeNode {
        val arrayNode = convertExpression(arrayAccess.array)
        val indexNode = convertExpression(arrayAccess.index)
        return ArrayAccessNode.create(arrayNode, indexNode)
    }

    private fun convertMemberAccess(memberAccess: MemberAccess): MezzeNode {
        // MemberAccess is for dot syntax (record.field), not module-qualified access.
        // Module access like bytes::from_string is parsed as Identifier("bytes::from_string"),
        // not MemberAccess, so we just handle record field access here.
        val receiverNode = convertExpression(memberAccess.receiver)

        // Note: We cannot use StaticFieldAccessNode here because the storage index
        // depends on the field ordering in the record literal, which we don't know.
        // CachedFieldAccessNode provides efficient polymorphic inline caching that
        // resolves the field location at runtime after the first access.
        return RecordFieldAccessNode.createByName(receiverNode, memberAccess.member)
    }

    private fun convertVariantConstruction(variantConstr: VariantConstruction): MezzeNode {
        // Special case: True and False are boolean literals, not variants
        // In Mezze, Bool is defined as | True, False | but we optimize to native booleans
        if (variantConstr.constructor == "True" && variantConstr.value == null) {
            return BoolLiteralNode(true)
        }
        if (variantConstr.constructor == "False" && variantConstr.value == null) {
            return BoolLiteralNode(false)
        }

        val tag = getTagForConstructor(variantConstr.constructor)
        val payloadNode = variantConstr.value?.let { convertExpression(it) }
        return VariantConstructNode.create(tag, variantConstr.constructor, payloadNode)
    }

    private fun convertDoExpression(doExpr: DoExpression): MezzeNode {
        val nodes = mutableListOf<MezzeNode>()

        for (stmt in doExpr.statements) {
            when (stmt) {
                is LetStatement -> {
                    val letBinding = LetBinding(
                        pattern = stmt.pattern,
                        expression = stmt.expression
                    )
                    nodes.add(convertLetBinding(letBinding))
                }
                is ExpressionStatement -> {
                    nodes.add(convertExpression(stmt.expression))
                }
            }
        }

        nodes.add(convertExpression(doExpr.result))
        return BlockNode(nodes.toTypedArray())
    }

    private fun convertPerformExpression(performExpr: PerformExpression): MezzeNode {
        val handlers = performExpr.handlers
        if (handlers.isEmpty()) {
            // No handlers, just convert the body
            return convertExpression(performExpr.expression)
        }

        // Process handlers from right to left (innermost first) to create nested structure
        // perform A, B in body  =>  perform A in (perform B in body)
        // This ensures all handlers are properly installed before the body runs
        var currentBody = convertExpression(performExpr.expression)

        for (handler in handlers.reversed()) {
            currentBody = convertSingleHandler(handler, performExpr.resolvedHandlers, currentBody)
        }

        return currentBody
    }

    /**
     * Convert a single handler wrapping a body node.
     * @param handler The handler to convert
     * @param resolvedHandlers Map from the PerformExpression with resolved handler info from typechecker
     * @param bodyNode The body to wrap
     */
    private fun convertSingleHandler(
        handler: PerformHandler,
        resolvedHandlers: Map<String, ResolvedEffectHandler>,
        bodyNode: MezzeNode
    ): MezzeNode {
        val mapping = handlerToEffectMapping[handler.name]

        // Also check resolved handlers from typechecker for cross-module impls
        val resolvedInfo = resolvedHandlers.values.find { it.performerName == handler.name }

        // Check for parameterized impl (has parameters)
        if (handler.parameters != null) {
            val parametersNode = convertExpression(handler.parameters!!)

            if (mapping != null) {
                val (effectName, contentHash) = mapping

                // Check if this is a scoped parameterized effect
                val scopedInfo = scopedEffectInfo[contentHash]
                if (scopedInfo != null && scopedInfo.isScopedEffect) {
                    return ParameterizedScopedHandleNode(
                        handler.name,
                        arrayOf(effectName),
                        arrayOf(contentHash),
                        parametersNode,
                        bodyNode,
                        scopedInfo.scopeSlot,
                        scopedInfo.initializeScopeNode,
                        scopedInfo.finalizeScopeNode
                    )
                }

                return ParameterizedHandleNode(
                    handler.name,
                    arrayOf(effectName),
                    arrayOf(contentHash),
                    parametersNode,
                    bodyNode
                )
            }

            // Use resolved info from typechecker for cross-module impls
            val effectName = resolvedInfo?.effectName ?: handler.name
            val contentHash = resolvedInfo?.implDeclarationHash ?: ""

            // Check if resolved info indicates a scoped parameterized effect (for stdlib imports)
            if (resolvedInfo != null && resolvedInfo.isScopedEffect) {
                val scopeSlot = getOrCreateSlot("__scope_${contentHash.ifEmpty { handler.name }}__")

                // initialize_scope and finalize_scope are lambdas: {} -> ...
                // We need to invoke them with {} as argument
                val initNode = resolvedInfo.initializeScopeExpr?.let { expr ->
                    val lambdaNode = convertExpression(expr)
                    val emptyRecordArg = RecordLiteralNode.allObjects(emptyArray(), emptyArray())
                    InvokeNode.create(lambdaNode, arrayOf(emptyRecordArg))
                }
                val finalizeNode = resolvedInfo.finalizeScopeExpr?.let { expr ->
                    val lambdaNode = convertExpression(expr)
                    val emptyRecordArg = RecordLiteralNode.allObjects(emptyArray(), emptyArray())
                    InvokeNode.create(lambdaNode, arrayOf(emptyRecordArg))
                }

                return ParameterizedScopedHandleNode(
                    handler.name,
                    arrayOf(effectName),
                    if (contentHash.isNotEmpty()) arrayOf(contentHash) else emptyArray(),
                    parametersNode,
                    bodyNode,
                    scopeSlot,
                    initNode,
                    finalizeNode
                )
            }

            return ParameterizedHandleNode(
                handler.name,
                arrayOf(effectName),
                if (contentHash.isNotEmpty()) arrayOf(contentHash) else emptyArray(),
                parametersNode,
                bodyNode
            )
        }

        // Non-parameterized handler
        if (mapping != null) {
            val (effectName, contentHash) = mapping
            val localSlot = contentHashToSlot[contentHash]

            // Check if this is a scoped effect
            val scopedInfo = scopedEffectInfo[contentHash]
            if (scopedInfo != null && scopedInfo.isScopedEffect) {
                return HandleNode.createScoped(
                    localSlot ?: -1,
                    contentHash,
                    arrayOf(effectName),
                    handler.name,
                    bodyNode,
                    scopedInfo.scopeSlot,
                    scopedInfo.initializeScopeNode,
                    scopedInfo.finalizeScopeNode
                )
            }

            return HandleNode.create(
                localSlot ?: -1,
                contentHash,
                arrayOf(effectName),
                handler.name,
                bodyNode
            )
        }

        // Use resolved info from typechecker for cross-module impls
        val effectName = resolvedInfo?.effectName ?: handler.name
        val contentHash = resolvedInfo?.implDeclarationHash

        // Check if resolved info indicates a scoped effect (for stdlib imports)
        if (resolvedInfo != null && resolvedInfo.isScopedEffect) {
            val scopeSlot = getOrCreateSlot("__scope_${contentHash ?: handler.name}__")

            // initialize_scope and finalize_scope are lambdas: {} -> ...
            // We need to invoke them with {} as argument
            // Convert the lambda expressions then wrap in InvokeNode
            val initNode = resolvedInfo.initializeScopeExpr?.let { expr ->
                val lambdaNode = convertExpression(expr)
                val emptyRecordArg = RecordLiteralNode.allObjects(emptyArray(), emptyArray())
                InvokeNode.create(lambdaNode, arrayOf(emptyRecordArg))
            }
            val finalizeNode = resolvedInfo.finalizeScopeExpr?.let { expr ->
                val lambdaNode = convertExpression(expr)
                val emptyRecordArg = RecordLiteralNode.allObjects(emptyArray(), emptyArray())
                InvokeNode.create(lambdaNode, arrayOf(emptyRecordArg))
            }

            return HandleNode.createScoped(
                -1,  // No local slot for cross-module impls
                contentHash ?: "",
                arrayOf(effectName),
                handler.name,
                bodyNode,
                scopeSlot,
                initNode,
                finalizeNode
            )
        }

        return DynamicHandleNode(
            handler.name,
            arrayOf(effectName),
            if (contentHash != null) arrayOf(contentHash) else emptyArray(),
            bodyNode
        )
    }

    private fun convertStateMachineExpression(stateMachine: StateMachineExpression): MezzeNode {
        val states = stateMachine.states

        if (states.isEmpty()) {
            return UnitLiteralNode()
        }

        // Pre-allocate slots for state machine variables
        val inputSlot = getOrCreateSlot("__state_machine_input__")
        val stateIndexSlot = getOrCreateSlotWithKind("__state_index__", ReadLocalNode.KIND_LONG)

        // Collect captured variables
        val capturedVars = stateMachine.capturedVariables.distinct()
        val capturedVarSlots = capturedVars.map { getOrCreateSlot(it) }.toIntArray()
        val capturedVarNames = capturedVars.toTypedArray()

        // Convert each state to a node
        val stateNodes = mutableListOf<MezzeNode>()
        val stateTypes = mutableListOf<Int>()
        val nextStateIndices = mutableListOf<Int>()

        for (state in states) {
            when (state) {
                is ComputeState -> {
                    stateNodes.add(convertExpression(state.computation))
                    stateTypes.add(StateMachineNode.STATE_COMPUTE)
                    nextStateIndices.add(state.nextStateId)
                }
                is YieldState -> {
                    stateNodes.add(convertExpression(state.valueExpression))
                    stateTypes.add(StateMachineNode.STATE_YIELD)
                    nextStateIndices.add(state.nextStateId)
                }
                is CompleteState -> {
                    stateNodes.add(convertExpression(state.resultExpression))
                    stateTypes.add(StateMachineNode.STATE_COMPLETE)
                    nextStateIndices.add(-1) // Terminal
                }
            }
        }

        return StateMachineNode(
            stateNodes.toTypedArray(),
            stateTypes.toIntArray(),
            nextStateIndices.toIntArray(),
            inputSlot,
            stateIndexSlot,
            capturedVarSlots,
            capturedVarNames,
            stateMachine.initialState
        )
    }

    fun getOrCreateSlot(name: String): Int {
        variableInfo[name]?.let { return it.slot }
        val slot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, name, null)
        variableInfo[name] = SlotInfo(slot, ReadLocalNode.KIND_OBJECT, isClosure = false, isTopLevel = isTopLevel)
        return slot
    }

    /**
     * Create a slot for a variable that needs fallback global lookup.
     * Used for variables from outer scopes (module level, parent functions).
     */
    private fun getOrCreateSlotWithFallback(name: String): Int {
        variableInfo[name]?.let { return it.slot }
        val slot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, name, null)
        variableInfo[name] = SlotInfo(slot, ReadLocalNode.KIND_OBJECT, isClosure = false, isTopLevel = false, needsFallback = true)
        return slot
    }

    fun markAsClosure(name: String) {
        val info = variableInfo[name] ?: return
        if (!info.isClosure) {
            variableInfo[name] = info.copy(isClosure = true)
        }
    }

    fun getOrCreateSlotWithKind(name: String, kind: Int): Int {
        variableInfo[name]?.let { return it.slot }
        val frameKind = when (kind) {
            ReadLocalNode.KIND_LONG -> FrameSlotKind.Long
            ReadLocalNode.KIND_DOUBLE -> FrameSlotKind.Double
            ReadLocalNode.KIND_BOOLEAN -> FrameSlotKind.Boolean
            else -> FrameSlotKind.Object
        }
        val slot = frameDescriptorBuilder.addSlot(frameKind, name, null)
        variableInfo[name] = SlotInfo(slot, kind, isClosure = false, isTopLevel = isTopLevel)
        return slot
    }

    private fun inferKind(expr: Expression): Int {
        val inferredType = expr.inferredType
        if (inferredType != null) {
            return kindFromType(inferredType)
        }

        return when (expr) {
            is IntLiteral, is NatLiteral -> ReadLocalNode.KIND_LONG
            is FloatLiteral, is FractionLiteral -> ReadLocalNode.KIND_DOUBLE
            is BooleanLiteral -> ReadLocalNode.KIND_BOOLEAN
            else -> ReadLocalNode.KIND_OBJECT
        }
    }

    /**
     * Get the appropriate frame slot kind for a given MezzeType.
     * Used for pattern bindings and typed variables.
     */
    private fun kindFromType(type: MezzeType): Int {
        return when (type) {
            is MezzeType.IntType -> ReadLocalNode.KIND_LONG
            is MezzeType.NatType -> ReadLocalNode.KIND_LONG
            is MezzeType.FloatType -> ReadLocalNode.KIND_DOUBLE
            is MezzeType.BoolType -> ReadLocalNode.KIND_BOOLEAN
            else -> ReadLocalNode.KIND_OBJECT
        }
    }

    fun getFrameDescriptor(): FrameDescriptor {
        return frameDescriptorBuilder.build()
    }

    /**
     * Get the variable name to slot mapping.
     * Used for extracting module exports after execution.
     */
    fun getVariableSlots(): Map<String, Int> {
        return variableInfo.mapValues { it.value.slot }
    }

    /**
     * Convert native function calls to specialized Truffle nodes.
     * Returns null if the function is not a recognized native function.
     *
     * Uses NativeFunctions dispatch for all __native_* functions.
     * Continuation functions have specialized nodes for control flow.
     */
    private fun convertNativeFunctionCall(funcName: String, args: Expression): MezzeNode? {
        // Continuation functions need special handling for control flow
        return when (funcName) {
            "__native_continuation_delimit" -> ContinuationDelimitNode.create()
            "__native_continuation_activate" -> ContinuationActivateNode.create(convertExpression(args))
            "__native_continuation_capture" -> ContinuationCaptureNode.create(convertExpression(args))
            "__native_continuation_suspend" -> ContinuationSuspendNode.create()
            "__native_continuation_suspend_with" -> ContinuationSuspendWithNode.create(convertExpression(args))
            "__native_continuation_resume" -> ContinuationResumeNode.create(convertExpression(args))
            "__native_continuation_fork" -> ContinuationForkNode.create(convertExpression(args))
            "__native_continuation_abort" -> ContinuationAbortNode.create(convertExpression(args))
            "__native_continuation_extract_suspended" -> ContinuationExtractSuspendedNode.create(convertExpression(args))
            "__native_continuation_deactivate" -> ContinuationDeactivateNode.create(convertExpression(args))

            // Built-in crash function for program termination with error
            "crash" -> com.mezze.interpreter.v2.nodes.builtin.NativeDispatch.create("crash", convertExpression(args))

            // All other native functions use the dispatch node
            else -> {
                if (funcName.startsWith("__native_")) {
                    // Try NativeFunctions first (optimized jump table for common ops)
                    if (NativeFunctions.isNativeFunction(funcName)) {
                        NativeFunctions.create(funcName, convertExpression(args))
                    } else {
                        // Fall back to NativeDispatch (HashMap-based, handles all remaining natives)
                        com.mezze.interpreter.v2.nodes.builtin.NativeDispatch.create(funcName, convertExpression(args))
                    }
                } else if (funcName.startsWith("native_") && com.mezze.interpreter.v2.nodes.builtin.NativeDispatch.isNativeFunction(funcName)) {
                    // Legacy native functions without double underscore prefix (backward compatibility)
                    com.mezze.interpreter.v2.nodes.builtin.NativeDispatch.create(funcName, convertExpression(args))
                } else {
                    null
                }
            }
        }
    }

    /**
     * Wraps a body node with nested record field extraction.
     *
     * For a pattern like `Some { val = { value, cont = k } }`:
     * 1. The variant pattern binds `val` to a temp slot (by index, not name)
     * 2. This method wraps the body with code to extract `value` and `k` from the temp slot
     *
     * @param tempSlot The slot index holding the intermediate record
     * @param nestedPattern The nested RecordPattern to extract fields from
     * @param bodyNode The original body node to wrap
     * @return A BlockNode that extracts fields then executes the body
     */
    private fun wrapBodyWithNestedRecordExtraction(
        tempSlot: Int,
        nestedPattern: RecordPattern,
        bodyNode: MezzeNode
    ): MezzeNode {
        val statements = mutableListOf<MezzeNode>()
        // Track further nested patterns: (slot, pattern)
        val furtherNested = mutableListOf<Pair<Int, RecordPattern>>()

        // For each entry in the nested pattern, create a field access and write
        for (entry in nestedPattern.entries) {
            val fieldName = entry.name

            // Determine the variable name to bind
            val bindName = when (val p = entry.pattern) {
                is VariablePattern -> p.name
                null -> entry.name // shorthand: { x } means x = x
                is RecordPattern -> {
                    // Doubly-nested patterns - allocate anonymous slot
                    val nestedTempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)

                    // Read the field and write to nested temp slot
                    val fieldAccessNode = RecordFieldAccessNode.createByName(
                        ReadLocalNode.createObject(tempSlot),
                        fieldName
                    )
                    statements.add(WriteLocalNode.createObject(fieldAccessNode, nestedTempSlot))

                    // Track for recursive processing
                    furtherNested.add(nestedTempSlot to p)
                    continue
                }
                else -> continue // Skip unsupported nested patterns
            }

            val targetSlot = getOrCreateSlot(bindName)

            // Create a field access node: temp.fieldName (using dynamic by-name access)
            val fieldAccessNode = RecordFieldAccessNode.createByName(
                ReadLocalNode.createObject(tempSlot),
                fieldName
            )

            // Write the extracted field to the target slot
            statements.add(WriteLocalNode.createObject(fieldAccessNode, targetSlot))
        }

        // Recursively wrap body with further nested extractions
        var wrappedBody = bodyNode
        for ((nestedSlot, nestedPattern) in furtherNested.reversed()) {
            wrappedBody = wrapBodyWithNestedRecordExtraction(nestedSlot, nestedPattern, wrappedBody)
        }

        // Add the wrapped body as the final statement
        statements.add(wrappedBody)

        return BlockNode(statements.toTypedArray())
    }

    /**
     * Collects nested record field extraction statements without wrapping.
     * This is used when we need to extract fields BEFORE a guard runs.
     *
     * @param tempSlot The slot index holding the intermediate record
     * @param nestedPattern The nested RecordPattern to extract fields from
     * @param statements The list to add extraction statements to
     */
    private fun collectNestedExtractionStatements(
        tempSlot: Int,
        nestedPattern: RecordPattern,
        statements: MutableList<MezzeNode>
    ) {
        val furtherNested = mutableListOf<Pair<Int, RecordPattern>>()

        for (entry in nestedPattern.entries) {
            val fieldName = entry.name

            val bindName = when (val p = entry.pattern) {
                is VariablePattern -> p.name
                null -> entry.name // shorthand: { x } means x = x
                is RecordPattern -> {
                    // Doubly-nested patterns - allocate anonymous slot
                    val nestedTempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)

                    // Read the field and write to nested temp slot
                    val fieldAccessNode = RecordFieldAccessNode.createByName(
                        ReadLocalNode.createObject(tempSlot),
                        fieldName
                    )
                    statements.add(WriteLocalNode.createObject(fieldAccessNode, nestedTempSlot))

                    // Track for recursive processing
                    furtherNested.add(nestedTempSlot to p)
                    continue
                }
                else -> continue // Skip unsupported nested patterns
            }

            val targetSlot = getOrCreateSlot(bindName)

            // Create a field access node: temp.fieldName
            val fieldAccessNode = RecordFieldAccessNode.createByName(
                ReadLocalNode.createObject(tempSlot),
                fieldName
            )

            // Write the extracted field to the target slot
            statements.add(WriteLocalNode.createObject(fieldAccessNode, targetSlot))
        }

        // Recursively collect extraction statements for further nested patterns
        for ((nestedSlot, nestedPat) in furtherNested) {
            collectNestedExtractionStatements(nestedSlot, nestedPat, statements)
        }
    }

    /**
     * Wraps a body node with nested array pattern extraction.
     *
     * For a pattern like `[[x, ...rest], ...rows]`:
     * 1. The outer array pattern binds element 0 to a temp slot (the inner array)
     * 2. This method extracts `x` and `rest` from that inner array
     *
     * @param tempSlot The slot holding the intermediate array
     * @param nestedPattern The nested ArrayPattern to match against
     * @param bodyNode The original body node to wrap
     * @return A BlockNode that extracts elements then executes the body
     */
    private fun wrapBodyWithNestedArrayExtraction(
        tempSlot: Int,
        nestedPattern: ArrayPattern,
        bodyNode: MezzeNode
    ): MezzeNode {
        val statements = mutableListOf<MezzeNode>()
        // Track further nested array patterns: (tempSlot, pattern)
        val furtherNestedArrays = mutableListOf<Pair<Int, ArrayPattern>>()
        // Track further nested constructor patterns: (tempSlot, pattern)
        val furtherNestedConstructors = mutableListOf<Pair<Int, ConstructorPattern>>()

        // For each element in the nested pattern, extract from array and bind
        nestedPattern.elements.forEachIndexed { idx, elemPattern ->
            when (elemPattern) {
                is VariablePattern -> {
                    val targetSlot = getOrCreateSlot(elemPattern.name)
                    // array[idx] -> targetSlot
                    val accessNode = ArrayAccessNode.create(
                        ReadLocalNode.createObject(tempSlot),
                        IntLiteralNode(idx.toLong())
                    )
                    statements.add(WriteLocalNode.createObject(accessNode, targetSlot))
                }
                is ArrayPattern -> {
                    // Further nested array - allocate temp slot
                    val nestedTempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                    val accessNode = ArrayAccessNode.create(
                        ReadLocalNode.createObject(tempSlot),
                        IntLiteralNode(idx.toLong())
                    )
                    statements.add(WriteLocalNode.createObject(accessNode, nestedTempSlot))
                    furtherNestedArrays.add(nestedTempSlot to elemPattern)
                }
                is ConstructorPattern -> {
                    // Further nested constructor - allocate temp slot
                    val nestedTempSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Object, null, null)
                    val accessNode = ArrayAccessNode.create(
                        ReadLocalNode.createObject(tempSlot),
                        IntLiteralNode(idx.toLong())
                    )
                    statements.add(WriteLocalNode.createObject(accessNode, nestedTempSlot))
                    furtherNestedConstructors.add(nestedTempSlot to elemPattern)
                }
                is WildcardPattern -> {
                    // No binding needed
                }
                else -> {
                    // Other patterns not supported yet
                }
            }
        }

        // Handle rest pattern: ...rest
        nestedPattern.restPattern?.let { restName ->
            val restSlot = getOrCreateSlot(restName)
            // Create a slice from index=elements.size to end
            val sliceNode = ArraySliceNode.create(
                ReadLocalNode.createObject(tempSlot),
                IntLiteralNode(nestedPattern.elements.size.toLong())
            )
            statements.add(WriteLocalNode.createObject(sliceNode, restSlot))
        }

        // Recursively wrap body with further nested extractions
        var wrappedBody = bodyNode
        for ((nestedSlot, nestedPat) in furtherNestedArrays.reversed()) {
            wrappedBody = wrapBodyWithNestedArrayExtraction(nestedSlot, nestedPat, wrappedBody)
        }
        for ((nestedSlot, nestedPat) in furtherNestedConstructors.reversed()) {
            wrappedBody = wrapBodyWithNestedConstructorExtraction(nestedSlot, nestedPat, wrappedBody)
        }

        // Add the wrapped body as the final statement
        statements.add(wrappedBody)

        return BlockNode(statements.toTypedArray())
    }

    /**
     * Wraps a body node with nested constructor pattern extraction.
     *
     * For a pattern like `[Ok {val}]`:
     * 1. The outer array pattern binds element 0 to a temp slot (the variant)
     * 2. This method extracts `val` from the variant's payload record
     *
     * This creates a nested match expression that:
     * - Checks if the variant has the expected tag
     * - Extracts fields from the payload record if matched
     * - Returns null if the variant tag doesn't match (pattern fails)
     *
     * @param tempSlot The slot holding the intermediate variant
     * @param nestedPattern The nested ConstructorPattern to match against
     * @param bodyNode The original body node to wrap
     * @return A MatchNode that checks the variant and extracts fields
     */
    private fun wrapBodyWithNestedConstructorExtraction(
        tempSlot: Int,
        nestedPattern: ConstructorPattern,
        bodyNode: MezzeNode
    ): MezzeNode {
        // Read the variant from temp slot
        val valueNode = ReadLocalNode.createObject(tempSlot)

        // Create the branch that matches the expected constructor
        val tag = getTagForConstructor(nestedPattern.constructor)
        val fieldNames = nestedPattern.recordPattern?.let { recordPattern ->
            recordPattern.entries.map { it.name }.toTypedArray()
        } ?: arrayOf()
        val fieldSlots = nestedPattern.recordPattern?.let { recordPattern ->
            recordPattern.entries.map { entry ->
                when (val fp = entry.pattern) {
                    is VariablePattern -> getOrCreateSlot(fp.name)
                    null -> getOrCreateSlot(entry.name)
                    else -> -1
                }
            }.toIntArray()
        } ?: intArrayOf()

        val matchBranch = MatchBranchNode.variantPatternWithFieldNames(tag, fieldNames, fieldSlots, bodyNode)

        // Create match node with just this branch (no wildcard - pattern match will fail if wrong variant)
        return MatchNode(valueNode, arrayOf(matchBranch), -1)
    }

    /**
     * Wrap a body node with a literal pattern check for nested literal patterns inside array patterns.
     *
     * For a pattern like `[True, False]`:
     * 1. The outer array pattern binds elements to temp slots
     * 2. This method creates a match that checks the element value equals the literal
     *
     * @param tempSlot The slot holding the element value to check
     * @param nestedPattern The nested LiteralPattern to match against
     * @param bodyNode The original body node to wrap
     * @return A MatchNode that checks the literal value
     */
    private fun wrapBodyWithNestedLiteralCheck(
        tempSlot: Int,
        nestedPattern: LiteralPattern,
        bodyNode: MezzeNode
    ): MezzeNode {
        // Read the element value from temp slot
        val valueNode = ReadLocalNode.createObject(tempSlot)

        // Convert AST LiteralValue to runtime value
        val literalValue: Any = when (val lit = nestedPattern.value) {
            is BooleanLiteralValue -> lit.value
            is IntLiteralValue -> lit.value
            is NatLiteralValue -> lit.value
            is FloatLiteralValue -> lit.value
            is StringLiteralValue -> lit.value
        }

        // Create a match branch for this literal
        val literalBranch = MatchBranchNode.literalPattern(literalValue, bodyNode)

        // Create match node - if the literal doesn't match, execute returns null
        return MatchNode(valueNode, arrayOf(literalBranch), -1)
    }
}
