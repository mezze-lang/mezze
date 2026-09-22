package com.mezze.language.parsedast

import com.mezze.language.ast.isTypeVarName

/**
 * Generic traversal for [ParsedTypeAnnotation], the counterpart to
 * [ParsedExpr.children] for the type-annotation hierarchy.
 *
 * Every `when` here is exhaustive over the seven variants, so adding an eighth
 * is a compile error in this file until it is handled — which is the point.
 * Hand-rolled recursions over annotations were the norm before this file and
 * they disagreed with each other: a rewriter that forgot a position silently
 * returned the sub-tree unchanged, which reads as "nothing to substitute"
 * rather than "I did not look".
 *
 * ## The positions hand-rolled walks miss
 *
 * A [ParsedFunctionType] nests annotations in FOUR places, not two:
 *
 * ```
 * { x: 'a } with Container 'c having 'item = 'a -> Str <Exception having 'error = 'a>
 *   ^param        ^typeArgs       ^assocTypeBindings  ^return ^effects[].associatedTypes
 * ```
 *
 * The effect row and the `with` clause carry real annotations — `'a` appears
 * in both above — and the existing recursions almost all stop at `paramType`
 * and `returnType`. [children] and [transformChildren] descend into all four.
 *
 * ## What is NOT a child
 *
 * [ParsedEffectRow] is deliberately not a [ParsedTypeAnnotation] (it is a row,
 * not a type), so it never appears as a child in its own right; only the
 * annotations *inside* its `having` bindings do. Code that branches on
 * `is ParsedEffectRow` inside a `when` over [ParsedTypeAnnotation] is dead.
 */

/**
 * The direct sub-annotations of this annotation, in source order.
 *
 * For a function type that order follows the surface syntax
 * `{ params } with Constraints -> Return <Effects>`, so a positional consumer
 * (node-at-position, "first reference wins") sees them the way they were written.
 */
fun ParsedTypeAnnotation.children(): List<ParsedTypeAnnotation> = when (this) {
    is ParsedNamedType -> emptyList()
    is ParsedQualifiedType -> args
    is ParsedTypeApp -> args
    is ParsedRecordType -> entries.map { it.type }
    is ParsedVariantType -> constructors.mapNotNull { it.dataType }
    is ParsedSeqType -> listOf(elementType)
    is ParsedFunctionType ->
        listOf(paramType) +
            abilityConstraints.flatMap { it.typeArgs + it.associatedTypeBindings.values } +
            returnType +
            (effects?.effectAnnotations?.flatMap { it.associatedTypes.values } ?: emptyList())
}

/**
 * Rebuild this annotation with [f] applied to each direct child, leaving the
 * node's own shape alone. A node with no children returns itself unchanged.
 *
 * This is the shallow step: pair it with recursion (or use [transformDeep]) to
 * rewrite a whole tree. Substitution passes want [transformDeep].
 */
fun ParsedTypeAnnotation.transformChildren(
    f: (ParsedTypeAnnotation) -> ParsedTypeAnnotation
): ParsedTypeAnnotation = when (this) {
    is ParsedNamedType -> this
    is ParsedQualifiedType -> copy(args = args.map(f))
    is ParsedTypeApp -> copy(args = args.map(f))
    is ParsedRecordType -> copy(entries = entries.map { it.copy(type = f(it.type)) })
    is ParsedVariantType -> copy(
        constructors = constructors.map { ctor -> ctor.copy(dataType = ctor.dataType?.let(f)) }
    )
    is ParsedSeqType -> copy(elementType = f(elementType))
    is ParsedFunctionType -> copy(
        paramType = f(paramType),
        returnType = f(returnType),
        effects = effects?.copy(
            effectAnnotations = effects.effectAnnotations.map { ann ->
                ann.copy(associatedTypes = ann.associatedTypes.mapValues { (_, t) -> f(t) })
            }
        ),
        abilityConstraints = abilityConstraints.map { c ->
            c.copy(
                typeArgs = c.typeArgs.map(f),
                associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, t) -> f(t) }
            )
        }
    )
}

/**
 * Bottom-up rewrite: children are transformed first, then [f] sees the rebuilt
 * node. A substitution written as `transformDeep { if (it is ParsedNamedType) … }`
 * reaches every position, including the effect-row and `with`-clause bindings.
 */
fun ParsedTypeAnnotation.transformDeep(
    f: (ParsedTypeAnnotation) -> ParsedTypeAnnotation
): ParsedTypeAnnotation = f(transformChildren { it.transformDeep(f) })

/** This annotation and every annotation beneath it, parents before children. */
fun ParsedTypeAnnotation.selfAndDescendants(): Sequence<ParsedTypeAnnotation> = sequence {
    yield(this@selfAndDescendants)
    for (child in children()) yieldAll(child.selfAndDescendants())
}

/** True when [predicate] holds anywhere in this annotation, including at the root. */
fun ParsedTypeAnnotation.anyDeep(predicate: (ParsedTypeAnnotation) -> Boolean): Boolean =
    selfAndDescendants().any(predicate)

/**
 * Replace type-variable references by name throughout this annotation.
 *
 * [substitution] is keyed by type-variable name; both spellings are accepted
 * because different code paths key with and without the leading tick (a
 * parametric alias records `'a`, an impl's type-parameter map records `a`).
 *
 * Substitution is on the PARSED tree only — no conversion, no unification —
 * so it is the right tool for "inline this alias's arguments" and "specialise
 * this ability-op signature to the impl's type arguments", and the wrong tool
 * for anything that needs type identity.
 */
fun ParsedTypeAnnotation.substituteTypeVarNames(
    substitution: Map<String, ParsedTypeAnnotation>
): ParsedTypeAnnotation {
    if (substitution.isEmpty()) return this
    return transformDeep { annotation ->
        if (annotation !is ParsedNamedType) return@transformDeep annotation
        val stripped = annotation.name.removePrefix("'")
        substitution[annotation.name] ?: substitution[stripped] ?: substitution["'$stripped"] ?: annotation
    }
}

/**
 * True when a type variable appears anywhere in this annotation, ignoring the
 * ones named in [allowed].
 *
 * Deep by construction, which the hand-rolled predicates were not: one stopped
 * at the arrow and so answered "no type variables" for
 * `{ x: Int } -> Str <Exception having 'error = 'a>`.
 */
fun ParsedTypeAnnotation.hasTypeVarsOutside(allowed: Set<String> = emptySet()): Boolean =
    anyDeep { it is ParsedNamedType && it.name.isTypeVarName() && it.name !in allowed }
