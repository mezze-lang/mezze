package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.Hash

class AbilityResolver(
    private val context: TypingContext,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY
) {
    private val typeDeclarations: Map<String, ParsedTypeDecl> get() = registry.typeDeclarations
    private val codecGenerator = CodecGenerator()

    private val typeNameCache = mutableMapOf<Int, String>()

    private val inProgressChecks = mutableSetOf<Pair<String, String>>()

    sealed class AbilityCheckResult {
        data class Satisfied(
            val implSource: String? = null
        ) : AbilityCheckResult()

        data class Unsatisfied(
            val reason: String,
            val suggestions: List<String> = emptyList()
        ) : AbilityCheckResult()
    }

    fun checkAbility(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType> = emptyList(),
        abilityHash: Hash? = null
    ): AbilityCheckResult {
        val typeName = getTypeNameForRecursionCheck(type)
        if (typeName != null) {
            val checkKey = Pair(typeName, abilityName)
            if (checkKey in inProgressChecks) {
                return AbilityCheckResult.Satisfied("Recursive type (coinductive)")
            }
            inProgressChecks.add(checkKey)
            try {
                return checkAbilityImpl(type, abilityName, typeArgs, abilityHash)
            } finally {
                inProgressChecks.remove(checkKey)
            }
        }

        return checkAbilityImpl(type, abilityName, typeArgs, abilityHash)
    }

    private fun getTypeNameForRecursionCheck(type: MezzeType): String? {
        return when (type) {
            is MezzeType.RecordType -> type.identity.displayNameOrNull()
            is MezzeType.VariantType -> type.identity.displayNameOrNull()
            is MezzeType.TypeApplication -> type.constructor
            else -> null
        }
    }

    private fun checkAbilityImpl(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType>,
        abilityHash: Hash? = null
    ): AbilityCheckResult {

        return when (type) {
            is MezzeType.VarType -> AbilityCheckResult.Satisfied(
                implSource = "Type variable (deferred)"
            )

            is MezzeType.SuspendType -> checkAbility(type.valueType, abilityName, typeArgs, abilityHash)

            is MezzeType.FunctionType -> AbilityCheckResult.Unsatisfied(
                reason = "Function types do not support abilities"
            )

            is MezzeType.EffectRow -> AbilityCheckResult.Unsatisfied(
                reason = "Effect rows do not support abilities"
            )

            is MezzeType.RecordType -> checkRecordAbility(type, abilityName, typeArgs, abilityHash)
            is MezzeType.VariantType -> checkVariantAbility(type, abilityName, typeArgs, abilityHash)
            is MezzeType.TypeApplication -> checkTypeApplicationAbility(type, abilityName, typeArgs, abilityHash)

            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkRecordAbility(type: MezzeType.RecordType, abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): AbilityCheckResult {
        return when {
            WellKnownHashConstants.isEq(abilityHash) -> {
                for ((fieldName, fieldType) in type.fields) {
                    val fieldCheck = checkAbility(fieldType, abilityName, emptyList(), abilityHash)
                    if (fieldCheck is AbilityCheckResult.Unsatisfied) {
                        return AbilityCheckResult.Unsatisfied(
                            reason = "Record Eq requires all fields to have Eq (field '$fieldName' does not)"
                        )
                    }
                }
                AbilityCheckResult.Satisfied("Derived from all fields")
            }
            WellKnownHashConstants.isInspect(abilityHash) -> {
                for ((fieldName, fieldType) in type.fields) {
                    val fieldCheck = checkAbility(fieldType, abilityName, emptyList(), abilityHash)
                    if (fieldCheck is AbilityCheckResult.Unsatisfied) {
                        return AbilityCheckResult.Unsatisfied(
                            reason = "Record Inspect requires all fields to have Inspect (field '$fieldName' does not)"
                        )
                    }
                }
                AbilityCheckResult.Satisfied("Built-in Record to_string (derived from all fields)")
            }
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkVariantAbility(type: MezzeType.VariantType, abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): AbilityCheckResult {
        val isBool = type.constructors.size == 2 &&
            type.constructors.containsKey(BuiltinTypes.TRUE) && type.constructors.containsKey(BuiltinTypes.FALSE) &&
            type.constructors[BuiltinTypes.TRUE] == null && type.constructors[BuiltinTypes.FALSE] == null
        if (isBool) {
            return checkUserDefinedAbilityImpl(MezzeType.BoolType, abilityName, typeArgs)
        }

        val typeName = type.identity.displayNameOrNull()
        val typeDecl = if (typeName != null) typeDeclarations[typeName] else null
        val isNewtype = typeDecl?.definition is ParsedNewtypeDef

        if (isNewtype) {
            return checkNewtypeAbility(type, abilityName, typeArgs, typeDecl!!.definition as ParsedNewtypeDef, abilityHash)
        }

        return when {
            WellKnownHashConstants.isEq(abilityHash) -> {
                for ((constructorName, dataType) in type.constructors) {
                    if (dataType != null) {
                        val dataCheck = checkAbility(dataType, abilityName, emptyList(), abilityHash)
                        if (dataCheck is AbilityCheckResult.Unsatisfied) {
                            return AbilityCheckResult.Unsatisfied(
                                reason = "Variant Eq requires all constructor data to have Eq (constructor '$constructorName' does not)"
                            )
                        }
                    }
                }
                AbilityCheckResult.Satisfied("Derived from all constructors")
            }
            WellKnownHashConstants.isInspect(abilityHash) -> {
                for ((constructorName, dataType) in type.constructors) {
                    if (dataType != null) {
                        val dataCheck = checkAbility(dataType, abilityName, emptyList(), abilityHash)
                        if (dataCheck is AbilityCheckResult.Unsatisfied) {
                            return AbilityCheckResult.Unsatisfied(
                                reason = "Variant Inspect requires all constructor data to have Inspect (constructor '$constructorName' does not)"
                            )
                        }
                    }
                }
                AbilityCheckResult.Satisfied("Built-in Variant to_string (derived from all constructors)")
            }
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkNewtypeAbility(
        type: MezzeType.VariantType,
        abilityName: String,
        typeArgs: List<MezzeType>,
        definition: ParsedNewtypeDef,
        abilityHash: Hash? = null
    ): AbilityCheckResult {
        val typeName = type.identity.displayNameOrNull() ?: return checkUserDefinedAbilityImpl(type, abilityName, typeArgs)

        val wrappedType = type.constructors.values.firstOrNull()

        return when {
            WellKnownHashConstants.isInspect(abilityHash) -> {
                AbilityCheckResult.Satisfied("Built-in Newtype Inspect (universal)")
            }
            else -> {
                checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
            }
        }
    }

    private fun checkNewtypeAbilityForTypeApplication(
        type: MezzeType.TypeApplication,
        abilityName: String,
        typeArgs: List<MezzeType>,
        definition: ParsedNewtypeDef,
        abilityHash: Hash? = null
    ): AbilityCheckResult {
        return when {
            WellKnownHashConstants.isInspect(abilityHash) -> {
                AbilityCheckResult.Satisfied("Built-in Newtype Inspect (universal)")
            }
            else -> {
                checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
            }
        }
    }

    private fun checkTypeApplicationAbility(
        type: MezzeType.TypeApplication,
        abilityName: String,
        typeArgs: List<MezzeType>,
        abilityHash: Hash? = null
    ): AbilityCheckResult {
        when (type.constructor) {
            BuiltinTypes.OPTION, BuiltinTypes.RESULT -> {
                return when {
                    WellKnownHashConstants.isEq(abilityHash) -> {
                        val allArgsHaveEq = type.args.all { arg ->
                            checkAbility(arg, abilityName, emptyList(), abilityHash) is AbilityCheckResult.Satisfied
                        }
                        if (allArgsHaveEq) {
                            AbilityCheckResult.Satisfied("Built-in ${type.constructor} equality")
                        } else {
                            AbilityCheckResult.Unsatisfied(
                                reason = "${type.constructor} Eq requires type arguments to have Eq"
                            )
                        }
                    }
                    WellKnownHashConstants.isInspect(abilityHash) -> {
                        val allArgsHaveInspect = type.args.all { arg ->
                            checkAbility(arg, abilityName, emptyList(), abilityHash) is AbilityCheckResult.Satisfied
                        }
                        if (allArgsHaveInspect) {
                            AbilityCheckResult.Satisfied("Built-in ${type.constructor} to_string")
                        } else {
                            AbilityCheckResult.Unsatisfied(
                                reason = "${type.constructor} Inspect requires type arguments to have Inspect"
                            )
                        }
                    }
                    else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
                }
            }
        }

        val typeDecl = typeDeclarations[type.constructor]
        if (typeDecl != null) {
            if (typeDecl.definition is ParsedNewtypeDef) {
                return checkNewtypeAbilityForTypeApplication(
                    type, abilityName, typeArgs, typeDecl.definition as ParsedNewtypeDef, abilityHash
                )
            }

            if (WellKnownHashConstants.isEq(abilityHash) || WellKnownHashConstants.isInspect(abilityHash)) {
                val allArgsSupport = type.args.all { arg ->
                    checkAbility(arg, abilityName, emptyList(), abilityHash) is AbilityCheckResult.Satisfied
                }
                if (!allArgsSupport) {
                    return AbilityCheckResult.Unsatisfied(
                        reason = "${type.constructor} $abilityName requires type arguments to have $abilityName"
                    )
                }

                return when (val definition = typeDecl.definition) {
                    is ParsedVariantDef -> {
                        for (constructor in definition.constructors) {
                            val dataTypeAnnotation = constructor.dataType ?: continue
                            if (isSelfReference(dataTypeAnnotation, type.constructor)) continue
                        }
                        AbilityCheckResult.Satisfied("Derived from type declaration ${type.constructor}")
                    }
                    is ParsedRecordDef -> {
                        AbilityCheckResult.Satisfied("Derived from type declaration ${type.constructor}")
                    }
                    is ParsedNewtypeDef -> {
                        checkNewtypeAbilityForTypeApplication(type, abilityName, typeArgs, definition, abilityHash)
                    }
                    is ParsedTypeAliasDef -> {
                        // Transparent aliases should have been inlined by the
                        // converter; if we see one here it means the label
                        // survived. Delegate to the aliased type's ability
                        // check to keep transparency intact.
                        AbilityCheckResult.Unsatisfied(
                            reason = "Alias ${type.constructor} was not inlined before ability check"
                        )
                    }
                }
            }
        }

        return checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
    }

    private fun isSelfReference(annotation: ParsedTypeAnnotation, typeName: String): Boolean {
        return when (annotation) {
            is ParsedNamedType -> annotation.name == typeName
            is ParsedTypeApp -> annotation.constructor == typeName
            is ParsedRecordType -> annotation.entries.any { isSelfReference(it.type, typeName) }
            else -> false
        }
    }

    private fun getTypeName(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeNameCache[key]?.let { return it }

        val result = when (type) {
            is MezzeType.RecordType -> {
                type.identity.displayNameOrNull() ?: ContentHashComputer.mezzeTypeToString(type)
            }
            is MezzeType.VariantType -> {
                type.identity.displayNameOrNull() ?: if (BuiltInAbilities.isBoolVariant(type)) {
                    "Bool"
                } else {
                    ContentHashComputer.mezzeTypeToString(type)
                }
            }
            is MezzeType.SuspendType -> getTypeName(type.valueType)
            else -> ContentHashComputer.mezzeTypeToString(type)
        }

        typeNameCache[key] = result
        return result
    }

    private fun checkUserDefinedAbilityImpl(
        type: MezzeType,
        abilityName: String,
        additionalTypeArgs: List<MezzeType> = emptyList()
    ): AbilityCheckResult {
        val typeName = getTypeName(type)

        val allImpls = context.getAbilityImpls(abilityName)
        val matchingImpl = allImpls.find { impl ->
            val annotations = impl.targetTypeAnnotations ?: return@find false

            val associatedBindings = impl.associatedTypeBindings

            // The full type tuple to match against the impl's annotations is the
            // primary `type` followed by the extra ability type args. Pick by the
            // impl's ANNOTATION ARITY, not by type-equality: a previous heuristic
            // dropped `type` whenever some arg shared its type (e.g. `Int` scaled
            // by `Int`), collapsing a 2-arg tuple to 1 and failing to match a
            // multi-param `impl Scale Int for Int`. Arity-directed selection is
            // immune to that coincidence.
            val withPrimary = listOf(type) + additionalTypeArgs
            val allTypes = when {
                additionalTypeArgs.isEmpty() -> listOf(type)
                // args alone already fill the annotations (some caller passed the
                // complete tuple, e.g. codec paths) — use them as-is.
                additionalTypeArgs.size == annotations.size -> additionalTypeArgs
                else -> withPrimary
            }

            val typeVarBindings = mutableMapOf<String, MezzeType>()

            // Target-type matching is the same whether or not the impl binds an
            // associated type (`having 'out = ...`): the associated binding only
            // adds OUTPUT info, it doesn't change which (receiver, arg…) tuple the
            // impl applies to. A previous shortcut forced `annotations.size == 1`
            // in the associated-binding case, which rejected every multi-param
            // impl with a `having` clause (e.g. `impl Scale Int for Int having 'out`).
            val structuralMatch =
                annotations.size == allTypes.size &&
                allTypes.zip(annotations).all { (actualType, annotation) ->
                    TypeAnnotationMatcher.typeMatchesAnnotation(actualType, annotation, typeDeclarations, impl.typeVars, typeVarBindings)
                }

            if (!structuralMatch) return@find false

            if (typeVarBindings.isNotEmpty() && impl.operationConstraints.isNotEmpty()) {
                impl.operationConstraints.all { constraint ->
                    val resolvedTypeArgs = constraint.typeArgs.map { typeArg ->
                        when (typeArg) {
                            is ParsedNamedType -> {
                                val bound = typeVarBindings[typeArg.name]
                                if (bound != null) bound
                                else resolveTypeAnnotation(typeArg)
                            }
                            else -> resolveTypeAnnotation(typeArg)
                        }
                    }
                    val result = checkAbilityImpl(resolvedTypeArgs.first(), constraint.abilityName, resolvedTypeArgs.drop(1))
                    result is AbilityCheckResult.Satisfied
                }
            } else {
                true
            }
        }

        if (matchingImpl != null) {
            val implRef = matchingImpl.contentHash ?: "${matchingImpl.abilityName} ${matchingImpl.targetTypes}"
            return AbilityCheckResult.Satisfied("User-defined impl: $implRef")
        }

        val resolvedHash = registry.resolveAbilityHash(abilityName)
        if (WellKnownHashConstants.isCodecAbility(resolvedHash) && codecGenerator.isSerializable(type)) {
            return AbilityCheckResult.Satisfied("Auto-derived codec impl")
        }

        return AbilityCheckResult.Unsatisfied(
            reason = "No implementation of ability '$abilityName' for type '$typeName'",
            suggestions = listOf(
                "Add an implementation: impl $abilityName $typeName { ... }"
            )
        )
    }

    private fun resolveTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedSeqType -> MezzeType.SeqType(resolveTypeAnnotation(annotation.elementType))
            else -> WellKnownTypeApp.of(annotation.toString(), emptyList())
        }
    }

}
