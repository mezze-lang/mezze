package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.*

/**
 * Recursion in a type declaration must have somewhere to STOP.
 *
 * Two refusals, both at the declaration rather than at the use site that is
 * surprised by it:
 *
 *   1. A cycle of pure ALIASES — `type Loop = Loop`, or `type A = B` with
 *      `type B = A`. A `type` is a spelling, so using it substitutes its
 *      definition; a cycle never terminates, so there is nothing the name can
 *      stand for.
 *
 *   2. A recursive type with NO BASE CASE — `type Endless = { next: Endless }`,
 *      or a variant whose every arm recurses. The declaration is well formed and
 *      the type is UNINHABITED: no value of it can ever be built.
 *
 * Both were accepted before. The second is the quieter one: the author only
 * found out at construction, as a type mismatch pointing at the innermost
 * literal (`Field 't' type mismatch: expected Int, got Endless`) — an error far
 * from the cause and about the wrong thing.
 *
 * MODELLED ON ROC, which states the rule most sharply: recursion is allowed only
 * behind a tag union with at least one non-recursive variant. Rust (`E0391`),
 * Haskell ("Cycle in type synonym declarations") and Elm all refuse cyclic
 * aliases too, and each points at its nominal construct, because a constructor
 * is what supplies the indirection. Here that generalises slightly: an escape is
 * a variant arm that does not recurse, OR a sequence, which the empty sequence
 * inhabits whatever its element type.
 *
 * What still works, and the diagnostics say so explicitly so that "cycle" is not
 * read as "no recursion":
 *
 *     type Tree = | Leaf, Node { l: Tree, r: Tree } |   # a non-recursive arm
 *     type Node = { n: Int, next: Option Node }         # Option's None
 *     type Tree = { kids: [Tree] }                      # the empty seq
 */
class RecursiveTypeValidator {

    fun validate(module: ParsedModule): List<ValidationError> {
        val decls = module.decls.filterIsInstance<ParsedTypeDecl>()
        if (decls.isEmpty()) return emptyList()
        val byName = decls.associateBy { it.name }

        val errors = mutableListOf<ValidationError>()

        // (1) alias cycles. Reported per CYCLE, not per participant, so
        // `type A = B` / `type B = A` is one error naming the path and not two
        // errors each naming the other.
        val reported = mutableSetOf<String>()
        for (decl in decls) {
            if (decl.name in reported) continue
            val path = aliasCycleFrom(decl.name, byName) ?: continue
            reported.addAll(path)
            errors.add(ValidationError.CyclicTypeAlias(path, decl.loc))
        }

        // (2) inhabitedness, as a fixpoint. A type is inhabited once SOME path
        // through it terminates; iterate until nothing new becomes inhabited,
        // and whatever is left cannot be built. Aliases already reported as
        // cyclic are skipped — they have a better error.
        val inhabited = inhabitedTypes(byName)
        for (decl in decls) {
            if (decl.name in reported) continue
            if (decl.name in inhabited) continue
            errors.add(ValidationError.UninhabitedType(decl.name, escapeHintFor(decl), decl.loc))
        }
        return errors
    }

    /**
     * The alias cycle reachable from [start], as the path that closes it, or null.
     *
     * Follows ONLY alias-to-named-alias links. A hop through a record, variant or
     * sequence is not followed: that is indirection, which is exactly what makes
     * recursion legitimate, and is question (2)'s business instead.
     */
    private fun aliasCycleFrom(
        start: String,
        byName: Map<String, ParsedTypeDecl>,
    ): List<String>? {
        val path = mutableListOf<String>()
        var cur = start
        while (true) {
            if (cur in path) {
                // Return the cycle itself, dropping any lead-in: `A → B → C → B`
                // is a cycle in B and C, and naming A would misdirect.
                return path.subList(path.indexOf(cur), path.size) + cur
            }
            path.add(cur)
            val def = byName[cur]?.definition as? ParsedTypeAliasDef ?: return null
            val next = (def.aliasedType as? ParsedNamedType)?.name ?: return null
            if (next !in byName) return null
            cur = next
        }
    }

    /** Names that can be built, by fixpoint over the declarations. */
    private fun inhabitedTypes(byName: Map<String, ParsedTypeDecl>): Set<String> {
        val inhabited = mutableSetOf<String>()
        var changed = true
        while (changed) {
            changed = false
            for ((name, decl) in byName) {
                if (name in inhabited) continue
                if (isInhabited(decl.definition, byName, inhabited)) {
                    inhabited.add(name)
                    changed = true
                }
            }
        }
        return inhabited
    }

    private fun isInhabited(
        def: ParsedTypeDef,
        byName: Map<String, ParsedTypeDecl>,
        inhabited: Set<String>,
    ): Boolean = when (def) {
        // SOME arm must be buildable. A nullary arm is buildable outright, which
        // is the base case Roc's rule is named for.
        is ParsedVariantDef -> def.isOpen || def.constructors.any { ctor ->
            ctor.dataType?.let { annInhabited(it, byName, inhabited) } ?: true
        }
        // EVERY field must be buildable — a record is a product.
        is ParsedRecordDef -> def.fields.all { annInhabited(it.type, byName, inhabited) }
        // A newtype is inhabited exactly when its payload is.
        is ParsedNewtypeDef -> annInhabited(def.wrappedType, byName, inhabited)
        is ParsedTypeAliasDef -> annInhabited(def.aliasedType, byName, inhabited)
    }

    private fun annInhabited(
        ann: ParsedTypeAnnotation,
        byName: Map<String, ParsedTypeDecl>,
        inhabited: Set<String>,
    ): Boolean = when (ann) {
        // A declared name counts only once it is KNOWN inhabited, which is what
        // makes this a fixpoint rather than a walk: a cycle never becomes true.
        is ParsedNamedType -> if (ann.name in byName) ann.name in inhabited else true
        // The EMPTY sequence inhabits `[T]` whatever `T` is, so a seq is always an
        // escape — `type Tree = { kids: [Tree] }` is a real type with a real value.
        is ParsedSeqType -> true
        is ParsedRecordType -> ann.entries.all { annInhabited(it.type, byName, inhabited) }
        is ParsedVariantType -> ann.isOpen || ann.constructors.any { c ->
            c.dataType?.let { annInhabited(it, byName, inhabited) } ?: true
        }
        is ParsedTypeApp ->
            // An applied name is inhabited when its HEAD is: `Option Node` is
            // inhabited by `None` regardless of `Node`, which is what makes the
            // `next: Option Node` idiom work.
            if (ann.constructor in byName) ann.constructor in inhabited else true
        // A function type is inhabited by any lambda of that shape; a primitive
        // and a type variable by their own values.
        else -> true
    }

    /** The escape this declaration is missing, phrased for its own shape. */
    private fun escapeHintFor(decl: ParsedTypeDecl): String = when (decl.definition) {
        is ParsedVariantDef ->
            "add an arm that does not recurse, e.g. `| Done, … |` — one arm that can be " +
                "built is what ends the chain"
        is ParsedRecordDef, is ParsedNewtypeDef ->
            "make the recursive field optional (`Option ${decl.name}`) or a sequence " +
                "(`[${decl.name}]`) — both can be empty, which ends the chain"
        is ParsedTypeAliasDef ->
            "the recursion needs to pass through a field: " +
                "`type ${decl.name} = { next: Option ${decl.name} }`"
    }
}
