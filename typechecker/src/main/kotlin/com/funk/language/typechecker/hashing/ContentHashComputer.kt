package com.funk.language.typechecker.hashing

import com.funk.language.ast.*
import com.funk.language.ast.hashing.ASTHasher
import com.funk.language.ast.hashing.FunkHash

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
 * - API-sensitive: Parameter names (record fields) affect hash (Funk-specific)
 * - Metadata-invariant: Source locations, inferred types don't affect hash
 */
object ContentHashComputer {

    /**
     * Result of computing hashes for a program.
     */
    data class HashResult(
        val program: Program,
        val hashMap: Map<String, FunkHash>  // name -> hash for looking up
    )

    /**
     * Compute content hashes for all declarations in a program.
     *
     * Returns a new program with contentHash fields populated.
     */
    fun computeHashes(program: Program): HashResult {
        val hashMap = mutableMapOf<String, FunkHash>()

        val updatedItems = program.items.map { item ->
            when (item) {
                is LetBinding -> {
                    val hash = ASTHasher.hashLetBinding(item)
                    val name = if (item.isSimpleBinding) item.variableName else "pattern_binding"
                    hashMap[name] = hash
                    item.copy(contentHash = hash.toString())
                }

                is TypeDeclaration -> {
                    val hash = ASTHasher.hashTypeDeclaration(item)
                    hashMap[item.name] = hash
                    item.copy(contentHash = hash.toString())
                }

                is EffectDeclaration -> {
                    val hash = ASTHasher.hashEffectDeclaration(item)
                    hashMap[item.name] = hash
                    item.copy(contentHash = hash.toString())
                }

                is ImplDeclaration -> {
                    val hash = ASTHasher.hashImplDeclaration(item)
                    // Use performer name as key
                    hashMap[item.performerName] = hash
                    item.copy(contentHash = hash.toString())
                }

                is AbilityDeclaration -> {
                    val hash = ASTHasher.hashAbilityDeclaration(item)
                    hashMap[item.name] = hash
                    item.copy(contentHash = hash.toString())
                }

                is ImplAbilityDeclaration -> {
                    val hash = ASTHasher.hashImplAbilityDeclaration(item)
                    // Create a key that identifies the impl
                    val key = createAbilityImplKey(item)
                    hashMap[key] = hash
                    item.copy(contentHash = hash.toString())
                }

                is TopLevelExpression -> item  // No hash for top-level expressions
                is UseDeclaration -> item  // No hash for imports
                is PubUseDeclaration -> item  // No hash for re-exports
            }
        }

        return HashResult(
            program = Program(items = updatedItems),
            hashMap = hashMap
        )
    }

    /**
     * Create a lookup key for an ability implementation.
     * This mirrors the string-based approach but is only used for indexing.
     */
    private fun createAbilityImplKey(impl: ImplAbilityDeclaration): String {
        val targetTypesStr = impl.targetTypes.joinToString("$") { typeAnnotationToString(it) }
        return "${impl.qualifiedAbilityName}\$$targetTypesStr"
    }

    /**
     * Convert type annotation to string for key generation.
     * This is used for creating implementation reference keys that match
     * between SubstitutionApplier and ContentHashComputer.
     *
     * IMPORTANT: For Record and Variant types, we include structural information
     * (sorted field names for records, sorted constructor names for variants)
     * to distinguish between different record/variant structures.
     * This is critical for Encode/Decode ability resolution.
     */
    fun typeAnnotationToString(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name
            is TypeApplicationAnnotation -> {
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) annotation.constructor else "${annotation.constructor}_$args"
            }
            is FunctionTypeAnnotation -> "Function"
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
            is ArrayTypeAnnotation -> "Array_${typeAnnotationToString(annotation.elementType)}"
            is EffectRowAnnotation -> "EffectRow"
            is QualifiedTypeAnnotation -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) "${modulePart}_${annotation.typeName}" else "${modulePart}_${annotation.typeName}_$args"
            }
        }
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
        hashMap: Map<String, FunkHash>
    ): FunkHash? {
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
        hashMap: Map<String, FunkHash>
    ): FunkHash? {
        return hashMap[performerName]
    }

    // Note: updateResolvedAbilitiesWithHashes and buildImplRefToHashMap were removed.
    // Since content hashes are now computed in Phase 0.5 (before SubstitutionApplier),
    // and SubstitutionApplier sets implementationHash directly, we no longer need
    // a separate pass to update resolved abilities.
}
