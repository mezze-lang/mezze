package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.mono.unwrapSuspended
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typedast.Hash

internal class AbilityChecker(
    private val context: TypingContext,
    private val registry: DeclarationRegistry
) {
    private val codecGenerator = CodecGenerator()

    data class GeneratedCodecImpl(
        val abilityName: String,
        val targetTypes: List<MezzeType>,
        val contentHash: Hash
    )

    private data class TrackedAbilityConstraint(
        val abilityName: String,
        val abilityHash: Hash?,
        val typeArgs: List<MezzeType>,
        val loc: Loc?,
        val fromAnnotation: Boolean = false
    )

    private val generatedCodecImpls = mutableMapOf<Hash, GeneratedCodecImpl>()
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableList<TrackedAbilityConstraint>>()
    private val deferredInference = mutableMapOf<TypeVar, MezzeType>()
    private val associatedTypeResolutionConstraints = mutableMapOf<TypeVar, MutableList<Constraint.AssociatedTypeResolution>>()

    fun getGeneratedCodecImpls(): Collection<GeneratedCodecImpl> = generatedCodecImpls.values
    fun getDeferredInference(): Map<TypeVar, MezzeType> = deferredInference
    fun getAssociatedTypeResolutionConstraints(): Map<TypeVar, MutableList<Constraint.AssociatedTypeResolution>> = associatedTypeResolutionConstraints

    internal fun validateAbilityConstraints(substitution: Substitution): Pair<String, Loc?>? {
        for ((typeVar, abilityConstraints) in typeVarAbilityConstraints) {
            val varType = MezzeType.VarType(typeVar)
            val boundType = substitution.apply(varType)

            if (boundType !is MezzeType.VarType) {
                for (tracked in abilityConstraints) {
                    val abilityName = tracked.abilityName
                    val typeArgs = tracked.typeArgs
                    val errorLoc = tracked.loc
                    val trackedResolvedHash = tracked.abilityHash ?: WellKnownHashConstants.resolveHash(abilityName)
                    if (WellKnownHashConstants.isInspect(trackedResolvedHash)) continue
                    val concreteTypeArgs: List<MezzeType> = typeArgs.map { typeArg ->
                        if (typeArg is MezzeType.VarType &&
                            (typeArg.variable.id == typeVar.id ||
                             typeArg.variable.name.removePrefix("'") == typeVar.name.removePrefix("'"))) {
                            boundType
                        } else {
                            substitution.apply(typeArg)
                        }
                    }

                    val closedTypeArgs = if (isCodecAbility(abilityName, tracked.abilityHash)) {
                        concreteTypeArgs.map { closeOpenRecordsForCodec(it) }
                    } else {
                        concreteTypeArgs
                    }

                    val allConcrete = closedTypeArgs.all { isConcrete(it) }

                    val primaryArg = closedTypeArgs.firstOrNull()
                    if (tracked.fromAnnotation && primaryArg is MezzeType.RecordType) {
                        return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs, errorLoc), errorLoc)
                    }

                    if (allConcrete) {
                        val hasImpl = checkAbilityImplementation(abilityName, closedTypeArgs, tracked.abilityHash)
                        if (!hasImpl) {
                            return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs, errorLoc), errorLoc)
                        }
                    } else {
                        val inferredSubst = tryBidirectionalInference(abilityName, closedTypeArgs, tracked.abilityHash)
                        if (inferredSubst != null) {
                            deferredInference.putAll(inferredSubst.mappings)
                        }
                    }
                }
            }
        }
        return null
    }

    internal fun solveAbilityRequired(constraint: Constraint.AbilityRequired): ConstraintSolver.ConstraintResult {
        val abilityName = constraint.abilityName
        val typeArgs = constraint.typeArgs

        val resolvedHash = constraint.abilityHash ?: WellKnownHashConstants.resolveHash(abilityName)
        if (WellKnownHashConstants.isInspect(resolvedHash)) {
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        val constraintVarName = constraint.typeVar.name.removePrefix("'")
        val typeVarInArgs = typeArgs.any { arg ->
            arg is MezzeType.VarType && arg.variable.name.removePrefix("'") == constraintVarName
        }
        val trackingArgs = if (typeVarInArgs) {
            typeArgs
        } else {
            listOf(MezzeType.VarType(constraint.typeVar)) + typeArgs
        }
        val fromAnnotation = constraint.context?.startsWith("Type annotation on ") == true
        val trackedEntry = TrackedAbilityConstraint(abilityName, constraint.abilityHash, trackingArgs, constraint.loc, fromAnnotation)
        typeVarAbilityConstraints.getOrPut(constraint.typeVar) { mutableListOf() }
            .add(trackedEntry)

        for (typeArg in typeArgs) {
            if (typeArg is MezzeType.VarType && typeArg.variable != constraint.typeVar) {
                val constraints = typeVarAbilityConstraints.getOrPut(typeArg.variable) { mutableListOf() }
                constraints.add(trackedEntry)
            }
        }

        val allConcrete = trackingArgs.all { isConcrete(it) }

        if (allConcrete) {
            val hasImpl = checkAbilityImplementation(abilityName, trackingArgs, constraint.abilityHash)

            if (!hasImpl) {
                val errorMessage = generateAbilityErrorMessage(abilityName, trackingArgs, constraint.loc)
                return ConstraintSolver.ConstraintResult.Failure(errorMessage)
            }

            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        } else {
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }
    }

    internal fun solveAssociatedTypeResolution(
        constraint: Constraint.AssociatedTypeResolution,
        currentSubst: Substitution
    ): ConstraintSolver.ConstraintResult {
        val abilityName = constraint.abilityName
        val primaryType = currentSubst.apply(MezzeType.VarType(constraint.primaryTypeVar))

        DebugLogger.debug(Category.ConstraintSolver) {
            "solveAssociatedTypeResolution: ability=$abilityName, primaryTypeVar=${constraint.primaryTypeVar}, resolved=$primaryType"
        }

        if (primaryType is MezzeType.VarType) {
            DebugLogger.debug(Category.ConstraintSolver) {
                "  Deferring - primary type still a variable: ${primaryType.variable}"
            }
            associatedTypeResolutionConstraints.getOrPut(primaryType.variable) { mutableListOf() }
                .add(constraint)
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        val matchingImplPair = findMatchingAbilityImpl(abilityName, primaryType)
        DebugLogger.debug(Category.ConstraintSolver) {
            "  matchingImpl: ${matchingImplPair?.first?.abilityName} with bindings ${matchingImplPair?.first?.associatedTypeBindings}, " +
                "implTypeVarBindings: ${matchingImplPair?.second}"
        }

        if (matchingImplPair == null) {
            // No impl for this type yet — nothing to resolve. Not an error here;
            // a missing impl surfaces via the AbilityRequired check instead.
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        val matchingImpl = matchingImplPair.first
        val implTypeVarBindings = matchingImplPair.second

        val associatedBindings = matchingImpl.associatedTypeBindings
        if (associatedBindings.isEmpty()) {
            // Impl declares no `having` bindings — nothing to resolve.
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        var resultSubst = Substitution.empty

        for ((assocTypeName, typeVar) in constraint.associatedTypeVars) {
            val binding = associatedBindings[assocTypeName]
                ?: associatedBindings["'$assocTypeName"]

            if (binding != null) {
                val concreteType = substituteAnnotationWithImplBindings(binding, implTypeVarBindings)

                val resolvedTypeVar = currentSubst.apply(MezzeType.VarType(typeVar))

                val unifyResult = unify(resolvedTypeVar, concreteType)
                when (unifyResult) {
                    is UnificationResult.Success -> {
                        resultSubst = unifyResult.substitution.compose(resultSubst)
                    }
                    is UnificationResult.Failure -> {
                        return ConstraintSolver.ConstraintResult.Failure(
                            "Type mismatch for associated type '$assocTypeName': expected $concreteType but got $resolvedTypeVar"
                        )
                    }
                }
            }
        }

        return ConstraintSolver.ConstraintResult.Success(resultSubst)
    }

    private fun substituteAnnotationWithImplBindings(
        annotation: ParsedTypeAnnotation,
        implTypeVarBindings: Map<String, MezzeType>,
    ): MezzeType {
        if (annotation is ParsedNamedType) {
            val name = annotation.name
            implTypeVarBindings[name]?.let { return it }
            implTypeVarBindings["'$name"]?.let { return it }
            if (name.startsWith("'")) {
                implTypeVarBindings[name.removePrefix("'")]?.let { return it }
            }
        }
        return typeAnnotationToMezzeType(annotation)
    }

    private fun findMatchingAbilityImpl(
        abilityName: String,
        primaryType: MezzeType
    ): Pair<TypingContext.AbilityImplInfo, Map<String, MezzeType>>? {
        val allImpls = context.getAbilityImpls(abilityName)

        for (impl in allImpls) {
            val annotations = impl.targetTypeAnnotations ?: continue
            if (annotations.size != 1) continue
            val typeVarBindings = mutableMapOf<String, MezzeType>()
            val matched = TypeAnnotationMatcher.typeMatchesAnnotation(
                primaryType, annotations.first(), registry.typeDeclarations, impl.typeVars, typeVarBindings
            )
            if (matched) return impl to typeVarBindings
        }
        return null
    }

    private fun typeAnnotationToMezzeType(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedTypeApp -> WellKnownTypeApp.of(annotation.constructor, annotation.args.map { typeAnnotationToMezzeType(it) })
            is ParsedSeqType -> MezzeType.SeqType(typeAnnotationToMezzeType(annotation.elementType))
            is ParsedRecordType -> MezzeType.RecordType(fields = annotation.entries.associate { it.name to typeAnnotationToMezzeType(it.type) })
            is ParsedFunctionType -> MezzeType.FunctionType(paramType = typeAnnotationToMezzeType(annotation.paramType), returnType = typeAnnotationToMezzeType(annotation.returnType))
            is ParsedVariantType -> MezzeType.VariantType(constructors = annotation.constructors.associate { it.name to it.dataType?.let { dt -> typeAnnotationToMezzeType(dt) } })
            is ParsedQualifiedType -> WellKnownTypeApp.of((annotation.modulePath + annotation.typeName).joinToString("::"), annotation.args.map { typeAnnotationToMezzeType(it) })
        }
    }

    internal fun checkAbilityImplementation(abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): Boolean {
        val effectiveHash = abilityHash ?: WellKnownHashConstants.resolveHash(abilityName)
        if (typeArgs.size == 1) {
            if (canDeriveAbility(abilityName, typeArgs.first(), effectiveHash)) {
                return true
            }
        }

        if (isCodecAbility(abilityName, abilityHash)) {
            if (typeArgs.size == 2) {
                val dataType = typeArgs[0]
                val formatType = typeArgs[1]
                if (checkCodecAbilityImplementation(abilityName, formatType, dataType)) {
                    return true
                }
            } else if (typeArgs.size == 1 && (effectiveHash == WellKnownHashConstants.ABILITY_ENCODEREPR || effectiveHash == WellKnownHashConstants.ABILITY_DECODEREPR)) {
                val dataType = typeArgs[0]
                if (codecGenerator.isSerializable(dataType) && !containsVarType(dataType)) {
                    val hashBuilder = MezzeHashBuilder()
                    hashBuilder.append("codec_impl")
                    hashBuilder.append(abilityName)
                    hashBuilder.append("_single_param_")
                    hashBuilder.append(ContentHashComputer.mezzeTypeToString(dataType))
                    val implKey = Hash(hashBuilder.build().toFullHexString())
                    if (!generatedCodecImpls.containsKey(implKey)) {
                        generatedCodecImpls[implKey] = GeneratedCodecImpl(
                            abilityName = abilityName,
                            targetTypes = listOf(dataType),
                            contentHash = implKey
                        )
                    }
                    return true
                }
            }
        }

        val impls = if (abilityHash != null) {
            val byHash = context.getAbilityImplsByDeclHash(abilityHash)
            if (byHash.isNotEmpty()) byHash else context.getAbilityImpls(abilityName)
        } else {
            context.getAbilityImpls(abilityName)
        }

        val unwrappedTypeArgs = typeArgs.map(::unwrapSuspended)

        val hasImpl = impls.any { implInfo ->
            val annotations = implInfo.targetTypeAnnotations ?: return@any false

            if (annotations.size != unwrappedTypeArgs.size) return@any false

            val typeVarBindings = mutableMapOf<String, MezzeType>()

            val matches = unwrappedTypeArgs.zip(annotations).all { (actualType, annotation) ->
                TypeAnnotationMatcher.typeMatchesAnnotation(actualType, annotation, registry.typeDeclarations, implInfo.typeVars, typeVarBindings)
            }

            if (!matches) return@any false

            if (typeVarBindings.isNotEmpty() && implInfo.operationConstraints.isNotEmpty()) {
                implInfo.operationConstraints.all { constraint ->
                    val resolvedTypeArgs = constraint.typeArgs.map { typeArg ->
                        when (typeArg) {
                            is ParsedNamedType -> {
                                val bound = typeVarBindings[typeArg.name]
                                if (bound != null) bound
                                else typeAnnotationToMezzeType(typeArg)
                            }
                            else -> typeAnnotationToMezzeType(typeArg)
                        }
                    }
                    checkAbilityImplementation(constraint.abilityName, resolvedTypeArgs, WellKnownHashConstants.resolveHash(constraint.abilityName))
                }
            } else {
                true
            }
        }

        return hasImpl
    }

    private fun isCodecAbility(abilityName: String, abilityHash: Hash? = null): Boolean =
        WellKnownHashConstants.isCodecAbility(abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName))

    private fun checkCodecAbilityImplementation(
        abilityName: String,
        formatType: MezzeType,
        dataType: MezzeType
    ): Boolean {
        if (!codecGenerator.isSerializable(dataType)) {
            return false
        }

        if (containsVarType(formatType) || containsVarType(dataType)) {
            return false
        }

        val hashBuilder = MezzeHashBuilder()
        hashBuilder.append("codec_impl")
        hashBuilder.append(abilityName)
        hashBuilder.append(ContentHashComputer.mezzeTypeToString(formatType))
        hashBuilder.append(ContentHashComputer.mezzeTypeToString(dataType))
        val implKey = Hash(hashBuilder.build().toFullHexString())

        if (generatedCodecImpls.containsKey(implKey)) {
            return true
        }

        generatedCodecImpls[implKey] = GeneratedCodecImpl(
            abilityName = abilityName,
            targetTypes = listOf(formatType, dataType),
            contentHash = implKey
        )
        return true
    }

    internal fun containsVarType(type: MezzeType): Boolean {
        return type is MezzeType.VarType || type.anyChild { containsVarType(it) }
    }

    internal fun closeOpenRecordsForCodec(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.RecordType -> {
                val needsClosing = type.rowVar != null || type.fields.values.any { needsClosingForCodec(it) }
                if (!needsClosing) {
                    return type
                }
                val closedFields = type.fields.mapValues { (_, fieldType) ->
                    closeOpenRecordsForCodec(fieldType)
                }
                MezzeType.RecordType(fields = closedFields, rowVar = null, typeName = type.typeName, typeHash = type.typeHash)
            }
            is MezzeType.TypeApplication -> {
                if (type.args.none { needsClosingForCodec(it) }) {
                    return type
                }
                val closedArgs = type.args.map { closeOpenRecordsForCodec(it) }
                WellKnownTypeApp.of(type.constructor, closedArgs, type.typeHash)
            }
            is MezzeType.VariantType -> {
                val needsClosing = type.rowVar != null || type.constructors.values.any { it != null && needsClosingForCodec(it) }
                if (!needsClosing) {
                    return type
                }
                val closedConstructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { closeOpenRecordsForCodec(it) }
                }
                MezzeType.VariantType(closedConstructors, rowVar = null, typeName = type.typeName, typeHash = type.typeHash)
            }
            is MezzeType.ListType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.ListType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.SeqType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.SeqType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.SetType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.SetType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.SortedSetType -> {
                if (!needsClosingForCodec(type.elementType)) return type
                MezzeType.SortedSetType(closeOpenRecordsForCodec(type.elementType))
            }
            is MezzeType.MapType -> {
                if (!needsClosingForCodec(type.keyType) && !needsClosingForCodec(type.valueType)) return type
                MezzeType.MapType(
                    closeOpenRecordsForCodec(type.keyType),
                    closeOpenRecordsForCodec(type.valueType)
                )
            }
            is MezzeType.SortedMapType -> {
                if (!needsClosingForCodec(type.keyType) && !needsClosingForCodec(type.valueType)) return type
                MezzeType.SortedMapType(
                    closeOpenRecordsForCodec(type.keyType),
                    closeOpenRecordsForCodec(type.valueType)
                )
            }
            is MezzeType.FunctionType -> {
                if (!needsClosingForCodec(type.paramType) && !needsClosingForCodec(type.returnType)) return type
                MezzeType.FunctionType(
                    paramType = closeOpenRecordsForCodec(type.paramType),
                    returnType = closeOpenRecordsForCodec(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.SuspendType -> {
                if (!needsClosingForCodec(type.valueType)) return type
                MezzeType.SuspendType(
                    valueType = closeOpenRecordsForCodec(type.valueType),
                    effects = type.effects
                )
            }
            is MezzeType.VarType -> type
            is MezzeType.EffectRow -> type
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.CharType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType -> type
            is MezzeType.NewtypeType -> type
        }
    }

    private fun needsClosingForCodec(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.RecordType -> type.rowVar != null
            is MezzeType.VariantType -> type.rowVar != null
            else -> false
        } || type.anyChild { needsClosingForCodec(it) }
    }

    private fun canDeriveAbility(abilityName: String, type: MezzeType, abilityHash: Hash? = null): Boolean {
        val resolvedHash = abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)
        var unwrappedType = unwrapSuspended(type)

        var isNewtype = unwrappedType is MezzeType.NewtypeType

        if (unwrappedType is MezzeType.TypeApplication) {
            val typeDecl = registry.lookupType(unwrappedType.constructor)
                ?: ModuleResolver.lookupTypeDeclaration(unwrappedType.constructor)
            if (typeDecl?.definition is ParsedNewtypeDef) {
                isNewtype = true
            }

            val expandedVariant = expandTypeApplicationToVariant(unwrappedType, registry)
            if (expandedVariant != null) {
                unwrappedType = expandedVariant
            } else {
                val expandedRecord = expandTypeApplicationToRecord(unwrappedType, registry)
                if (expandedRecord != null) {
                    unwrappedType = expandedRecord
                }
            }
        }

        if (isNewtype && !WellKnownHashConstants.isInspect(abilityHash)) {
            return false
        }

        return when {
            WellKnownHashConstants.isInspect(abilityHash) -> true

            WellKnownHashConstants.isEq(abilityHash) -> {
                unwrappedType is MezzeType.VarType || BuiltInAbilities.isBoolVariant(unwrappedType) ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash))
            }

            WellKnownHashConstants.isOrd(abilityHash) -> {
                unwrappedType is MezzeType.VarType ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash))
            }

            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALAND ||
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALOR ||
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALNOT -> {
                unwrappedType is MezzeType.BoolType || BuiltInAbilities.isBoolVariant(unwrappedType) ||
                isBoolLikeVariant(unwrappedType)
            }

            else -> false
        }
    }

    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType, abilityHash: Hash? = null): Boolean {
        if (canDeriveAbility(abilityName, type, abilityHash)) return true
        if (abilityHash != null && checkAbilityImplementation(abilityName, listOf(type), abilityHash)) return true
        return checkAbilityImplementation(abilityName, listOf(type))
    }

    internal fun generateAbilityErrorMessage(abilityName: String, typeArgs: List<MezzeType>, loc: Loc? = null): String {
        val hasFunctionType = typeArgs.any { it is MezzeType.FunctionType }
        val typeNames = typeArgs.map { prettyPrintType(it) }.joinToString(", ")
        val primaryType = typeArgs.firstOrNull()?.let { prettyPrintType(it) } ?: typeNames

        val operator = loc?.let { SyntaxMetadata.getOperatorSyntax(it) }

        if (hasFunctionType) {
            return if (operator != null) {
                "Cannot use '$operator' with a function. Functions cannot implement $abilityName."
            } else {
                "Functions cannot implement '$abilityName'."
            }
        }

        if (operator != null) {
            val implExistsForType = abilityImplExistsForType(abilityName, typeArgs)
            return if (implExistsForType) {
                "Cannot use '$operator' with $typeNames. An `impl $abilityName for $primaryType` exists but is not " +
                    "visible at this call site — most likely the operator's stdlib helper isn't imported. " +
                    "Try adding `use std::${stdModuleForType(primaryType)}::{ * }` (or import the specific symbol used)." +
                    formatAbilityCandidates(abilityName)
            } else {
                "Cannot use '$operator' with $typeNames. To enable this, implement: impl $abilityName for $primaryType where { ... }${formatAbilityCandidates(abilityName)}"
            }
        }

        return when (abilityName) {
            "Decode", "DecodeRepr" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    """Cannot infer decode target type. Add a type annotation:
  let result: MyType = decode {val = data, fmt = fmt}"""
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            "Encode", "EncodeRepr" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    "Cannot infer encode source type. Add a type annotation to the value being encoded."
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            "Tryable" -> {
                "Cannot use `<-` on $typeNames — no `Tryable` impl. `<-` works " +
                    "on `Option`, `Result`, or any type implementing `Tryable`. " +
                    "If you meant a plain binding, use `let x = ...` instead."
            }
            else -> "$typeNames does not implement '$abilityName'. To enable this, implement: impl $abilityName for $primaryType where { ... }${formatAbilityCandidates(abilityName)}"
        }
    }

    private fun abilityImplExistsForType(abilityName: String, typeArgs: List<MezzeType>): Boolean {
        val primaryType = typeArgs.firstOrNull() ?: return false
        val primaryName = prettyPrintType(primaryType)
        val impls = context.getAbilityImpls(abilityName)
        return impls.any { info ->
            val targets = info.targetTypeAnnotations
            val rendered = when {
                targets != null && targets.isNotEmpty() -> targets.joinToString(", ") { renderAnnotation(it) }
                info.targetTypes.isNotEmpty() -> info.targetTypes.joinToString(", ")
                else -> return@any false
            }
            rendered == primaryName || rendered.split(' ').firstOrNull() == primaryName.split(' ').firstOrNull()
        }
    }

    private fun stdModuleForType(primaryType: String): String = when {
        primaryType.startsWith("Int") -> "int"
        primaryType.startsWith("Float") -> "float"
        primaryType.startsWith("Nat") -> "nat"
        primaryType.startsWith("Bool") -> "bool"
        primaryType.startsWith("Str") -> "str"
        primaryType.startsWith("Char") -> "char"
        primaryType.startsWith("Frac") -> "frac"
        primaryType.startsWith("List") -> "collections::list"
        primaryType.startsWith("Map") -> "collections::map"
        primaryType.startsWith("Set") -> "collections::set"
        primaryType.startsWith("Seq") -> "collections::seq"
        primaryType.startsWith("Bytes") -> "collections::bytes"
        primaryType.startsWith("Option") -> "option"
        primaryType.startsWith("Result") -> "result"
        else -> "<unknown>"
    }

    private fun formatAbilityCandidates(abilityName: String): String {
        val impls = context.getAbilityImpls(abilityName)
        if (impls.isEmpty()) return ""
        val rendered = impls.mapNotNull { info ->
            val targets = info.targetTypeAnnotations
            when {
                targets != null && targets.isNotEmpty() ->
                    targets.joinToString(", ") { renderAnnotation(it) }
                info.targetTypes.isNotEmpty() ->
                    info.targetTypes.joinToString(", ")
                else -> null
            }
        }.distinct()
        if (rendered.isEmpty()) return ""
        val shown = rendered.take(5)
        val suffix = if (rendered.size > shown.size) ", … (${rendered.size - shown.size} more)" else ""
        return "\n  Available impls: " + shown.joinToString("; ") { "impl $abilityName for $it" } + suffix
    }

    private fun renderAnnotation(a: ParsedTypeAnnotation): String = when (a) {
        is ParsedNamedType -> a.name
        is ParsedQualifiedType -> {
            val prefix = a.modulePath.joinToString("::")
            val head = if (prefix.isEmpty()) a.typeName else "$prefix::${a.typeName}"
            if (a.args.isEmpty()) head else "$head ${a.args.joinToString(" ") { renderAnnotation(it) }}"
        }
        is ParsedTypeApp ->
            if (a.args.isEmpty()) a.constructor else "${a.constructor} ${a.args.joinToString(" ") { renderAnnotation(it) }}"
        is ParsedRecordType -> {
            val fields = a.entries.joinToString(", ") { "${it.name}: ${renderAnnotation(it.type)}" }
            if (a.isOpen) "{ $fields, ... }" else "{ $fields }"
        }
        is ParsedVariantType -> {
            val ctors = a.constructors.joinToString(", ") { c ->
                val dt = c.dataType
                if (dt == null) c.name else "${c.name} ${renderAnnotation(dt)}"
            }
            "| $ctors |"
        }
        is ParsedFunctionType ->
            "${renderAnnotation(a.paramType)} -> ${renderAnnotation(a.returnType)}"
        is ParsedSeqType -> "[${renderAnnotation(a.elementType)}]"
        else -> a::class.simpleName?.removePrefix("Parsed")?.removeSuffix("Type") ?: "?"
    }

    private fun isBoolLikeVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val keys = type.constructors.keys
        return keys.all { it == "True" || it == "False" } &&
               keys.isNotEmpty() &&
               type.constructors.values.all { it == null }
    }

    internal fun isConcrete(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType, is MezzeType.EffectRow -> false
            is MezzeType.RecordType -> type.rowVar == null && !type.anyChild { !isConcrete(it) }
            is MezzeType.VariantType -> type.rowVar == null && !type.anyChild { !isConcrete(it) }
            else -> !type.anyChild { !isConcrete(it) }
        }
    }

    internal fun tryBidirectionalInference(abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): Substitution? {
        val resolvedHash = abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODEREPR && typeArgs.size == 1) {
            val dataType = typeArgs[0]

            if (dataType is MezzeType.VarType) {
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    WellKnownHashConstants.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODEREPR &&
                    impl.targetTypes.isNotEmpty()
                }

                if (matchingImpls.size == 1) {
                    val impl = matchingImpls.first()
                    val inferredType = impl.targetTypes[0]
                    return Substitution.single(dataType.variable, inferredType)
                }
            }
        }
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODE && typeArgs.size == 2) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]

            if (formatType !is MezzeType.VarType && dataType is MezzeType.VarType) {
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    WellKnownHashConstants.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODE &&
                    impl.targetTypes.size >= 2 &&
                    typeMatchesMezzeType(formatType, impl.targetTypes[0])
                }

                if (matchingImpls.size == 1) {
                    val impl = matchingImpls.first()
                    val inferredType = impl.targetTypes[1]
                    return Substitution.single(dataType.variable, inferredType)
                }
            }
        }

        val allImpls = context.getAbilityImpls(abilityName)

        if (allImpls.isEmpty()) {
            return null
        }

        val matchingImpls = mutableListOf<List<ParsedTypeAnnotation>>()

        for (impl in allImpls) {
            val annotations = impl.targetTypeAnnotations
            if (annotations == null || annotations.size != typeArgs.size) {
                continue
            }

            var matches = true
            for (i in typeArgs.indices) {
                val constraintType = typeArgs[i]

                if (constraintType !is MezzeType.VarType) {
                    if (!TypeAnnotationMatcher.typeMatchesAnnotation(constraintType, annotations[i], registry.typeDeclarations)) {
                        matches = false
                        break
                    }
                }
            }

            if (matches) {
                matchingImpls.add(annotations)
            }
        }

        if (matchingImpls.isEmpty()) {
            return null
        }

        if (matchingImpls.size > 1) {
            return null
        }

        val implAnnotations = matchingImpls.first()
        val substitutionMap = mutableMapOf<TypeVar, MezzeType>()

        for (i in typeArgs.indices) {
            val constraintType = typeArgs[i]
            if (constraintType is MezzeType.VarType) {
                val inferredType = convertAnnotationToType(implAnnotations[i])
                if (inferredType != null) {
                    substitutionMap[constraintType.variable] = inferredType
                }
            }
        }

        return if (substitutionMap.isNotEmpty()) {
            Substitution(substitutionMap)
        } else {
            null
        }
    }

    private fun convertAnnotationToType(annotation: ParsedTypeAnnotation): MezzeType? {
        return when (annotation) {
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedTypeApp -> { val args = annotation.args.mapNotNull { convertAnnotationToType(it) }; if (args.size != annotation.args.size) null else WellKnownTypeApp.of(annotation.constructor, args) }
            is ParsedSeqType -> convertAnnotationToType(annotation.elementType)?.let { MezzeType.SeqType(it) }
            is ParsedRecordType -> { val fields = mutableMapOf<String, MezzeType>(); for (e in annotation.entries) { val ft = convertAnnotationToType(e.type) ?: return null; fields[e.name] = ft }; MezzeType.RecordType(fields) }
            is ParsedVariantType -> MezzeType.VariantType(annotation.constructors.associate { it.name to it.dataType?.let { dt -> convertAnnotationToType(dt) } })
            is ParsedFunctionType -> { val p = convertAnnotationToType(annotation.paramType) ?: return null; val r = convertAnnotationToType(annotation.returnType) ?: return null; MezzeType.FunctionType(p, r) }
            is ParsedQualifiedType -> null
        }
    }

    private fun typeMatchesMezzeType(expected: MezzeType, actual: MezzeType): Boolean {
        if (expected == actual) return true
        if (expected is MezzeType.TypeApplication && actual is MezzeType.TypeApplication) {
            return expected.constructor == actual.constructor
        }
        return false
    }
}
