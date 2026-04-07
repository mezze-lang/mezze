package com.mezze.language.typechecker.hashing

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.ASTHasher
import com.mezze.language.ast.hashing.MezzeHash

/**
 * Computes content-addressable hashes for all declarations in a program.
 *
 * This is invoked by the TypeChecker after substitution is applied (Phase 4)
 * and before validation (Phase 5). At this point:
 * - All types are inferred
 * - All ability/effect references are resolved
 * - The AST structure is finalized
 *
 * ## Hash Usage
 *
 * Content hashes enable:
 * 1. **Caching**: Skip re-processing if hash hasn't changed
 * 2. **Reference**: Identify implementations by hash instead of mangled names
 * 3. **Incremental Compilation**: Only recompile changed definitions
 *
 * ## Hash Properties
 *
 * - Deterministic: Same code structure = same hash
 * - Alpha-invariant: Renaming internal bindings doesn't change hash
 * - API-sensitive: Parameter names (record fields) affect hash (Mezze-specific)
 * - Metadata-invariant: Source locations, inferred types don't affect hash
 */
object ContentHashComputer {

    private val typeStringCache = object : LinkedHashMap<Int, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > 1000
    }
    private val annotationStringCache = object : LinkedHashMap<Int, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > 1000
    }
    fun clearCaches() { typeStringCache.clear(); annotationStringCache.clear() }

    /**
     * Convert type annotation to string with caching for complex types.
     */
    fun typeAnnotationToString(annotation: TypeAnnotation): String {
        // Fast path for simple identifiers
        if (annotation is IdentifierTypeAnnotation) {
            return annotation.name
        }
        if (annotation is FunctionTypeAnnotation) {
            return "Function"
        }
        if (annotation is EffectRowAnnotation) {
            return "EffectRow"
        }

        // Complex types - use cache
        val key = System.identityHashCode(annotation)
        annotationStringCache[key]?.let { return it }

        val result = when (annotation) {
            is TypeApplicationAnnotation -> {
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) annotation.constructor else "${annotation.constructor}_$args"
            }
            is RecordTypeAnnotation -> {
                // Include sorted field names to distinguish different record structures
                val fieldNames = annotation.entries.map { it.name }.sorted().joinToString("_")
                if (fieldNames.isEmpty()) "Record" else "Record_$fieldNames"
            }
            is VariantTypeAnnotation -> {
                // Include sorted constructor names to distinguish different variant structures
                val constructorNames = annotation.constructors.map { it.name }.sorted().joinToString("_")
                if (constructorNames.isEmpty()) "Variant" else "Variant_$constructorNames"
            }
            is SeqTypeAnnotation -> "Array_${typeAnnotationToString(annotation.elementType)}"
            is QualifiedTypeAnnotation -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) "${modulePart}_${annotation.typeName}" else "${modulePart}_${annotation.typeName}_$args"
            }
            else -> annotation.toString()
        }

        annotationStringCache[key] = result
        return result
    }

    /**
     * Convert a MezzeType to a canonical string for ability impl lookup keys.
     *
     * This is the MezzeType equivalent of typeAnnotationToString.
     * Used during ability resolution when we have inferred types, not annotations.
     *
     * IMPORTANT: Must produce consistent keys that match typeAnnotationToString
     * for the same underlying type structure.
     *
     * Uses LRU caching for complex types to avoid repeated computation.
     */
    fun mezzeTypeToString(type: MezzeType): String {
        // Fast path for primitive types using centralized registry (no caching needed)
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> type.variable.name  // Keep 'a format for type variables
            is MezzeType.FunctionType -> "Function"
            // Complex types - use cache
            else -> mezzeTypeToStringCached(type)
        }
    }

    /**
     * Cached implementation for complex types.
     */
    private fun mezzeTypeToStringCached(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeStringCache[key]?.let { return it }

        val result = when (type) {
            is MezzeType.TypeApplication -> {
                val args = type.args.joinToString("_") { mezzeTypeToString(it) }
                if (args.isEmpty()) type.constructor else "${type.constructor}_$args"
            }
            is MezzeType.RecordType -> {
                val fieldNames = type.fields.keys.sorted().joinToString("_")
                if (fieldNames.isEmpty()) "Record" else "Record_$fieldNames"
            }
            is MezzeType.VariantType -> {
                val constructorNames = type.constructors.keys.sorted().joinToString("_")
                if (constructorNames.isEmpty()) "Variant" else "Variant_$constructorNames"
            }
            is MezzeType.SeqType -> "Array_${mezzeTypeToString(type.elementType)}"
            is MezzeType.ListType -> "List_${mezzeTypeToString(type.elementType)}"
            is MezzeType.SetType -> "Set_${mezzeTypeToString(type.elementType)}"
            is MezzeType.MapType -> "Map_${mezzeTypeToString(type.keyType)}_${mezzeTypeToString(type.valueType)}"
            is MezzeType.SortedMapType -> "SortedMap_${mezzeTypeToString(type.keyType)}_${mezzeTypeToString(type.valueType)}"
            is MezzeType.SortedSetType -> "SortedSet_${mezzeTypeToString(type.elementType)}"
            is MezzeType.SuspendedType -> mezzeTypeToString(type.valueType)
            else -> type.toString()
        }

        typeStringCache[key] = result
        return result
    }

    /**
     * Look up the content hash for an ability implementation.
     *
     * @param abilityName The ability name (e.g., "NumAdd")
     * @param targetTypes The target types (e.g., [IntType])
     * @param hashMap The hash map from computeHashes
     * @return The content hash if found
     */
    fun lookupAbilityImplHash(
        abilityName: String,
        targetTypes: List<TypeAnnotation>,
        hashMap: Map<String, MezzeHash>
    ): MezzeHash? {
        val targetTypesStr = targetTypes.joinToString("$") { typeAnnotationToString(it) }
        val key = "$abilityName\$$targetTypesStr"
        return hashMap[key]
    }

    /**
     * Look up the content hash for an effect handler implementation.
     *
     * @param performerName The performer/handler name
     * @param hashMap The hash map from computeHashes
     * @return The content hash if found
     */
    fun lookupEffectHandlerHash(
        performerName: String,
        hashMap: Map<String, MezzeHash>
    ): MezzeHash? {
        return hashMap[performerName]
    }

    // Note: updateResolvedAbilitiesWithHashes and buildImplRefToHashMap were removed.
    // Content hashes are computed in Phase 0.5 before validation.
}
