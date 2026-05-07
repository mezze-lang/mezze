package com.mezze.language.typechecker.util

import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typedast.Hash

/**
 * Single source of truth for building `TypingContext.AbilityImplInfo` from
 * a `ParsedImplAbilityDecl`. Five different code paths used to construct
 * this record by hand, each subtly different (see #307 for the audit):
 *
 * - `targetTypes` was sometimes `getTypeName(...)` (synthetic key form like
 *   "Maybe_Int"), sometimes `it.toString()` (source form like "Maybe Int").
 *   `targetTypes` is documented as the synthetic-key form — the source-form
 *   sites were buggy.
 * - `typeVars` was computed in 3 of 5 paths, defaulted empty in others.
 * - `operationConstraints` was computed only in the local-named-impl path;
 *   imported impls silently dropped their per-op constraints.
 * - `abilityDeclHash` lookup form differed across import contexts.
 *
 * This helper unifies all of that: pass an `implDecl` + the hash and the
 * caller's lookup of `abilityDeclHash`, get a consistent `AbilityImplInfo`.
 */
internal object AbilityImplFactory {

    /**
     * Build an `AbilityImplInfo` from a `ParsedImplAbilityDecl`.
     *
     * @param implDecl the parsed impl declaration. Must have `contentHash` populated.
     * @param abilityDeclHash content hash of the ABILITY decl (not the impl).
     *        Pass null only for inherent impls (where `implDecl.isInherent` is true).
     *        Caller is responsible for the lookup since it depends on which
     *        scope (local decls, imported, prelude) the ability lives in.
     * @param sourceModule source module identifier for diagnostics. Defaults null.
     */
    internal fun build(
        implDecl: ParsedImplAbilityDecl,
        abilityDeclHash: Hash?,
        sourceModule: String? = null
    ): TypingContext.AbilityImplInfo {
        val implKey = implDecl.contentHash
            ?: error("ParsedImplAbilityDecl.contentHash not populated for " +
                "${implDecl.abilityName ?: "inherent"} ${implDecl.targetTypes}. " +
                "Hash must be computed before AbilityImplFactory.build is called.")

        val annotations: List<ParsedTypeAnnotation> = if (implDecl.isInherent) {
            // Inherent impls only have targetTypes (no ability type args).
            implDecl.targetTypes
        } else {
            // Named impls flatten abilityTypeArgs (concrete only) + targetTypes
            // via the convenience accessor. Type vars in abilityTypeArgs are
            // quantifiers, not target-shape information, so they're filtered out.
            implDecl.allTypeArgs
        }
        val targetTypes = annotations.map { typeNameOf(it) }

        // Type variables from generic impl header (e.g. 'a from `impl Eq 'a for ['a]`).
        // Only ParsedNamedType starting with `'` is a real type var; concrete
        // type names in abilityTypeArgs (e.g. `Str` in `impl Into Str for Int`)
        // are not.
        val typeVars = if (implDecl.isInherent) emptySet() else {
            implDecl.abilityTypeArgs
                .filterIsInstance<ParsedNamedType>()
                .filter { it.name.startsWith("'") }
                .map { it.name }
                .toSet()
        }

        // Method-level constraints — collected from each operation's `with` clause.
        // Inherent impls don't have ability-level constraints, but they CAN
        // have per-op constraints (e.g. `impl Seq where { sort = ... with Ord 'a }`).
        val operationConstraints = implDecl.operations.flatMap { it.abilityConstraints }

        return TypingContext.AbilityImplInfo(
            abilityName = implDecl.abilityName,
            abilityDeclHash = abilityDeclHash,
            targetTypes = targetTypes,
            targetTypeAnnotations = annotations,
            associatedTypeBindings = implDecl.associatedTypeBindings,
            sourceModule = sourceModule,
            contentHash = implKey,
            typeVars = typeVars,
            operationConstraints = operationConstraints
        )
    }

    /**
     * Convert a parsed type annotation to the synthetic-key form used by
     * `AbilityImplInfo.targetTypes`. Examples:
     *
     *   Int          → "Int"
     *   Maybe Int    → "Maybe_Int"
     *   ['a]         → "Seq_a"
     *   std::str::Str → "std_str_Str"
     *
     * Distinct from `toString()` (which produces source form like "Maybe Int")
     * and from `prettyPrintType` (display form). This is for impl lookup keys
     * only — never user-facing.
     */
    internal fun typeNameOf(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name.removePrefix("'")
            is ParsedTypeApp -> {
                val allArgsAreTypeVars = annotation.args.all { arg ->
                    arg is ParsedNamedType && (arg.name.startsWith("'") || arg.name.first().isLowerCase())
                }
                if (allArgsAreTypeVars) {
                    annotation.constructor
                } else {
                    val argNames = annotation.args.map { typeNameOf(it) }
                    if (argNames.isEmpty()) annotation.constructor
                    else "${annotation.constructor}_${argNames.joinToString("_")}"
                }
            }
            is ParsedFunctionType -> "Function"
            is ParsedRecordType -> "Record"
            is ParsedVariantType -> "Variant"
            is ParsedSeqType -> "Seq_${typeNameOf(annotation.elementType)}"
            is ParsedQualifiedType -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val argNames = annotation.args.map { typeNameOf(it) }
                if (argNames.isEmpty()) "${modulePart}_${annotation.typeName}"
                else "${modulePart}_${annotation.typeName}_${argNames.joinToString("_")}"
            }
        }
    }
}
