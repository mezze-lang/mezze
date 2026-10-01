package com.mezze.language.typechecker.util

import com.mezze.language.ast.isTypeVarName
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
            implDecl.targetTypes
        } else {
            implDecl.allTypeArgs
        }
        val targetTypes = annotations.map { typeNameOf(it) }

        val typeVars = if (implDecl.isInherent) emptySet() else {
            implDecl.forallVars.toSet()
        }

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
            operationConstraints = operationConstraints,
            derived = implDecl.derived
        )
    }

        internal fun typeNameOf(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name.removePrefix("'")
            is ParsedTypeApp -> {
                val allArgsAreTypeVars = annotation.args.all { arg ->
                    arg is ParsedNamedType && arg.name.isTypeVarName()
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
            // A GENERIC sequence impl registers under the bare constructor, exactly
            // as the `ParsedTypeApp` arm above does: `impl Eq for ['a]` is `Seq`, not
            // `Seq_a`.
            //
            // This arm used to mangle unconditionally, so every `impl X for ['a]` in
            // the stdlib was keyed by its own TYPE PARAMETER's spelling — `Seq_a`,
            // where `a` is not a type and can never be a lookup's answer. Harmless
            // for the receiver-TAKING path, which recovers the receiver from the
            // argument and never consults this name, and fatal for a receiver-less
            // operation, where the produced type is the ONLY thing that can pick an
            // impl: `let xs: [Int] = Arbitrary.arbitrary {}` reported "No
            // `impl Arbitrary for Seq` is in scope" with the impl sitting right there.
            is ParsedSeqType ->
                if ((annotation.elementType as? ParsedNamedType)?.name?.isTypeVarName() == true) "Seq"
                else "Seq_${typeNameOf(annotation.elementType)}"
            is ParsedQualifiedType -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val argNames = annotation.args.map { typeNameOf(it) }
                if (argNames.isEmpty()) "${modulePart}_${annotation.typeName}"
                else "${modulePart}_${annotation.typeName}_${argNames.joinToString("_")}"
            }
        }
    }
}
