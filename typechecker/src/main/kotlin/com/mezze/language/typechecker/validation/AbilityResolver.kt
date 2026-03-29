package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.ast.PrimitiveTypes

/**
 * Ability resolver - checks that ability constraints are satisfied.
 *
 * Abilities (type classes) require that types implement specific operations.
 * Example: NumAdd ability requires + operation
 *
 * Built-in abilities (from BuiltInAbilities.kt):
 * - NumAdd, NumSub, NumMul, NumDiv, NumMod (arithmetic)
 * - Eq (equality: eq, neq)
 * - Ord (ordering: lt, le, gt, ge)
 * - Inspect (to_str conversion)
 * - LogicalAnd, LogicalOr, LogicalNot (boolean operations)
 *
 * The resolver checks:
 * 1. Does the type have an impl for this ability?
 * 2. Are all ability dependencies satisfied?
 * 3. Are ability constraints from functions satisfied?
 */
class AbilityResolver(
    private val context: TypingContext,
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap()
) {
    private val codecGenerator = CodecGenerator()

    // Cache for getTypeName() - avoids repeated ContentHashComputer calls
    // Keyed by identity hash to avoid expensive MezzeType.equals()
    private val typeNameCache = mutableMapOf<Int, String>()

    // Track types currently being checked to handle recursive types.
    // Key is (typeName, abilityName) - if we encounter the same check recursively,
    // we assume the ability is satisfied (coinductive reasoning for recursive types).
    private val inProgressChecks = mutableSetOf<Pair<String, String>>()

    /**
     * Result of ability resolution.
     */
    sealed class AbilityCheckResult {
        data class Satisfied(
            val implSource: String? = null
        ) : AbilityCheckResult()

        data class Unsatisfied(
            val reason: String,
            val suggestions: List<String> = emptyList()
        ) : AbilityCheckResult()
    }

    /**
     * Check if a type satisfies an ability.
     *
     * @param abilityHash Content hash of the ability declaration — used for hash-only
     *        identification of well-known abilities (Inspect, Eq, etc.). Callers should
     *        provide this when available (e.g., from Constraint.AbilityRequired.abilityHash).
     */
    fun checkAbility(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType> = emptyList(),
        abilityHash: String? = null
    ): AbilityCheckResult {
        // Handle recursive type checking - if we're already checking this type for this ability,
        // assume it's satisfied to break the recursion (coinductive approach)
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

    /**
     * Get a stable name for a type for recursion checking.
     * Returns null for types that can't be recursive (primitives, etc.)
     */
    private fun getTypeNameForRecursionCheck(type: MezzeType): String? {
        return when (type) {
            is MezzeType.RecordType -> type.typeName
            is MezzeType.VariantType -> type.typeName
            is MezzeType.TypeApplication -> type.constructor
            else -> null
        }
    }

    /**
     * Internal implementation of ability checking.
     */
    private fun checkAbilityImpl(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType>,
        abilityHash: String? = null
    ): AbilityCheckResult {
        // NOTE: Built-in abilities (Eq, NumAdd, Ord, etc.) are validated by the
        // ConstraintSolver in Phase 3 and skipped by TypeValidator in Phase 5.
        // This resolver only handles user-defined abilities and codec abilities.

        return when (type) {
            // Type variables can't be checked yet (deferred to instantiation)
            is MezzeType.VarType -> AbilityCheckResult.Satisfied(
                implSource = "Type variable (deferred)"
            )

            // Suspended types delegate to their wrapped type
            is MezzeType.SuspendedType -> checkAbility(type.valueType, abilityName, typeArgs, abilityHash)

            // Function types don't have abilities
            is MezzeType.FunctionType -> AbilityCheckResult.Unsatisfied(
                reason = "Function types do not support abilities"
            )

            // Effect rows don't have abilities
            is MezzeType.EffectRow -> AbilityCheckResult.Unsatisfied(
                reason = "Effect rows do not support abilities"
            )

            // Composite types: check for user-defined ability impls
            is MezzeType.RecordType -> checkRecordAbility(type, abilityName, typeArgs, abilityHash)
            is MezzeType.VariantType -> checkVariantAbility(type, abilityName, typeArgs, abilityHash)
            is MezzeType.TypeApplication -> checkTypeApplicationAbility(type, abilityName, typeArgs, abilityHash)

            // All other types: look up user-defined impl in context
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkRecordAbility(type: MezzeType.RecordType, abilityName: String, typeArgs: List<MezzeType>, abilityHash: String? = null): AbilityCheckResult {
        return when {
            WellKnownHashes.isEq(abilityHash) -> {
                // Records can have Eq if all fields have Eq
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
            WellKnownHashes.isInspect(abilityHash) -> {
                // Records can have Inspect if all fields have Inspect
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

    private fun checkVariantAbility(type: MezzeType.VariantType, abilityName: String, typeArgs: List<MezzeType>, abilityHash: String? = null): AbilityCheckResult {
        // Bool variant — delegate to user-defined ability impl lookup
        val isBool = type.constructors.size == 2 &&
            type.constructors.containsKey("True") && type.constructors.containsKey("False") &&
            type.constructors["True"] == null && type.constructors["False"] == null
        if (isBool) {
            return checkUserDefinedAbilityImpl(MezzeType.BoolType, abilityName, typeArgs)
        }

        // Check if this is a newtype by looking up the type declaration
        val typeName = type.typeName
        val typeDecl = if (typeName != null) typeDeclarations[typeName] else null
        val isNewtype = typeDecl?.definition is NewtypeDefinition

        if (isNewtype) {
            return checkNewtypeAbility(type, abilityName, typeArgs, typeDecl!!.definition as NewtypeDefinition, abilityHash)
        }

        // Regular variant handling
        return when {
            WellKnownHashes.isEq(abilityHash) -> {
                // Variants can have Eq if all constructors have Eq
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
            WellKnownHashes.isInspect(abilityHash) -> {
                // Variants can have Inspect if all constructor data types have Inspect
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

    /**
     * Check ability for newtype.
     *
     * Newtypes have special rules:
     * - Inspect: Always auto-derived (universal debugging)
     * - Eq, Ord, etc.: Only available if explicitly listed in `deriving` clause
     */
    private fun checkNewtypeAbility(
        type: MezzeType.VariantType,
        abilityName: String,
        typeArgs: List<MezzeType>,
        definition: NewtypeDefinition,
        abilityHash: String? = null
    ): AbilityCheckResult {
        val typeName = type.typeName ?: return checkUserDefinedAbilityImpl(type, abilityName, typeArgs)

        // Get the wrapped type (payload of the single constructor)
        val wrappedType = type.constructors.values.firstOrNull()

        return when {
            WellKnownHashes.isInspect(abilityHash) -> {
                // Inspect is always available for newtypes (universal debugging)
                AbilityCheckResult.Satisfied("Built-in Newtype Inspect (universal)")
            }
            else -> {
                // All other abilities require explicit impl.
                // Newtypes do NOT auto-derive — users write `impl Eq for UserId where { ... }`
                checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
            }
        }
    }

    /**
     * Check ability for newtype when accessed via TypeApplication.
     *
     * This is called when the type is represented as TypeApplication("UserId", [])
     * rather than VariantType (which happens during constraint generation).
     */
    private fun checkNewtypeAbilityForTypeApplication(
        type: MezzeType.TypeApplication,
        abilityName: String,
        typeArgs: List<MezzeType>,
        definition: NewtypeDefinition,
        abilityHash: String? = null
    ): AbilityCheckResult {
        return when {
            WellKnownHashes.isInspect(abilityHash) -> {
                AbilityCheckResult.Satisfied("Built-in Newtype Inspect (universal)")
            }
            else -> {
                // Newtypes require explicit impl for all non-Inspect abilities
                checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
            }
        }
    }

    private fun checkTypeApplicationAbility(
        type: MezzeType.TypeApplication,
        abilityName: String,
        typeArgs: List<MezzeType>,
        abilityHash: String? = null
    ): AbilityCheckResult {
        // Handle well-known built-in types
        when (type.constructor) {
            BuiltinTypes.OPTION, BuiltinTypes.RESULT -> {
                return when {
                    WellKnownHashes.isEq(abilityHash) -> {
                        // Option/Result have Eq if their type args have Eq
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
                    WellKnownHashes.isInspect(abilityHash) -> {
                        // Option/Result have Inspect if their type args have Inspect
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

        // For user-defined type applications, check if the underlying type declaration
        // supports the ability. This handles recursive types like Tree 'a.
        val typeDecl = typeDeclarations[type.constructor]
        if (typeDecl != null) {
            // Check if this is a newtype - newtypes have special deriving rules
            if (typeDecl.definition is NewtypeDefinition) {
                return checkNewtypeAbilityForTypeApplication(
                    type, abilityName, typeArgs, typeDecl.definition as NewtypeDefinition, abilityHash
                )
            }

            // For regular variants and records, check built-in abilities
            if (WellKnownHashes.isEq(abilityHash) || WellKnownHashes.isInspect(abilityHash)) {
                // First check if type arguments support the ability
                val allArgsSupport = type.args.all { arg ->
                    checkAbility(arg, abilityName, emptyList(), abilityHash) is AbilityCheckResult.Satisfied
                }
                if (!allArgsSupport) {
                    return AbilityCheckResult.Unsatisfied(
                        reason = "${type.constructor} $abilityName requires type arguments to have $abilityName"
                    )
                }

                // Check the underlying type structure
                return when (val definition = typeDecl.definition) {
                    is VariantDefinition -> {
                        // For variant types, check all constructor data types
                        // But skip recursive references (handled by inProgressChecks)
                        for (constructor in definition.constructors) {
                            val dataTypeAnnotation = constructor.dataType ?: continue
                            // Check if this is a self-reference - if so, skip
                            if (isSelfReference(dataTypeAnnotation, type.constructor)) continue

                            // For non-self-referential parts, we need to check the concrete type
                            // This is already handled by the recursive check with inProgressChecks
                        }
                        AbilityCheckResult.Satisfied("Derived from type declaration ${type.constructor}")
                    }
                    is RecordDefinition -> {
                        AbilityCheckResult.Satisfied("Derived from type declaration ${type.constructor}")
                    }
                    is NewtypeDefinition -> {
                        // Already handled above, but needed for exhaustive when
                        checkNewtypeAbilityForTypeApplication(type, abilityName, typeArgs, definition, abilityHash)
                    }
                }
            }
        }

        return checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
    }

    /**
     * Check if a type annotation references the given type name (self-reference).
     */
    private fun isSelfReference(annotation: TypeAnnotation, typeName: String): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name == typeName
            is TypeApplicationAnnotation -> annotation.constructor == typeName
            is RecordTypeAnnotation -> annotation.entries.any { isSelfReference(it.type, typeName) }
            else -> false
        }
    }

    // ========================================
    // Helper Functions
    // ========================================

    /**
     * Get the type name for ability implementation lookup.
     *
     * Uses special handling for named types (typeName) and Bool variants,
     * then falls back to the canonical ContentHashComputer.mezzeTypeToString.
     *
     * Results are cached by identity hash to avoid repeated computation.
     */
    private fun getTypeName(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeNameCache[key]?.let { return it }

        val result = when (type) {
            is MezzeType.RecordType -> {
                // Use typeName if available (e.g., "Person" for `type Person = { ... }`)
                type.typeName ?: ContentHashComputer.mezzeTypeToString(type)
            }
            is MezzeType.VariantType -> {
                // Use typeName if available (e.g., "Person" for `type Person = | ... |`)
                // Otherwise fall back to canonical naming
                type.typeName ?: if (BuiltInAbilities.isBoolVariant(type)) {
                    "Bool"
                } else {
                    ContentHashComputer.mezzeTypeToString(type)
                }
            }
            is MezzeType.SuspendedType -> getTypeName(type.valueType)
            // For all other types, delegate to canonical implementation
            else -> ContentHashComputer.mezzeTypeToString(type)
        }

        typeNameCache[key] = result
        return result
    }

    private fun formatType(type: MezzeType): String {
        // Use centralized registry for primitive types
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> type.variable.toString()
            else -> type.toString()
        }
    }

    private fun checkUserDefinedAbilityImpl(
        type: MezzeType,
        abilityName: String,
        additionalTypeArgs: List<MezzeType> = emptyList()
    ): AbilityCheckResult {
        val typeName = getTypeName(type)

        // Search for implementation using structural type matching
        val allImpls = context.getAbilityImpls(abilityName)
        val matchingImpl = allImpls.find { impl ->
            // Use structural matching via targetTypeAnnotations
            val annotations = impl.targetTypeAnnotations ?: return@find false

            // Match primary type params against targetTypeAnnotations
            // Match associated types against associatedTypeBindings
            val associatedBindings = impl.associatedTypeBindings

            // IMPORTANT: additionalTypeArgs may contain the primary type as first element
            // when the constraint comes from a type annotation like "with Show 'a".
            // In that case, we should use additionalTypeArgs directly without prepending.
            // For "Show 'a" with 'a=Int: type=Int, additionalTypeArgs=[Int] -> allTypes=[Int]
            // For "Encode Json 'a" with 'a=Record: type=Record, additionalTypeArgs=[Json,Record] -> allTypes=[Json,Record]
            val allTypes = if (additionalTypeArgs.isNotEmpty() && additionalTypeArgs.any { it == type }) {
                // additionalTypeArgs already contains the primary type, use as-is
                additionalTypeArgs
            } else if (additionalTypeArgs.isEmpty()) {
                listOf(type)
            } else {
                // Prepend primary type for method call constraints
                listOf(type) + additionalTypeArgs
            }

            // For generic impls, track type variable bindings during matching
            val typeVarBindings = mutableMapOf<String, MezzeType>()

            // Case 1: Simple matching with computed allTypes
            val structuralMatch = if (associatedBindings.isEmpty()) {
                annotations.size == allTypes.size &&
                allTypes.zip(annotations).all { (actualType, annotation) ->
                    typeMatchesAnnotation(actualType, annotation, impl.typeVars, typeVarBindings)
                }
            }
            // Case 2: Ability with associated types (e.g., Container 'c having 'item)
            else {
                // For abilities with associated types, only match the primary type params.
                // Associated types are determined by the impl itself, not by unification.
                // The additionalTypeArgs may contain unresolved VarTypes for associated types.
                annotations.size == 1 && typeMatchesAnnotation(type, annotations.first(), impl.typeVars, typeVarBindings)
            }

            if (!structuralMatch) return@find false

            // If this is a generic impl, check method-level constraints with resolved bindings
            if (typeVarBindings.isNotEmpty() && impl.operationConstraints.isNotEmpty()) {
                impl.operationConstraints.all { constraint ->
                    val resolvedTypeArgs = constraint.typeArgs.map { typeArg ->
                        when (typeArg) {
                            is IdentifierTypeAnnotation -> {
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

        // Check for auto-derivable Encode/Decode/EncodeRepr/DecodeRepr abilities
        // These are automatically generated by CodecGenerator for serializable types
        if ((abilityName == "Encode" || abilityName == "Decode" ||
             abilityName == "EncodeRepr" || abilityName == "DecodeRepr") &&
            codecGenerator.isSerializable(type)) {
            // Check if there's a Format/DecodeFormat impl in scope
            val formatAbility = if (abilityName == "Encode" || abilityName == "EncodeRepr") "Format" else "DecodeFormat"
            val formatImpls = context.getAbilityImpls(formatAbility)
            if (formatImpls.isNotEmpty()) {
                // This will be auto-derived by SubstitutionApplier/CodecGenerator
                return AbilityCheckResult.Satisfied("Auto-derived from $formatAbility impl")
            }
        }

        return AbilityCheckResult.Unsatisfied(
            reason = "No implementation of ability '$abilityName' for type '$typeName'",
            suggestions = listOf(
                "Add an implementation: impl $abilityName $typeName { ... }"
            )
        )
    }

    /**
     * Infer type arguments from a variant's concrete payload types by matching against
     * the type declaration's constructor patterns.
     */
    private fun inferTypeArgsFromVariant(
        variantType: MezzeType.VariantType,
        typeDecl: TypeDeclaration,
        definition: VariantDefinition
    ): List<MezzeType>? {
        // Map type parameter names to their inferred types
        val typeArgMap = mutableMapOf<String, MezzeType>()

        // For each constructor in the variant, try to match against the type declaration
        for ((constructorName, concretePayload) in variantType.constructors) {
            // Find the matching constructor in the type declaration
            val declConstructor = definition.constructors.find { it.name == constructorName } ?: continue

            // If the constructor has a data type annotation, try to extract type parameter mappings
            val dataTypeAnnotation = declConstructor.dataType ?: continue
            if (concretePayload == null) continue

            // Extract type parameter mappings by comparing the annotation with the concrete type
            extractTypeParamMappings(dataTypeAnnotation, concretePayload, typeDecl.typeParams, typeArgMap)
        }

        // Return the type arguments in order
        return if (typeArgMap.size == typeDecl.typeParams.size) {
            typeDecl.typeParams.map { typeArgMap[it] ?: return null }
        } else {
            null
        }
    }

    /**
     * Extract type parameter mappings by comparing a type annotation with a concrete type.
     */
    private fun extractTypeParamMappings(
        annotation: TypeAnnotation,
        concreteType: MezzeType,
        typeParams: List<String>,
        mappings: MutableMap<String, MezzeType>
    ) {
        when (annotation) {
            is IdentifierTypeAnnotation -> {
                // If this is a type parameter reference like 'a
                if (annotation.name in typeParams) {
                    // Map this type parameter to the concrete type
                    mappings[annotation.name] = concreteType
                }
            }
            is RecordTypeAnnotation -> {
                // Match record fields
                if (concreteType is MezzeType.RecordType) {
                    for (entry in annotation.entries) {
                        val fieldType = concreteType.fields[entry.name] ?: continue
                        extractTypeParamMappings(entry.type, fieldType, typeParams, mappings)
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                // Match type applications like List 'a
                if (concreteType is MezzeType.TypeApplication && concreteType.constructor == annotation.constructor) {
                    for ((annArg, concreteArg) in annotation.args.zip(concreteType.args)) {
                        extractTypeParamMappings(annArg, concreteArg, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.SeqType && annotation.constructor == BuiltinTypes.SEQ) {
                    // Handle Seq type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.ListType && annotation.constructor == BuiltinTypes.LIST) {
                    // Handle List type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                }
            }
            is VariantTypeAnnotation -> {
                // Match variant constructors
                if (concreteType is MezzeType.VariantType) {
                    for (constructor in annotation.constructors) {
                        val concretePayload = concreteType.constructors[constructor.name] ?: continue
                        val annPayload = constructor.dataType ?: continue
                        extractTypeParamMappings(annPayload, concretePayload, typeParams, mappings)
                    }
                }
            }
            else -> {
                // Other annotation types don't contain type parameters we need to extract
            }
        }
    }

    /**
     * Check if a concrete MezzeType matches a TypeAnnotation.
     *
     * This enables structural matching for ability implementations.
     */
    /**
     * Convert a simple TypeAnnotation to MezzeType (for constraint checking on generic impls).
     */
    private fun resolveTypeAnnotation(annotation: TypeAnnotation): MezzeType {
        if (annotation is IdentifierTypeAnnotation) {
            PrimitiveTypes.fromName(annotation.name)?.let { return it }
            return MezzeType.TypeApplication(annotation.name, emptyList())
        }
        if (annotation is SeqTypeAnnotation) {
            return MezzeType.SeqType(resolveTypeAnnotation(annotation.elementType))
        }
        return MezzeType.TypeApplication(annotation.toString(), emptyList())
    }

    private fun typeMatchesAnnotation(
        type: MezzeType,
        annotation: TypeAnnotation,
        typeVars: Set<String> = emptySet(),
        typeVarBindings: MutableMap<String, MezzeType> = mutableMapOf()
    ): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check if this is a type variable from a generic impl
                if (annotation.name in typeVars) {
                    val existing = typeVarBindings[annotation.name]
                    if (existing != null) {
                        existing == type
                    } else {
                        typeVarBindings[annotation.name] = type
                        true
                    }
                }
                else {
                    // Check if annotation refers to a built-in type (primitives + Ordering)
                    val builtinMatch = PrimitiveTypes.matchesBuiltinType(annotation.name, type)
                    if (builtinMatch != null) {
                        builtinMatch
                    } else {
                        val typeDecl = typeDeclarations[annotation.name]
                        if (typeDecl != null) {
                            typeMatchesDeclaration(type, typeDecl)
                        } else {
                            // Hash-first comparison: check typeHash against declaration's contentHash if available
                            val declHash = typeDeclarations[annotation.name]?.contentHash
                            when (type) {
                                is MezzeType.RecordType -> {
                                    if (type.typeHash != null && declHash != null) type.typeHash == declHash
                                    else type.typeName == annotation.name
                                }
                                is MezzeType.VariantType -> {
                                    // Hash-first check
                                    if (type.typeHash != null && declHash != null) type.typeHash == declHash
                                    else if (type.typeName == annotation.name) true
                                    // Fallback: if typeName is null but the variant has a constructor
                                    // matching the annotation name, treat it as a match.
                                    // This handles cross-module imports where the type declaration
                                    // isn't in the resolver's typeDeclarations map.
                                    else if (type.typeName == null && type.constructors.containsKey(annotation.name)) true
                                    else false
                                }
                                is MezzeType.TypeApplication -> {
                                    if (type.typeHash != null && declHash != null) type.typeHash == declHash
                                    else type.constructor == annotation.name && type.args.isEmpty()
                                }
                                else -> false
                            }
                        }
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                when {
                    type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> {
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotation(argType, argAnnotation, typeVars, typeVarBindings)
                        }
                    }
                    type is MezzeType.VariantType && type.typeName == null -> {
                        val typeDecl = typeDeclarations[annotation.constructor]
                        if (typeDecl != null && typeDecl.definition is VariantDefinition) {
                            val definition = typeDecl.definition as VariantDefinition
                            val declConstructors = definition.constructors.map { it.name }.toSet()
                            if (!declConstructors.containsAll(type.constructors.keys)) {
                                return false
                            }
                            val inferredArgs = inferTypeArgsFromVariant(type, typeDecl, definition)
                            if (inferredArgs != null && inferredArgs.size == annotation.args.size) {
                                inferredArgs.zip(annotation.args).all { (inferredType, argAnnotation) ->
                                    typeMatchesAnnotation(inferredType, argAnnotation, typeVars, typeVarBindings)
                                }
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                    type is MezzeType.SeqType && annotation.constructor == BuiltinTypes.SEQ -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings)
                    }
                    type is MezzeType.ListType && annotation.constructor == BuiltinTypes.LIST -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings)
                    }
                    else -> false
                }
            }
            is RecordTypeAnnotation -> {
                if (type !is MezzeType.RecordType) return false
                if (type.fields.size != annotation.entries.size) return false
                annotation.entries.all { entry ->
                    val fieldType = type.fields[entry.name] ?: return false
                    typeMatchesAnnotation(fieldType, entry.type, typeVars, typeVarBindings)
                }
            }
            is VariantTypeAnnotation -> {
                if (type !is MezzeType.VariantType) return false
                annotation.constructors.all { constructor ->
                    val concretePayload = type.constructors[constructor.name]
                    val dataType = constructor.dataType
                    if (dataType == null) {
                        concretePayload == null
                    } else if (concretePayload == null) {
                        false
                    } else {
                        typeMatchesAnnotation(concretePayload, dataType, typeVars, typeVarBindings)
                    }
                }
            }
            is SeqTypeAnnotation -> {
                // Handle [T] syntax for seq types
                type is MezzeType.SeqType && typeMatchesAnnotation(type.elementType, annotation.elementType, typeVars, typeVarBindings)
            }
            else -> false
        }
    }

    private fun typeMatchesDeclaration(type: MezzeType, typeDecl: TypeDeclaration): Boolean {
        // Hash-first: check typeHash against declaration's contentHash
        if (type is MezzeType.TypeApplication) {
            val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
            return hashMatch || (type.constructor == typeDecl.name && type.args.isEmpty())
        }
        return when (val definition = typeDecl.definition) {
            is RecordDefinition -> {
                if (type !is MezzeType.RecordType) return false
                val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
                hashMatch || type.typeName == typeDecl.name || type.fields.keys == definition.fields.map { it.name }.toSet()
            }
            is VariantDefinition -> {
                if (type !is MezzeType.VariantType) return false
                val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
                hashMatch || type.typeName == typeDecl.name ||
                definition.constructors.map { it.name }.toSet().containsAll(type.constructors.keys)
            }
            else -> false
        }
    }

}
