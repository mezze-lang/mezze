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
import com.mezze.interpreter.v2.nodes.array.ArrayAccessNode
import com.mezze.interpreter.v2.nodes.array.ArrayLiteralNode as OptimizedArrayLiteralNode
import com.mezze.interpreter.v2.nodes.array.ArraySpreadNode
import com.mezze.interpreter.v2.nodes.effect.HandleNode
import com.mezze.interpreter.v2.nodes.effect.DynamicEffectCallNode
import com.mezze.interpreter.v2.nodes.builtin.BuiltinBinaryNodes.*
import com.mezze.interpreter.v2.nodes.builtin.BuiltinUnaryNodes.*
import com.mezze.interpreter.v2.nodes.builtin.NativeFunctions
import com.mezze.interpreter.v2.nodes.ability.ResolvedAbilityCallNode as OptimizedAbilityCallNode
import com.mezze.interpreter.v2.nodes.continuation.*
import com.mezze.interpreter.v2.nodes.statemachine.StateMachineNode
import com.mezze.interpreter.v2.runtime.MezzeContext

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
    private val parentVariableSlots: Map<String, Int>? = null
) {

    private var frameDescriptorBuilder = FrameDescriptor.newBuilder()

    /**
     * Combined slot info - stores slot index, kind, and closure flag in a single lookup.
     * Reduces 3 map lookups to 1 for each variable access.
     */
    data class SlotInfo(val slot: Int, val kind: Int, val isClosure: Boolean = false)

    private val variableInfo = mutableMapOf<String, SlotInfo>()

    private val handlerToEffectMapping = mutableMapOf<String, Pair<String, String>>().apply {
        parentHandlerMapping?.let { putAll(it) }
    }

    private val contentHashToSlot = mutableMapOf<String, Int>()

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
            ?: throw RuntimeException("Content hash required for effect handler: ${impl.effectName}/${impl.performerName}")

        handlerToEffectMapping[impl.performerName] = impl.effectName to contentHash

        val hashSlot = getOrCreateSlot(contentHash)
        contentHashToSlot[contentHash] = hashSlot
        val performerSlot = getOrCreateSlot(impl.performerName)

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

        val operationNames = mutableListOf<String>()
        val operationBodyNodes = mutableListOf<MezzeNode>()
        val operationParamNamesList = mutableListOf<List<String>>()
        val operationFrameDescriptors = mutableListOf<FrameDescriptor>()
        val operationClosureVarSlots = mutableListOf<IntArray>()

        for (op in impl.operations) {
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
                parentVariableSlots = implParamSlots.toMap()
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

        val implDefinitionNode = ParameterizedImplDefinitionNode(
            implName = impl.performerName,
            implParamNames = implParamNames,
            implParamSlots = implParamSlots,
            operationNames = operationNames,
            operationBodyNodes = operationBodyNodes,
            operationParamNames = operationParamNamesList,
            operationFrameDescriptors = operationFrameDescriptors,
            operationClosureVarSlots = operationClosureVarSlots,
            implFrameDescriptor = implFrameDescriptor
        )

        val hashSlot = getOrCreateSlot(contentHash)
        contentHashToSlot[contentHash] = hashSlot
        val performerSlot = getOrCreateSlot(impl.performerName)

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
            contentHash = contentHash
        )
    }

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
                // Use WriteLocalWithContextNode for function bindings to support recursion.
                // When a function references itself (recursive call), the frame slot may not
                // be written yet, but the fallback lookup will find it in the global context.
                if (letBinding.expression is LambdaExpression) {
                    WriteLocalWithContextNode.create(valueNode, slot, pattern.name, letBinding.contentHash)
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
        val tempSlot = getOrCreateSlot("__temp_record_${System.nanoTime()}")

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
            is NatLiteral -> IntLiteralNode(expr.value.toLong())
            is FloatLiteral -> FloatLiteralNode(expr.value)
            is FractionLiteral -> FloatLiteralNode(expr.numerator.toDouble() / expr.denominator.toDouble())
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

        // Variable not found in local slots - module-level function or import
        // If it has content hash, use strict lookup; otherwise it's a local recursive reference
        val slot = getOrCreateSlot(identifier.name)
        if (contentHash != null) {
            return ReadLocalWithFallbackNode.create(slot, identifier.name, contentHash)
        }
        return ReadLocalWithFallbackNode.create(slot, identifier.name)
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

        for ((index, case) in matchExpr.cases.withIndex()) {
            val pattern = case.patterns.firstOrNull() ?: continue

            when (pattern) {
                is ConstructorPattern -> {
                    // Special case: True and False are boolean patterns, not variant patterns
                    // This matches how V2 converts True/False constructors to boolean literals
                    if (pattern.constructor == "True" && pattern.recordPattern == null) {
                        val bodyNode = convertExpression(case.expression)
                        branches.add(MatchBranchNode.literalPattern(true, bodyNode))
                    } else if (pattern.constructor == "False" && pattern.recordPattern == null) {
                        val bodyNode = convertExpression(case.expression)
                        branches.add(MatchBranchNode.literalPattern(false, bodyNode))
                    } else {
                        val tag = getTagForConstructor(pattern.constructor)
                        // Collect both field names and slots for proper record extraction
                        val fieldNames = pattern.recordPattern?.let { recordPattern ->
                            recordPattern.entries.map { it.name }.toTypedArray()
                        } ?: arrayOf()
                        val fieldSlots = pattern.recordPattern?.let { recordPattern ->
                            recordPattern.entries.map { entry ->
                                when (val fp = entry.pattern) {
                                    is VariablePattern -> getOrCreateSlot(fp.name)
                                    null -> getOrCreateSlot(entry.name)
                                    else -> -1
                                }
                            }.toIntArray()
                        } ?: intArrayOf()

                        val bodyNode = convertExpression(case.expression)
                        branches.add(MatchBranchNode.variantPatternWithFieldNames(tag, fieldNames, fieldSlots, bodyNode))
                    }
                }
                is VariablePattern -> {
                    val slot = getOrCreateSlot(pattern.name)
                    val bodyNode = convertExpression(case.expression)
                    branches.add(MatchBranchNode.wildcard(slot, bodyNode))
                    wildcardIndex = index
                }
                is WildcardPattern -> {
                    val bodyNode = convertExpression(case.expression)
                    branches.add(MatchBranchNode.wildcardNoBinding(bodyNode))
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
                    branches.add(MatchBranchNode.literalPattern(litValue, bodyNode))
                }
                is ArrayPattern -> {
                    // Array pattern: [x, y], [head, ...tail], [], etc.
                    val elementSlots = pattern.elements.mapNotNull { elemPattern ->
                        when (elemPattern) {
                            is VariablePattern -> getOrCreateSlot(elemPattern.name)
                            is WildcardPattern -> -1 // No binding needed
                            else -> -1 // For now, only support variable patterns
                        }
                    }.toIntArray()

                    val hasRestPattern = pattern.restPattern != null
                    val restSlot = pattern.restPattern?.let { getOrCreateSlot(it) } ?: -1

                    val bodyNode = convertExpression(case.expression)
                    branches.add(MatchBranchNode.arrayPattern(elementSlots, restSlot, hasRestPattern, bodyNode))
                }
                is RecordPattern -> {
                    // Record pattern: {x, y} or {name = n, age = a}
                    val fieldNames = pattern.entries.map { it.name }.toTypedArray()
                    val fieldSlots = pattern.entries.map { entry ->
                        when (val p = entry.pattern) {
                            is VariablePattern -> getOrCreateSlot(p.name)
                            null -> getOrCreateSlot(entry.name) // Shorthand {x} = {x = x}
                            else -> -1
                        }
                    }.toIntArray()
                    val isOpen = pattern.isOpen

                    val bodyNode = convertExpression(case.expression)
                    branches.add(MatchBranchNode.recordPattern(fieldNames, fieldSlots, isOpen, bodyNode))
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
                    branches.add(MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode))
                }
                else -> throw RuntimeException("Unsupported pattern: ${pattern::class.simpleName}")
            }
        }

        return MatchNode(valueNode, branches.toTypedArray(), wildcardIndex)
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
                val isBuiltinAbility = receiverName in listOf(
                    "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod",
                    "Eq", "Ord", "LogicalAnd", "LogicalOr", "LogicalNot",
                    "Inspect"
                )

                if (isBuiltinAbility) {
                    val argsNode = convertExpression(funcCall.arguments)
                    return createBuiltinBinaryOp(receiverName, operationName, argsNode)
                }

                // CRITICAL: Check resolvedAbilities FIRST before falling back to effect dispatch
                // The type checker resolves ability method calls and populates resolvedAbilities
                // If present, this is an ability call, not an effect call
                if (funcCall.resolvedAbilities.isNotEmpty()) {
                    return convertResolvedAbilityCall(funcCall)
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

        val functionNode = convertExpression(funcCall.function)
        val argumentsNode = convertExpression(funcCall.arguments)
        return InvokeNode.create(functionNode, arrayOf(argumentsNode))
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
            parentVariableSlots = this.getVariableSlots()
        )

        // Closure slots come first (only for capturable vars), then parameter slots
        // Layout: [closure0, closure1, ..., param0, param1, ...]
        capturableVars.forEach { varName ->
            functionConverter.getOrCreateSlot(varName)
            // Mark as closure variable so convertIdentifier uses fallback lookup.
            // This is critical for recursive functions where the closure value is null.
            functionConverter.markAsClosure(varName)
        }
        paramNames.forEach { functionConverter.getOrCreateSlot(it) }
        // Global vars get slots after parameters (will be read via GlobalLookupNode)
        // Don't allocate slots for them - let convertIdentifier handle them with fallback

        val rawBodyNode = functionConverter.convertExpression(lambda.body)
        val hasTailCalls = containsTailCalls(lambda.body)
        val closureSlots = capturableVars.map { variableInfo[it]!!.slot }.toIntArray()
        val bodyNode = if (hasTailCalls) {
            // Pass parameter names so TailCallLoopNode can unpack record arguments correctly
            TailCallLoopNode(rawBodyNode, paramNames.size, closureSlots.size, paramNames.toTypedArray())
        } else {
            rawBodyNode
        }

        val functionFrameDescriptor = functionConverter.getFrameDescriptor()

        // Pass content hash for CallTarget caching
        return FunctionDefinitionNode(
            functionName,
            contentHash,
            closureSlots,
            paramNames.toTypedArray(),
            bodyNode,
            functionFrameDescriptor
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
                    e.getFieldEntries().forEach { entry ->
                        if (entry.value != null) {
                            visit(entry.value!!)
                        } else {
                            variables.add(entry.name)
                        }
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
        // PerformExpression has handlers list, get the first one
        val firstHandler = performExpr.handlers.firstOrNull()
        if (firstHandler != null) {
            val mapping = handlerToEffectMapping[firstHandler.name]

            if (mapping != null) {
                val (effectName, contentHash) = mapping
                val bodyNode = convertExpression(performExpr.expression)
                val localSlot = contentHashToSlot[contentHash]

                // Pass effect names so HandleNode can register in context
                return HandleNode.create(
                    localSlot ?: -1,
                    contentHash,
                    arrayOf(effectName),
                    firstHandler.name,
                    bodyNode
                )
            }
        }

        // Dynamic handler lookup using resolved handler info from typechecker
        val bodyNode = convertExpression(performExpr.expression)

        // Build arrays of effect names and content hashes from resolvedHandlers
        val effectNames = performExpr.resolvedHandlers.keys.toTypedArray()
        val contentHashes = performExpr.resolvedHandlers.values.mapNotNull { it.implDeclarationHash }.toTypedArray()

        // Check if this is a parameterized impl (has parameters)
        if (firstHandler?.parameters != null) {
            val parametersNode = convertExpression(firstHandler.parameters!!)
            return ParameterizedHandleNode(
                firstHandler.name,
                effectNames,
                contentHashes,
                parametersNode,
                bodyNode
            )
        }

        return DynamicHandleNode(
            firstHandler?.name ?: "unknown",
            effectNames,
            contentHashes,
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
        variableInfo[name] = SlotInfo(slot, ReadLocalNode.KIND_OBJECT)
        return slot
    }

    fun markAsClosure(name: String) {
        val info = variableInfo[name] ?: return
        if (!info.isClosure) {
            variableInfo[name] = info.copy(isClosure = true)
        }
    }

    private fun getOrCreateSlotWithKind(name: String, kind: Int): Int {
        variableInfo[name]?.let { return it.slot }
        val frameKind = when (kind) {
            ReadLocalNode.KIND_LONG -> FrameSlotKind.Long
            ReadLocalNode.KIND_DOUBLE -> FrameSlotKind.Double
            ReadLocalNode.KIND_BOOLEAN -> FrameSlotKind.Boolean
            else -> FrameSlotKind.Object
        }
        val slot = frameDescriptorBuilder.addSlot(frameKind, name, null)
        variableInfo[name] = SlotInfo(slot, kind)
        return slot
    }

    private fun inferKind(expr: Expression): Int {
        val inferredType = expr.inferredType
        if (inferredType != null) {
            // Use proper sealed class matching instead of toString() comparison
            return when (inferredType) {
                is MezzeType.IntType -> ReadLocalNode.KIND_LONG
                is MezzeType.NatType -> ReadLocalNode.KIND_LONG
                is MezzeType.FloatType -> ReadLocalNode.KIND_DOUBLE
                is MezzeType.BooleanType -> ReadLocalNode.KIND_BOOLEAN
                else -> ReadLocalNode.KIND_OBJECT
            }
        }

        return when (expr) {
            is IntLiteral, is NatLiteral -> ReadLocalNode.KIND_LONG
            is FloatLiteral, is FractionLiteral -> ReadLocalNode.KIND_DOUBLE
            is BooleanLiteral -> ReadLocalNode.KIND_BOOLEAN
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
                } else {
                    null
                }
            }
        }
    }
}
