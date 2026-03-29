package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.util.StringDistance
import java.util.concurrent.ConcurrentHashMap

/**
 * Unification algorithm - the heart of type inference.
 *
 * Unification finds a substitution that makes two types equal.
 * This is the core algorithm used in Hindley-Milner type inference.
 *
 * Example:
 *   unify(List α, List Int) = Success({α -> Int})
 *   unify(Int, String) = Failure("Type mismatch: Int is not compatible with String")
 *   unify(α, List α) = Failure("Occurs check: α occurs in List α")
 *
 * Design:
 * - Pure function (no side effects)
 * - Returns sealed UnificationResult (no nulls!)
 * - Exhaustive pattern matching on all MezzeType cases
 * - Structural unification for records/variants
 *
 * Performance optimization:
 * - Memoization cache for repeated unification of the same type pairs
 * - Cache is thread-local to avoid concurrency issues
 * - Call clearUnificationCache() between type-checking invocations
 */

/**
 * Thread-local memoization cache for unification results.
 * Maps (type1, type2) pairs to their unification results.
 *
 * Note: We use a ConcurrentHashMap for safety, but each thread should
 * primarily access its own entries. The cache is keyed by (type1, type2)
 * pairs where order matters (symmetric calls are cached separately).
 */
private val unificationCache = ThreadLocal.withInitial {
    ConcurrentHashMap<Pair<MezzeType, MezzeType>, UnificationResult>()
}

/**
 * Clear the unification cache.
 * Should be called between type-checking invocations to prevent stale results.
 */
fun clearUnificationCache() {
    unificationCache.get().clear()
}

/**
 * Result of unification.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class UnificationResult {
    /**
     * Unification succeeded with a substitution.
     */
    data class Success(val substitution: Substitution) : UnificationResult()

    /**
     * Unification failed with an error message.
     */
    data class Failure(
        val message: String,
        val type1: MezzeType? = null,
        val type2: MezzeType? = null
    ) : UnificationResult()
}

/**
 * Unify two types.
 *
 * Finds a substitution that makes the two types equal.
 * Returns Success with substitution, or Failure with error message.
 *
 * @param typeDeclarations Map of type declarations for expanding type applications
 */
fun unify(type1: MezzeType, type2: MezzeType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Shortcut: if types are identical, no substitution needed
    if (type1 == type2) {
        return UnificationResult.Success(Substitution.empty)
    }

    // Check memoization cache
    // Note: The cache is cleared between type-checking invocations (in TypeChecker.typeCheck()),
    // and typeDeclarations is constant within a single invocation, so we can safely cache
    // results regardless of whether typeDeclarations is empty.
    val cacheKey = Pair(type1, type2)
    val cachedResult = unificationCache.get()[cacheKey]
    if (cachedResult != null) {
        return cachedResult
    }

    // Perform unification and cache the result
    val result = unifyImpl(type1, type2, typeDeclarations)
    unificationCache.get()[cacheKey] = result
    return result
}

/**
 * Internal implementation of unification (without caching).
 */
private fun unifyImpl(type1: MezzeType, type2: MezzeType, typeDeclarations: Map<String, TypeDeclaration>): UnificationResult {
    // SPECIAL CASE: Bool primitive unifies with Bool variant
    // BoolType (primitive) can unify with VariantType([True, False])
    // This allows pattern matching on True/False while keeping Bool as a primitive
    if (type1 is MezzeType.BoolType && type2 is MezzeType.VariantType) {
        if (isBoolVariant(type2)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (type1 is MezzeType.VariantType && type2 is MezzeType.BoolType) {
        if (isBoolVariant(type1)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }

    // SPECIAL CASE: TypeApplication expansion
    // Handle cross-type unification: VariantType with TypeApplication
    when {
        type1 is MezzeType.VariantType && type2 is MezzeType.TypeApplication -> {
            val expanded = expandTypeApplicationToVariant(type2, typeDeclarations)
            return if (expanded != null) {
                unify(type1, expanded, typeDeclarations)
            } else {
                // Can't expand - fall through to error
                UnificationResult.Failure(
                    message = "Type mismatch: ${formatType(type1)} is not compatible with ${formatType(type2)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.VariantType -> {
            val expanded = expandTypeApplicationToVariant(type1, typeDeclarations)
            return if (expanded != null) {
                unify(expanded, type2, typeDeclarations)
            } else {
                // Can't expand - fall through to error
                UnificationResult.Failure(
                    message = "Type mismatch: ${formatType(type1)} is not compatible with ${formatType(type2)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        // Handle cross-type unification: RecordType with TypeApplication
        type1 is MezzeType.RecordType && type2 is MezzeType.TypeApplication -> {
            // Use principled expansion based on definition type
            when (val expansion = expandTypeApplication(type2, typeDeclarations)) {
                is TypeExpansionResult.Record -> {
                    return unify(type1, expansion.recordType, typeDeclarations)
                }
                is TypeExpansionResult.Newtype -> {
                    // Newtype pattern: {age: Int} unifies with User (| User { name: String, age: Int } |)
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(type1, expansion.payloadType, typeDeclarations)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                    // Can't unify record with variant or unknown type
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: ${formatType(type1)} is not compatible with ${formatType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.RecordType -> {
            // Use principled expansion based on definition type
            when (val expansion = expandTypeApplication(type1, typeDeclarations)) {
                is TypeExpansionResult.Record -> {
                    return unify(expansion.recordType, type2, typeDeclarations)
                }
                is TypeExpansionResult.Newtype -> {
                    // Newtype pattern
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(expansion.payloadType, type2, typeDeclarations)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                    // Can't unify variant or unknown with record
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: ${formatType(type1)} is not compatible with ${formatType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
        // Handle RecordType with VariantType (single-constructor variant / newtype pattern)
        // Example: {age: Int} unifies with User (| User { name: String, age: Int } |)
        // This allows field access on single-constructor variants to work via constraint generation
        type1 is MezzeType.RecordType && type2 is MezzeType.VariantType -> {
            // Check if variant is a single-constructor variant (newtype pattern)
            if (type2.constructors.size == 1 && type2.rowVar == null) {
                val (_, payloadType) = type2.constructors.entries.first()
                if (payloadType != null) {
                    // Unify the record with the payload type
                    return unify(type1, payloadType, typeDeclarations)
                }
            }
            // Multi-constructor variants or no payload - can't unify with record
            return UnificationResult.Failure(
                message = "Type mismatch: expected ${formatType(type2)}, but got ${formatType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.VariantType && type2 is MezzeType.RecordType -> {
            // Check if variant is a single-constructor variant (newtype pattern)
            if (type1.constructors.size == 1 && type1.rowVar == null) {
                val (_, payloadType) = type1.constructors.entries.first()
                if (payloadType != null) {
                    // Unify the payload type with the record
                    return unify(payloadType, type2, typeDeclarations)
                }
            }
            // Multi-constructor variants or no payload - can't unify with record
            return UnificationResult.Failure(
                message = "Type mismatch: expected ${formatType(type2)}, but got ${formatType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
    }

    return when {
        // CASE 1: Type variable unification
        type1 is MezzeType.VarType -> unifyVar(type1.variable, type2)
        type2 is MezzeType.VarType -> unifyVar(type2.variable, type1)

        // CASE 2: Effect polymorphism - Suspended<T> with T
        // Allow pure types to unify with suspended types (effect polymorphism)
        // Examples: Int <Log> + Int → Int <Log>
        //          Int <Log> + Int <DB> → Int <Log, DB>
        type1 is MezzeType.SuspendedType && type2 !is MezzeType.SuspendedType -> {
            // Suspended<T> unifies with T by unifying the value types
            val valueResult = unify(type1.valueType, type2, typeDeclarations)
            when (valueResult) {
                is UnificationResult.Success -> {
                    // Result is Suspended<T> with the substitution applied
                    // The suspended type (type1) is the more specific type
                    valueResult
                }
                is UnificationResult.Failure -> valueResult
            }
        }
        type1 !is MezzeType.SuspendedType && type2 is MezzeType.SuspendedType -> {
            // T unifies with Suspended<T> by unifying the value types
            val valueResult = unify(type1, type2.valueType, typeDeclarations)
            when (valueResult) {
                is UnificationResult.Success -> {
                    // Result is Suspended<T> with the substitution applied
                    // The suspended type (type2) is the more specific type
                    valueResult
                }
                is UnificationResult.Failure -> valueResult
            }
        }

        // CASE 3: Function type unification
        type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType ->
            unifyFunctions(type1, type2, typeDeclarations)

        // CASE 4: Record type unification
        type1 is MezzeType.RecordType && type2 is MezzeType.RecordType ->
            unifyRecords(type1, type2, typeDeclarations)

        // CASE 5: Variant type unification
        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType ->
            unifyVariants(type1, type2, typeDeclarations)

        // CASE 6: Type application unification
        type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication ->
            unifyTypeApplications(type1, type2, typeDeclarations)

        // CASE 7: Seq type unification
        type1 is MezzeType.SeqType && type2 is MezzeType.SeqType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 7a: Cross-unification between SeqType and TypeApplication("Seq", ...)
        type1 is MezzeType.SeqType && type2 is MezzeType.TypeApplication && type2.constructor == "Seq" && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], typeDeclarations)
        type1 is MezzeType.TypeApplication && type1.constructor == "Seq" && type1.args.size == 1 && type2 is MezzeType.SeqType ->
            unify(type1.args[0], type2.elementType, typeDeclarations)

        // CASE 8: List type unification
        type1 is MezzeType.ListType && type2 is MezzeType.ListType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 8a: Cross-unification between ListType and TypeApplication("List", ...)
        type1 is MezzeType.ListType && type2 is MezzeType.TypeApplication && type2.constructor == "List" && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], typeDeclarations)
        type1 is MezzeType.TypeApplication && type1.constructor == "List" && type1.args.size == 1 && type2 is MezzeType.ListType ->
            unify(type1.args[0], type2.elementType, typeDeclarations)

        // CASE 9: Set type unification
        type1 is MezzeType.SetType && type2 is MezzeType.SetType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 9a: Cross-unification between SetType and TypeApplication("Set", ...)
        type1 is MezzeType.SetType && type2 is MezzeType.TypeApplication && type2.constructor == "Set" && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], typeDeclarations)
        type1 is MezzeType.TypeApplication && type1.constructor == "Set" && type1.args.size == 1 && type2 is MezzeType.SetType ->
            unify(type1.args[0], type2.elementType, typeDeclarations)

        // CASE 9b: SortedMap type unification
        type1 is MezzeType.SortedMapType && type2 is MezzeType.SortedMapType ->
            unifySortedMaps(type1, type2, typeDeclarations)

        // CASE 9c: Cross-unification between SortedMapType and TypeApplication("SortedMap", ...)
        type1 is MezzeType.SortedMapType && type2 is MezzeType.TypeApplication && type2.constructor == "SortedMap" && type2.args.size == 2 -> {
            val keyResult = unify(type1.keyType, type2.args[0], typeDeclarations)
            when (keyResult) {
                is UnificationResult.Success -> {
                    val valueResult = unify(keyResult.substitution.apply(type1.valueType), keyResult.substitution.apply(type2.args[1]), typeDeclarations)
                    when (valueResult) {
                        is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                        is UnificationResult.Failure -> valueResult
                    }
                }
                is UnificationResult.Failure -> keyResult
            }
        }
        type1 is MezzeType.TypeApplication && type1.constructor == "SortedMap" && type1.args.size == 2 && type2 is MezzeType.SortedMapType -> {
            val keyResult = unify(type1.args[0], type2.keyType, typeDeclarations)
            when (keyResult) {
                is UnificationResult.Success -> {
                    val valueResult = unify(keyResult.substitution.apply(type1.args[1]), keyResult.substitution.apply(type2.valueType), typeDeclarations)
                    when (valueResult) {
                        is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                        is UnificationResult.Failure -> valueResult
                    }
                }
                is UnificationResult.Failure -> keyResult
            }
        }

        // CASE 9d: SortedSet type unification
        type1 is MezzeType.SortedSetType && type2 is MezzeType.SortedSetType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 9e: Cross-unification between SortedSetType and TypeApplication("SortedSet", ...)
        type1 is MezzeType.SortedSetType && type2 is MezzeType.TypeApplication && type2.constructor == "SortedSet" && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], typeDeclarations)
        type1 is MezzeType.TypeApplication && type1.constructor == "SortedSet" && type1.args.size == 1 && type2 is MezzeType.SortedSetType ->
            unify(type1.args[0], type2.elementType, typeDeclarations)

        // CASE 10: Map type unification
        type1 is MezzeType.MapType && type2 is MezzeType.MapType ->
            unifyMaps(type1, type2, typeDeclarations)

        // CASE 10a: Cross-unification between MapType and TypeApplication("Map", ...)
        type1 is MezzeType.MapType && type2 is MezzeType.TypeApplication && type2.constructor == "Map" && type2.args.size == 2 -> {
            val keyResult = unify(type1.keyType, type2.args[0], typeDeclarations)
            when (keyResult) {
                is UnificationResult.Success -> {
                    val valueResult = unify(keyResult.substitution.apply(type1.valueType), keyResult.substitution.apply(type2.args[1]), typeDeclarations)
                    when (valueResult) {
                        is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                        is UnificationResult.Failure -> valueResult
                    }
                }
                is UnificationResult.Failure -> keyResult
            }
        }
        type1 is MezzeType.TypeApplication && type1.constructor == "Map" && type1.args.size == 2 && type2 is MezzeType.MapType -> {
            val keyResult = unify(type1.args[0], type2.keyType, typeDeclarations)
            when (keyResult) {
                is UnificationResult.Success -> {
                    val valueResult = unify(keyResult.substitution.apply(type1.args[1]), keyResult.substitution.apply(type2.valueType), typeDeclarations)
                    when (valueResult) {
                        is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                        is UnificationResult.Failure -> valueResult
                    }
                }
                is UnificationResult.Failure -> keyResult
            }
        }

        // CASE 11: Suspended type unification (both suspended)
        type1 is MezzeType.SuspendedType && type2 is MezzeType.SuspendedType ->
            unifySuspended(type1, type2, typeDeclarations)

        // CASE 12: Effect row unification
        type1 is MezzeType.EffectRow && type2 is MezzeType.EffectRow ->
            unifyEffectRows(type1, type2)

        // CASE 13: Primitive type unification
        type1 is MezzeType.IntType && type2 is MezzeType.IntType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FloatType && type2 is MezzeType.FloatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.StringType && type2 is MezzeType.StringType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.BoolType && type2 is MezzeType.BoolType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.NatType && type2 is MezzeType.NatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FracType && type2 is MezzeType.FracType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.OrderingType && type2 is MezzeType.OrderingType -> UnificationResult.Success(Substitution.empty)

        // CASE 14: Newtype unification — same name = same type, unify wrapped types
        type1 is MezzeType.NewtypeType && type2 is MezzeType.NewtypeType -> {
            if (type1.name == type2.name) {
                unify(type1.wrappedType, type2.wrappedType, typeDeclarations)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${type1.name} is not the same type as ${type2.name}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }

        // OrderingType can unify with VariantType([LT, EQ, GT]) and vice versa
        // This supports pattern matching where the scrutinee is OrderingType but patterns create a VariantType
        type1 is MezzeType.OrderingType && type2 is MezzeType.VariantType -> {
            // Check if the variant contains exactly LT, EQ, GT with no payloads and is closed
            val expectedConstructors = setOf("LT", "EQ", "GT")
            val actualConstructors = type2.constructors.keys
            val allMatch = actualConstructors == expectedConstructors &&
                           type2.constructors.all { (_, payload) -> payload == null } &&
                           type2.rowVar == null
            if (allMatch || (actualConstructors.all { it in expectedConstructors } && type2.rowVar == null)) {
                // Compatible - OrderingType matches the Ordering variant
                UnificationResult.Success(Substitution.empty)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: expected ${formatType(type2)}, but got ${formatType(type1)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.VariantType && type2 is MezzeType.OrderingType -> {
            // Symmetric case
            val expectedConstructors = setOf("LT", "EQ", "GT")
            val actualConstructors = type1.constructors.keys
            val allMatch = actualConstructors == expectedConstructors &&
                           type1.constructors.all { (_, payload) -> payload == null } &&
                           type1.rowVar == null
            if (allMatch || (actualConstructors.all { it in expectedConstructors } && type1.rowVar == null)) {
                UnificationResult.Success(Substitution.empty)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: expected ${formatType(type2)}, but got ${formatType(type1)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }

        type1 is MezzeType.BytesType && type2 is MezzeType.BytesType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.ByteBufferType && type2 is MezzeType.ByteBufferType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.CharType && type2 is MezzeType.CharType -> UnificationResult.Success(Substitution.empty)

        // CASE 14: Type mismatch
        else -> {
            UnificationResult.Failure(
                message = "Type mismatch: expected ${formatType(type2)}, but got ${formatType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
    }
}

/**
 * Unify a type variable with a type.
 *
 * Performs occurs check to prevent infinite types.
 */
private fun unifyVar(typeVar: TypeVar, type: MezzeType): UnificationResult {
    // If type is the same variable, no substitution needed
    if (type is MezzeType.VarType && type.variable == typeVar) {
        return UnificationResult.Success(Substitution.empty)
    }

    // SPECIAL CASE: SuspendedType containing the same type variable
    // When unifying 'a with Suspended<'a, effects>, this is NOT an infinite type error.
    // Instead, we unify the type variable with the underlying value type (unwrap).
    // This handles cases like match branches where one branch is pure ('a = Int)
    // and another is effectful (Suspended<'a, <Log>>).
    if (type is MezzeType.SuspendedType) {
        val valueType = type.valueType
        if (valueType is MezzeType.VarType && valueType.variable == typeVar) {
            // 'a unifies with Suspended<'a> - this means 'a stays as 'a (no substitution needed,
            // the effect wrapping is handled at a higher level)
            return UnificationResult.Success(Substitution.empty)
        }
    }

    // Occurs check: prevent infinite types
    // NOTE: Like the OldTypeChecker, we IGNORE occurs check failures instead of throwing errors.
    // This allows advanced patterns like the Choice effect to type-check correctly.
    // The occurs check failure means we can't create a substitution (would be infinite),
    // so we just return an empty substitution and let the constraint solver continue.
    if (occursCheck(typeVar, type)) {
        return UnificationResult.Success(Substitution.empty)
    }

    // Create substitution
    return UnificationResult.Success(Substitution.single(typeVar, type))
}

/**
 * Unify two function types.
 */
private fun unifyFunctions(fn1: MezzeType.FunctionType, fn2: MezzeType.FunctionType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify parameter types
    val paramResult = unify(fn1.paramType, fn2.paramType, typeDeclarations)
    if (paramResult is UnificationResult.Failure) {
        return paramResult
    }
    val paramSubst = (paramResult as UnificationResult.Success).substitution

    // Apply parameter substitution and unify return types
    val return1 = paramSubst.apply(fn1.returnType)
    val return2 = paramSubst.apply(fn2.returnType)
    val returnResult = unify(return1, return2, typeDeclarations)
    if (returnResult is UnificationResult.Failure) {
        return returnResult
    }
    val returnSubst = (returnResult as UnificationResult.Success).substitution

    // Unify effects (if both have effects)
    val effects1 = fn1.effects
    val effects2 = fn2.effects

    // Effect unification rules:
    // - Pure (null or empty closed <>) can match anything
    // - Polymorphic (<...> - empty with row var) can match anything (row var gets instantiated)
    // - Concrete effects (<Log>) can only match if the other side can accept them
    //
    // The ONLY error case: concrete effects on one side, pure (closed empty) on the other
    // Because a pure function cannot handle effects that the effectful function produces

    // Check for pure effects (no simple effects AND no effect instances)
    val isPure1 = effects1 == null || (effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar == null)
    val isPure2 = effects2 == null || (effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar == null)
    // Check for polymorphic (empty effects but has row var)
    val isPolymorphic1 = effects1 != null && effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar != null
    val isPolymorphic2 = effects2 != null && effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar != null
    // Check for concrete effects (has simple effects OR effect instances)
    val hasConcrete1 = effects1 != null && (effects1.effects.isNotEmpty() || effects1.effectInstances.isNotEmpty())
    val hasConcrete2 = effects2 != null && (effects2.effects.isNotEmpty() || effects2.effectInstances.isNotEmpty())

    val effectSubst = when {
        // Both pure - OK
        isPure1 && isPure2 -> Substitution.empty

        // Pure with polymorphic - OK (polymorphic instantiates to empty)
        isPure1 && isPolymorphic2 -> Substitution.empty
        isPolymorphic1 && isPure2 -> Substitution.empty

        // Both polymorphic - OK (unify the row variables)
        isPolymorphic1 && isPolymorphic2 -> Substitution.empty

        // Polymorphic with concrete - OK (polymorphic instantiates to those effects)
        isPolymorphic1 && hasConcrete2 -> Substitution.empty
        hasConcrete1 && isPolymorphic2 -> Substitution.empty

        // Pure with concrete - ERROR if concrete is on fn1 (actual), OK if concrete is on fn2 (expected)
        // A pure function CAN be used where effectful is expected (caller handles effects that don't occur)
        // An effectful function CANNOT be used where pure is expected (caller can't handle effects)
        isPure1 && hasConcrete2 -> Substitution.empty  // Pure actual, effectful expected - OK
        hasConcrete1 && isPure2 -> {
            // Effectful actual, pure expected - ERROR
            val allEffects = effects1!!.effects + effects1.effectInstances.map { it.name }
            return UnificationResult.Failure(
                message = "Cannot use effectful function <${allEffects.joinToString()}> where pure function is expected",
                type1 = fn1,
                type2 = fn2
            )
        }

        // Both have concrete effects - unify them
        hasConcrete1 && hasConcrete2 -> {
            val effectResult = unifyEffectRows(effects1!!, effects2!!)
            if (effectResult is UnificationResult.Failure) {
                return effectResult
            }
            (effectResult as UnificationResult.Success).substitution
        }

        // Catch-all for any other combination (shouldn't happen)
        else -> Substitution.empty
    }

    // Compose all substitutions
    val finalSubst = returnSubst.compose(paramSubst).compose(effectSubst)
    return UnificationResult.Success(finalSubst)
}

/**
 * Unify two record types.
 *
 * Supports row polymorphism and currying/partial application:
 *
 * OPEN RECORDS (with rowVar):
 * - Can provide MORE fields than expected (extras ignored via row polymorphism)
 * - {x: Int | r} unifies with {x: Int, y: Int} (y goes into row variable)
 *
 * CLOSED RECORDS (no rowVar):
 * - For partial application/currying: one must be SUBSET of the other
 * - {x: Int, y: Int} unifies with {y: Int} (partial application - x will be curried)
 * - {x: Int} does NOT unify with {a: Int} (different field names)
 * - Field names must match exactly (no renaming)
 *
 * Examples:
 * - multiply {x, y} -> x * y  with  multiply {y = 5}  gives  {x} -> x * 5
 */
private fun unifyRecords(rec1: MezzeType.RecordType, rec2: MezzeType.RecordType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    val fields1 = rec1.fields.keys
    val fields2 = rec2.fields.keys

    // CASE 1: At least one record is OPEN (has rowVar)
    // Open records allow extra fields via row polymorphism
    if (rec1.rowVar != null || rec2.rowVar != null) {
        // Unify shared fields, allow extra fields in either record
        val allFields = (fields1 + fields2).toSet()

        var substitution = Substitution.empty
        for (fieldName in allFields) {
            val fieldType1 = rec1.fields[fieldName]
            val fieldType2 = rec2.fields[fieldName]

            when {
                fieldType1 != null && fieldType2 != null -> {
                    // Field in both - must have compatible types
                    val appliedType1 = substitution.apply(fieldType1)
                    val appliedType2 = substitution.apply(fieldType2)

                    val fieldResult = unify(appliedType1, appliedType2, typeDeclarations)
                    if (fieldResult is UnificationResult.Failure) {
                        return UnificationResult.Failure(
                            message = "Field '$fieldName' type mismatch: ${fieldResult.message}",
                            type1 = rec1,
                            type2 = rec2
                        )
                    }

                    val fieldSubst = (fieldResult as UnificationResult.Success).substitution
                    substitution = fieldSubst.compose(substitution)
                }
                // Field only in one record - OK for open records (goes into row variable)
                else -> {}
            }
        }

        return UnificationResult.Success(substitution)
    }

    // CASE 2: Both records are CLOSED
    // For closed records, fields must match EXACTLY
    // Unlike open records, closed records don't allow extra fields
    if (fields1 != fields2) {
        val extraIn1 = fields1 - fields2
        val extraIn2 = fields2 - fields1
        val mismatchParts = mutableListOf<String>()
        if (extraIn1.isNotEmpty()) {
            mismatchParts.add("extra fields in first: ${extraIn1.joinToString()}")
        }
        if (extraIn2.isNotEmpty()) {
            mismatchParts.add("extra fields in second: ${extraIn2.joinToString()}")
        }
        return UnificationResult.Failure(
            message = "Closed record field mismatch: {${fields1.joinToString()}} vs {${fields2.joinToString()}}. ${mismatchParts.joinToString("; ")}",
            type1 = rec1,
            type2 = rec2
        )
    }

    val sharedFields = fields1  // They're equal, so use either

    // Unify all shared fields (must have matching types)
    var substitution = Substitution.empty
    for (fieldName in sharedFields) {
        val fieldType1 = substitution.apply(rec1.fields[fieldName]!!)
        val fieldType2 = substitution.apply(rec2.fields[fieldName]!!)

        val fieldResult = unify(fieldType1, fieldType2, typeDeclarations)
        if (fieldResult is UnificationResult.Failure) {
            return UnificationResult.Failure(
                message = "Field '$fieldName' type mismatch: ${fieldResult.message}",
                type1 = rec1,
                type2 = rec2
            )
        }

        val fieldSubst = (fieldResult as UnificationResult.Success).substitution
        substitution = fieldSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

/**
 * Unify two variant types.
 */
internal fun unifyVariants(var1: MezzeType.VariantType, var2: MezzeType.VariantType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    val constructors1 = var1.constructors.keys
    val constructors2 = var2.constructors.keys

    // Case 1: Both variants are open - merge constructors
    if (var1.rowVar != null && var2.rowVar != null) {
        val allConstructors = (constructors1 + constructors2).toSet()

        var substitution = Substitution.empty
        for (constructorName in allConstructors) {
            val dataType1 = var1.constructors[constructorName]
            val dataType2 = var2.constructors[constructorName]

            if (dataType1 != null && dataType2 != null) {
                // Constructor in both - must have compatible payload types
                val appliedType1 = substitution.apply(dataType1)
                val appliedType2 = substitution.apply(dataType2)

                val constructorResult = unify(appliedType1, appliedType2, typeDeclarations)
                if (constructorResult is UnificationResult.Failure) {
                    return UnificationResult.Failure(
                        message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                        type1 = var1,
                        type2 = var2
                    )
                }

                val constructorSubst = (constructorResult as UnificationResult.Success).substitution
                substitution = constructorSubst.compose(substitution)
            }
            // Constructor only in one variant - that's OK for open variants
        }

        return UnificationResult.Success(substitution)
    }

    // Case 2: One is closed, one is open
    // Open variants (with `...`) accept ANY constructor, so a closed variant like `| CustomStatus |`
    // can always unify with an open variant like `| Active, Inactive, ... |`.
    //
    // The key insight: open variants are extensible - they represent "these constructors AND possibly more".
    // So `| Info |` (closed) is compatible with `| Error, Warn, Info, ... |` (open) because Info is
    // implicitly included in the "..." part even if not explicitly listed.
    //
    // HOWEVER: If the closed variant has a typeName (is a declared type like `Color`), then
    // ALL constructors in the open variant MUST be in the closed variant. Otherwise we'd be
    // allowing invalid constructors like `Yellow` when the type is `Color = | Red, Green, Blue |`.
    if (var1.rowVar != null || var2.rowVar != null) {
        val (openVar, closedVar) = if (var1.rowVar != null) Pair(var1, var2) else Pair(var2, var1)
        val closedCtors = closedVar.constructors.keys
        val openCtors = openVar.constructors.keys

        // CRITICAL: If closed variant has a typeName (is a declared type or inline annotation),
        // check that ALL constructors in the open variant exist in the closed variant.
        // This prevents `Yellow` from unifying with `Color = | Red, Green, Blue |`.
        if (closedVar.typeName != null) {
            val invalidConstructors = openCtors - closedCtors
            if (invalidConstructors.isNotEmpty()) {
                val available = closedCtors.sorted().joinToString(", ")
                val invalid = invalidConstructors.sorted().joinToString(", ")
                // Use friendly message for inline annotations and inferred variants
                val typeDesc = when (closedVar.typeName) {
                    "<inline>" -> "inline variant type '| $available |'"
                    "<inferred>" -> "inferred variant type '| $available |'"
                    else -> "type '${closedVar.typeName}'"
                }
                return UnificationResult.Failure(
                    message = "Constructor(s) '$invalid' not found in $typeDesc. " +
                              "Available constructors: $available",
                    type1 = var1,
                    type2 = var2
                )
            }
        }

        // Unify payload types for constructors that exist in BOTH variants
        // (constructors only in the closed variant are implicitly accepted by the open variant)
        var substitution = Substitution.empty
        for (constructorName in closedCtors) {
            val dataType1 = var1.constructors[constructorName]
            val dataType2 = var2.constructors[constructorName]

            // Only unify if constructor exists in both (has declared payload type in open variant)
            if (dataType1 != null && dataType2 != null) {
                val appliedType1 = substitution.apply(dataType1)
                val appliedType2 = substitution.apply(dataType2)

                val constructorResult = unify(appliedType1, appliedType2, typeDeclarations)
                if (constructorResult is UnificationResult.Failure) {
                    return UnificationResult.Failure(
                        message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                        type1 = var1,
                        type2 = var2
                    )
                }

                val constructorSubst = (constructorResult as UnificationResult.Success).substitution
                substitution = constructorSubst.compose(substitution)
            }
            // NOTE: Don't fail on payload mismatch (one has data, one doesn't).
            // This allows constructor references like `Some` (without payload) to unify
            // with the full type `Some { val: 'a }`. The constraint solver handles
            // the payload checking at the use site.
            //
            // Also: constructors only in the closed variant are accepted by the open variant's `...`
        }

        return UnificationResult.Success(substitution)
    }

    // Case 3: Both variants are closed - merge constructors for inference purposes
    // This allows match expressions to return different constructors that get unified into
    // a combined variant type: `match x is Red -> Blue | Green -> Yellow` produces `| Blue, Yellow |`
    //
    // HOWEVER: If BOTH variants have a typeName (are declared types), they must be the SAME type.
    // This prevents `Animal` from unifying with `Color` even if they happen to share a constructor.
    if (var1.typeName != null && var2.typeName != null && var1.typeName != var2.typeName) {
        return UnificationResult.Failure(
            message = "Cannot unify different variant types '${var1.typeName}' and '${var2.typeName}'",
            type1 = var1,
            type2 = var2
        )
    }

    // If only one has a typeName (is a declared type), check that ALL constructors from the
    // anonymous variant exist in the declared type
    val (declaredVar, anonymousVar) = when {
        var1.typeName != null && var2.typeName == null -> Pair(var1, var2)
        var2.typeName != null && var1.typeName == null -> Pair(var2, var1)
        else -> Pair(null, null)  // Both named (already checked above) or both anonymous
    }

    if (declaredVar != null && anonymousVar != null) {
        val invalidConstructors = anonymousVar.constructors.keys - declaredVar.constructors.keys
        if (invalidConstructors.isNotEmpty()) {
            val available = declaredVar.constructors.keys.sorted().joinToString(", ")
            val invalid = invalidConstructors.sorted().joinToString(", ")
            // Use friendly message for inline annotations and inferred variants
            val typeDesc = when (declaredVar.typeName) {
                "<inline>" -> "inline variant type '| $available |'"
                "<inferred>" -> "inferred variant type '| $available |'"
                else -> "type '${declaredVar.typeName}'"
            }
            return UnificationResult.Failure(
                message = "Constructor(s) '$invalid' not found in $typeDesc. " +
                          "Available constructors: $available",
                type1 = var1,
                type2 = var2
            )
        }
    }

    val allConstructors = (constructors1 + constructors2).toSet()

    var substitution = Substitution.empty
    for (constructorName in allConstructors) {
        val dataType1 = var1.constructors[constructorName]
        val dataType2 = var2.constructors[constructorName]

        if (constructorName in constructors1 && constructorName in constructors2) {
            // Constructor in both - unify payload types
            if ((dataType1 == null) != (dataType2 == null)) {
                return UnificationResult.Failure(
                    message = "Constructor '$constructorName' data mismatch",
                    type1 = var1,
                    type2 = var2
                )
            }

            if (dataType1 != null && dataType2 != null) {
                val appliedType1 = substitution.apply(dataType1)
                val appliedType2 = substitution.apply(dataType2)

                val constructorResult = unify(appliedType1, appliedType2, typeDeclarations)
                if (constructorResult is UnificationResult.Failure) {
                    return UnificationResult.Failure(
                        message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                        type1 = var1,
                        type2 = var2
                    )
                }

                val constructorSubst = (constructorResult as UnificationResult.Success).substitution
                substitution = constructorSubst.compose(substitution)
            }
        }
        // Constructor only in one variant - allowed for merging during inference
    }

    return UnificationResult.Success(substitution)
}

/**
 * Unify two type applications.
 */
private fun unifyTypeApplications(app1: MezzeType.TypeApplication, app2: MezzeType.TypeApplication, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Type constructors must match - unless one is an open variant
    if (app1.constructor != app2.constructor) {
        // OPEN VARIANT HANDLING: If one type is an open variant, expand both and unify as variants
        // This allows open variants to accept constructors from other types
        val expanded1 = expandTypeApplicationToVariant(app1, typeDeclarations)
        val expanded2 = expandTypeApplicationToVariant(app2, typeDeclarations)

        if (expanded1 != null && expanded2 != null) {
            // At least one must be open for cross-type unification
            if (expanded1.rowVar != null || expanded2.rowVar != null) {
                return unifyVariants(expanded1, expanded2, typeDeclarations)
            }
        }

        return UnificationResult.Failure(
            message = "Type constructor mismatch: ${app1.constructor} vs ${app2.constructor}",
            type1 = app1,
            type2 = app2
        )
    }

    // Argument count must match
    if (app1.args.size != app2.args.size) {
        return UnificationResult.Failure(
            message = "Type argument count mismatch: ${app1.args.size} vs ${app2.args.size}",
            type1 = app1,
            type2 = app2
        )
    }

    // Unify each type argument
    var substitution = Substitution.empty
    for (i in app1.args.indices) {
        val arg1 = substitution.apply(app1.args[i])
        val arg2 = substitution.apply(app2.args[i])

        val argResult = unify(arg1, arg2, typeDeclarations)
        if (argResult is UnificationResult.Failure) {
            return UnificationResult.Failure(
                message = "Type argument $i mismatch: ${argResult.message}",
                type1 = app1,
                type2 = app2
            )
        }

        val argSubst = (argResult as UnificationResult.Success).substitution
        substitution = argSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

/**
 * Unify two map types.
 */
private fun unifyMaps(map1: MezzeType.MapType, map2: MezzeType.MapType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify key types
    val keyResult = unify(map1.keyType, map2.keyType, typeDeclarations)
    if (keyResult is UnificationResult.Failure) {
        return keyResult
    }
    val keySubst = (keyResult as UnificationResult.Success).substitution

    // Apply key substitution and unify value types
    val value1 = keySubst.apply(map1.valueType)
    val value2 = keySubst.apply(map2.valueType)
    val valueResult = unify(value1, value2, typeDeclarations)
    if (valueResult is UnificationResult.Failure) {
        return valueResult
    }
    val valueSubst = (valueResult as UnificationResult.Success).substitution

    return UnificationResult.Success(valueSubst.compose(keySubst))
}

/**
 * Unify two sorted map types.
 */
private fun unifySortedMaps(map1: MezzeType.SortedMapType, map2: MezzeType.SortedMapType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify key types
    val keyResult = unify(map1.keyType, map2.keyType, typeDeclarations)
    if (keyResult is UnificationResult.Failure) {
        return keyResult
    }
    val keySubst = (keyResult as UnificationResult.Success).substitution

    // Apply key substitution and unify value types
    val value1 = keySubst.apply(map1.valueType)
    val value2 = keySubst.apply(map2.valueType)
    val valueResult = unify(value1, value2, typeDeclarations)
    if (valueResult is UnificationResult.Failure) {
        return valueResult
    }
    val valueSubst = (valueResult as UnificationResult.Success).substitution

    return UnificationResult.Success(valueSubst.compose(keySubst))
}

/**
 * Unify two suspended types.
 *
 * Effect unification uses UNION semantics: when unifying Suspended<T, <E1>> with
 * Suspended<T, <E2>>, the result is Suspended<T, <E1, E2>>. This allows match
 * expressions with branches having different effects to type-check correctly.
 */
private fun unifySuspended(susp1: MezzeType.SuspendedType, susp2: MezzeType.SuspendedType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify value types
    val valueResult = unify(susp1.valueType, susp2.valueType, typeDeclarations)
    if (valueResult is UnificationResult.Failure) {
        return valueResult
    }
    val valueSubst = (valueResult as UnificationResult.Success).substitution

    // Effect rows use UNION semantics - merge them together
    // This is the correct behavior for match expressions where different branches
    // may have different effects (the overall type has all effects)
    return UnificationResult.Success(valueSubst)
}

/**
 * Unify two effect rows.
 *
 * Effect rows use UNION semantics: unifying <E1> with <E2> succeeds and
 * conceptually produces <E1, E2>. This allows match branches with different
 * effects to be unified.
 */
private fun unifyEffectRows(eff1: MezzeType.EffectRow, eff2: MezzeType.EffectRow): UnificationResult {
    // HERMETIC EFFECT CHECK: Mut cannot coexist with other effects
    // This ensures data-race freedom by preventing interleaving of mutation with other effects
    val allEffects1 = eff1.allEffectNames
    val allEffects2 = eff2.allEffectNames
    val combinedEffects = allEffects1 + allEffects2

    // Build a merged effectHashes map from both rows for hash-based Mut identification
    val combinedHashes = eff1.effectHashes + eff2.effectHashes
    // Resolve hash: prefer effectHashes, fall back to well-known hash by name
    fun resolveEffectHash(name: String): String? =
        combinedHashes[name] ?: WellKnownHashes.hashForName(name)

    val mutEffect = combinedEffects.firstOrNull { name ->
        WellKnownHashes.isMut(resolveEffectHash(name))
    }
    if (mutEffect != null && combinedEffects.size > 1) {
        val otherEffects = combinedEffects.filter { name ->
            !WellKnownHashes.isMut(resolveEffectHash(name))
        }.joinToString(", ")
        return UnificationResult.Failure(
            message = "$mutEffect effect is hermetic: cannot coexist with other effects ($otherEffects). " +
                "$mutEffect must be the only effect in scope to prevent data races. " +
                "Either handle the other effects before entering the mutation scope, " +
                "or restructure the code to separate mutation from other effectful operations.",
            type1 = eff1,
            type2 = eff2
        )
    }

    // Effect rows use UNION semantics for effect names, but when the same effect
    // appears in both rows with associated types, those associated types must unify.

    // Build maps from effect name to associated types for quick lookup
    val instances1 = eff1.effectInstances.associateBy { it.name }
    val instances2 = eff2.effectInstances.associateBy { it.name }

    // Find effects that appear in both rows with associated types
    val commonEffects = instances1.keys.intersect(instances2.keys)

    // For common effects, unify their associated types
    var composedSubst = Substitution.empty
    for (effectName in commonEffects) {
        val inst1 = instances1[effectName]!!
        val inst2 = instances2[effectName]!!

        // Both instances should have the same associated type keys (from the effect declaration)
        // Unify each associated type
        val allKeys = inst1.associatedTypes.keys + inst2.associatedTypes.keys
        for (key in allKeys) {
            val type1 = inst1.associatedTypes[key]
            val type2 = inst2.associatedTypes[key]

            if (type1 != null && type2 != null) {
                // Both have this associated type - unify them
                val result = unify(composedSubst.apply(type1), composedSubst.apply(type2))
                when (result) {
                    is UnificationResult.Success -> {
                        composedSubst = composedSubst.compose(result.substitution)
                    }
                    is UnificationResult.Failure -> {
                        return UnificationResult.Failure(
                            message = "Effect '$effectName' has incompatible associated type '$key': " +
                                "expected ${formatType(type2)} but got ${formatType(type1)}",
                            type1 = eff1,
                            type2 = eff2
                        )
                    }
                }
            }
            // If only one has the type, that's OK - the other will be inferred
        }
    }

    return UnificationResult.Success(composedSubst)
}

/**
 * Format a type for error messages.
 */
private fun formatType(type: MezzeType): String {
    // Use prettyPrintType for normalized output (converts 'q12 to 'a, 'b, etc.)
    return prettyPrintType(type)
}

// Keep original implementation for internal use if needed
private fun formatTypeRaw(type: MezzeType): String {
    // Use centralized registry for primitive types
    PrimitiveTypes.toName(type)?.let { return it }

    return when (type) {
        is MezzeType.VarType -> type.variable.name
        is MezzeType.FunctionType -> "{...} -> ..."
        is MezzeType.RecordType -> "{${type.fields.entries.take(3).joinToString { "${it.key}: ${formatType(it.value)}" }}${if (type.fields.size > 3) ", ..." else ""}}"
        is MezzeType.VariantType -> {
            val constructorStrs = type.constructors.entries.take(3).map { (name, payload) ->
                if (payload != null) "$name ${formatType(payload)}" else name
            }
            "| ${constructorStrs.joinToString(", ")}${if (type.constructors.size > 3) ", ..." else ""} |"
        }
        is MezzeType.TypeApplication -> "${type.constructor}${if (type.args.isNotEmpty()) " ${type.args.joinToString(" ") { formatType(it) }}" else ""}"
        is MezzeType.SeqType -> "Seq ${formatType(type.elementType)}"
        is MezzeType.ListType -> "List ${formatType(type.elementType)}"
        is MezzeType.SetType -> "Set ${formatType(type.elementType)}"
        is MezzeType.SortedMapType -> "SortedMap ${formatType(type.keyType)} ${formatType(type.valueType)}"
        is MezzeType.SortedSetType -> "SortedSet ${formatType(type.elementType)}"
        is MezzeType.MapType -> "Map ${formatType(type.keyType)} ${formatType(type.valueType)}"
        is MezzeType.SuspendedType -> "Suspended<${formatType(type.valueType)}>"
        is MezzeType.EffectRow -> "<${type.effects.take(3).joinToString()}${if (type.effects.size > 3) ", ..." else ""}>"
        else -> type.toString()
    }
}

/**
 * Expand a TypeApplication to a RecordType.
 *
 * For example:
 * - `Person` expands to `{ name: String, age: Int, ... }`
 *
 * This allows unifying concrete record types with type application aliases.
 *
 * @param typeApp The type application to expand
 * @param typeDeclarations Map of type declarations
 * @return The expanded record type, or null if expansion failed
 */
internal fun expandTypeApplicationToRecord(typeApp: MezzeType.TypeApplication, typeDeclarations: Map<String, TypeDeclaration>): MezzeType.RecordType? {
    // Look up the type declaration
    val typeDecl = typeDeclarations[typeApp.constructor] ?: return null

    // Only record definitions can be expanded to record types
    val recordDef = typeDecl.definition as? RecordDefinition ?: return null

    // Build substitution map for type parameters
    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        val substType = if (argType is MezzeType.VarType) {
            MezzeType.VarType(TypeVar.fresh())
        } else {
            argType
        }
        paramName to substType
    }

    // Helper to substitute type parameters in a MezzeType
    fun substituteTypeParams(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                typeParamSubst[type.variable.name] ?: type
            }
            is MezzeType.RecordType -> {
                MezzeType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) ->
                        substituteTypeParams(fieldType)
                    },
                    rowVar = type.rowVar
                )
            }
            is MezzeType.FunctionType -> {
                MezzeType.FunctionType(
                    paramType = substituteTypeParams(type.paramType),
                    returnType = substituteTypeParams(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.TypeApplication -> {
                MezzeType.TypeApplication(
                    constructor = type.constructor,
                    args = type.args.map { substituteTypeParams(it) },
                    typeHash = type.typeHash
                )
            }
            is MezzeType.SeqType -> {
                MezzeType.SeqType(substituteTypeParams(type.elementType))
            }
            is MezzeType.ListType -> {
                MezzeType.ListType(substituteTypeParams(type.elementType))
            }
            is MezzeType.SetType -> {
                MezzeType.SetType(substituteTypeParams(type.elementType))
            }
            is MezzeType.SortedMapType -> {
                MezzeType.SortedMapType(
                    keyType = substituteTypeParams(type.keyType),
                    valueType = substituteTypeParams(type.valueType)
                )
            }
            is MezzeType.SortedSetType -> {
                MezzeType.SortedSetType(substituteTypeParams(type.elementType))
            }
            is MezzeType.MapType -> {
                MezzeType.MapType(
                    keyType = substituteTypeParams(type.keyType),
                    valueType = substituteTypeParams(type.valueType)
                )
            }
            is MezzeType.VariantType -> {
                MezzeType.VariantType(
                    constructors = type.constructors.mapValues { (_, dataType) ->
                        dataType?.let { substituteTypeParams(it) }
                    },
                    rowVar = type.rowVar
                )
            }
            is MezzeType.SuspendedType -> {
                MezzeType.SuspendedType(
                    valueType = substituteTypeParams(type.valueType),
                    effects = type.effects
                )
            }
            else -> type
        }
    }

    // Shared ContextBuilder instance for consistent type variable creation
    val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()

    // Build the record type by converting each field
    val fields = recordDef.fields.associate { field ->
        val convertedType = contextBuilder.convertTypeAnnotation(field.type)
        val substitutedType = substituteTypeParams(convertedType)
        field.name to substitutedType
    }

    return MezzeType.RecordType(
        fields = fields,
        rowVar = if (recordDef.isOpen) RowVar.fresh() else null
    )
}

/**
 * Expand a TypeApplication to a VariantType.
 *
 * For example:
 * - `Option Int` expands to `[Some { val: Int }, None]`
 * - `Result String Int` expands to `[Ok { val: Int }, Err { error: String }]`
 *
 * This allows unifying concrete variant types like `[Some]` with type applications like `Option Int`.
 *
 * @param typeApp The type application to expand
 * @param typeDeclarations Map of type declarations
 * @return The expanded variant type, or null if expansion failed
 */
internal fun expandTypeApplicationToVariant(typeApp: MezzeType.TypeApplication, typeDeclarations: Map<String, TypeDeclaration>): MezzeType.VariantType? {
    // Look up the type declaration - first in local declarations, then in all cached modules
    val typeDecl = typeDeclarations[typeApp.constructor]
        ?: com.mezze.language.typechecker.ModuleResolver.lookupTypeDeclaration(typeApp.constructor)
        ?: return null

    // Handle both variant definitions and newtype definitions
    // Newtypes are essentially single-constructor variants
    val variantDef = when (val def = typeDecl.definition) {
        is VariantDefinition -> def
        is NewtypeDefinition -> {
            // Expand newtype to variant for pattern matching and construction.
            // Ability derivation uses a separate check (isNewtype in canDeriveAbility).
            val payloadType = if (def.wrappedType is RecordTypeAnnotation) {
                def.wrappedType
            } else {
                RecordTypeAnnotation(
                    entries = listOf(RecordTypeEntry("val", def.wrappedType))
                )
            }
            VariantDefinition(listOf(VariantConstructorDef(def.constructor, payloadType)))
        }
        else -> return null
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplication") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "Variant has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}"
        )
    }

    // Build substitution map for type parameters
    // Use the actual type arguments from the TypeApplication - this preserves
    // the connection between type parameters and their usage sites.
    // For example, Color 'a should expand to a variant where 'a is preserved,
    // so that pattern bindings like `Blue { n }` get `n: 'a` which can then
    // unify with other uses of 'a in the match body.
    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    // Helper to substitute type parameters in a MezzeType
    fun substituteTypeParams(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                // Check if this type variable matches a type parameter
                // Try both with and without the ' prefix since formats may differ:
                // - typeDecl.typeParams uses "'a" format (from parser)
                // - ContextBuilder.convertTypeAnnotation creates TypeVar with "a" format (without ')
                val varName = type.variable.name
                typeParamSubst[varName]
                    ?: typeParamSubst["'$varName"]
                    ?: typeParamSubst[varName.removePrefix("'")]
                    ?: type
            }
            is MezzeType.RecordType -> {
                MezzeType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) ->
                        substituteTypeParams(fieldType)
                    },
                    rowVar = type.rowVar
                )
            }
            is MezzeType.FunctionType -> {
                MezzeType.FunctionType(
                    paramType = substituteTypeParams(type.paramType),
                    returnType = substituteTypeParams(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.TypeApplication -> {
                MezzeType.TypeApplication(
                    constructor = type.constructor,
                    args = type.args.map { substituteTypeParams(it) },
                    typeHash = type.typeHash
                )
            }
            is MezzeType.SeqType -> {
                MezzeType.SeqType(substituteTypeParams(type.elementType))
            }
            is MezzeType.ListType -> {
                MezzeType.ListType(substituteTypeParams(type.elementType))
            }
            is MezzeType.SetType -> {
                MezzeType.SetType(substituteTypeParams(type.elementType))
            }
            is MezzeType.SortedMapType -> {
                MezzeType.SortedMapType(
                    keyType = substituteTypeParams(type.keyType),
                    valueType = substituteTypeParams(type.valueType)
                )
            }
            is MezzeType.SortedSetType -> {
                MezzeType.SortedSetType(substituteTypeParams(type.elementType))
            }
            is MezzeType.MapType -> {
                MezzeType.MapType(
                    keyType = substituteTypeParams(type.keyType),
                    valueType = substituteTypeParams(type.valueType)
                )
            }
            is MezzeType.VariantType -> {
                MezzeType.VariantType(
                    constructors = type.constructors.mapValues { (_, dataType) ->
                        dataType?.let { substituteTypeParams(it) }
                    },
                    rowVar = type.rowVar
                )
            }
            is MezzeType.SuspendedType -> {
                MezzeType.SuspendedType(
                    valueType = substituteTypeParams(type.valueType),
                    effects = type.effects
                )
            }
            // Primitive types don't contain type variables
            else -> type
        }
    }

    // Shared ContextBuilder instance for consistent type variable creation
    // IMPORTANT: Use a single instance to ensure type variables are consistent
    // within this expansion (though still creates fresh vars each call)
    val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()

    // Build the variant type by converting each constructor
    val constructors = variantDef.constructors.associate { constructor ->
        val dataType = constructor.dataType?.let { annotation ->
            // Convert type annotation to MezzeType
            // This creates TypeVars for the type parameters like 'a
            val convertedType = contextBuilder.convertTypeAnnotation(annotation)
            // Substitute those TypeVars with the actual type arguments
            substituteTypeParams(convertedType)
        }
        constructor.name to dataType
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplicationToVariant") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "VariantDef has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}",
            "Built ${constructors.size} constructors: ${constructors.keys}"
        )
    }

    return MezzeType.VariantType(
        constructors = constructors,
        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
        typeName = typeDecl.name  // Preserve the type name for validation
    )
}

/**
 * Check if a variant type is the Bool variant: | True, False |
 */
private fun isBoolVariant(variant: MezzeType.VariantType): Boolean {
    // Bool variant must have exactly 2 constructors: True and False, both with no payloads
    return variant.constructors.size == 2 &&
           variant.constructors.containsKey("True") &&
           variant.constructors.containsKey("False") &&
           variant.constructors["True"] == null &&
           variant.constructors["False"] == null
}

/**
 * Result of expanding a TypeApplication to its underlying type.
 *
 * Rather than trying RecordType first then VariantType, this sealed class
 * allows the caller to handle each case appropriately based on the actual
 * type definition.
 */
sealed class TypeExpansionResult {
    /** TypeApplication expands to a RecordType */
    data class Record(val recordType: MezzeType.RecordType) : TypeExpansionResult()

    /** TypeApplication expands to a VariantType */
    data class Variant(val variantType: MezzeType.VariantType) : TypeExpansionResult()

    /** TypeApplication is a newtype - single-constructor variant with payload */
    data class Newtype(
        val variantType: MezzeType.VariantType,
        val constructorName: String,
        val payloadType: MezzeType?
    ) : TypeExpansionResult()

    /** TypeApplication could not be expanded (unknown type) */
    object NotFound : TypeExpansionResult()
}

/**
 * Get the definition kind of a TypeApplication without expanding.
 *
 * This is a fast check that only looks up the TypeDeclaration and
 * checks its definition type, without doing full type parameter substitution.
 */
enum class TypeDefinitionKind {
    RECORD,
    VARIANT,
    NEWTYPE,
    UNKNOWN
}

internal fun getTypeDefinitionKind(
    typeApp: MezzeType.TypeApplication,
    typeDeclarations: Map<String, TypeDeclaration>
): TypeDefinitionKind {
    val typeDecl = typeDeclarations[typeApp.constructor] ?: return TypeDefinitionKind.UNKNOWN
    return when (typeDecl.definition) {
        is RecordDefinition -> TypeDefinitionKind.RECORD
        is VariantDefinition -> TypeDefinitionKind.VARIANT
        is NewtypeDefinition -> TypeDefinitionKind.NEWTYPE
    }
}

/**
 * Expand a TypeApplication to its underlying type in a principled way.
 *
 * This function first checks the TypeDeclaration's definition type,
 * then expands accordingly. This is more efficient than trying RecordType
 * first and then falling back to VariantType.
 *
 * @param typeApp The type application to expand
 * @param typeDeclarations Map of type declarations
 * @return A TypeExpansionResult indicating the expanded type
 */
internal fun expandTypeApplication(
    typeApp: MezzeType.TypeApplication,
    typeDeclarations: Map<String, TypeDeclaration>
): TypeExpansionResult {
    val typeDecl = typeDeclarations[typeApp.constructor] ?: return TypeExpansionResult.NotFound

    return when (val definition = typeDecl.definition) {
        is RecordDefinition -> {
            val expanded = expandTypeApplicationToRecord(typeApp, typeDeclarations)
            if (expanded != null) {
                TypeExpansionResult.Record(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is VariantDefinition -> {
            val expanded = expandTypeApplicationToVariant(typeApp, typeDeclarations)
            if (expanded != null) {
                TypeExpansionResult.Variant(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is NewtypeDefinition -> {
            val expanded = expandTypeApplicationToVariant(typeApp, typeDeclarations)
            if (expanded != null && expanded.constructors.size == 1) {
                val (constructorName, payloadType) = expanded.constructors.entries.first()
                TypeExpansionResult.Newtype(expanded, constructorName, payloadType)
            } else {
                TypeExpansionResult.NotFound
            }
        }
    }
}
