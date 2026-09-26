package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.DeclKind
import com.mezze.language.store.DeclRow
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.SignatureIndex
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.solving.unify
import com.mezze.language.typechecker.util.TypeAnnotationConverter

/**
 * Find declarations by TYPE rather than by name.
 *
 * You know the shape of the thing you want — "something that turns a sequence of
 * `a` and a starting `b` into a `b`" — and not what it is called. Name search
 * cannot help: `fold`, `reduce`, `inject` and `aggregate` are all plausible
 * spellings of the same function, and a codebase picks one.
 *
 * ## How a candidate is judged
 *
 * By UNIFICATION, using the typechecker's own [unify]. Nothing here re-implements
 * type comparison: a search that decided matching for itself would drift from what
 * the compiler considers the same type, and then the search would be lying rather
 * than merely incomplete. Type variables are flexible on BOTH sides, so
 *
 *   - query `{ x: 'a } -> 'a` finds `{ x: Int } -> Int` (a concrete function is an
 *     instance of the shape asked for), and
 *   - query `{ x: Int } -> Int` finds `{ x: 'a } -> 'a` (a general function can do
 *     the specific job).
 *
 * Both directions are what a person means by "find me this type", which is why
 * the ranking below — not the filter — is what separates an exact hit from a
 * merely-unifiable one.
 *
 * ## Why the SQL pre-filter is only effects and constraints
 *
 * It is tempting to narrow candidates structurally in SQL — same arrow count, or
 * "mentions `Seq` somewhere". Both are UNSOUND once variables are involved, and
 * unsound here means silently missing the answer the index is holding:
 *
 *   - Arity: query `{ x: Int } -> 'r` unifies with `{ x: Int } -> { y: Int } -> Int`,
 *     because `'r` takes the inner arrow. Requiring equal arrow counts drops it.
 *   - Head symbols: query `{ x: Str } -> Str` unifies with `{ x: 'a } -> 'a`, which
 *     mentions no `Str` at all. Requiring a shared head drops it.
 *
 * Effects and constraints are different: they are named sets on the residual
 * arrow, and "at least these" is a subset test a join does exactly. So the filter
 * narrows on those, and unification decides the rest in memory. On a store of any
 * realistic size that is fast enough; when it stops being so, the fix is a sound
 * fingerprint, not a lossy one.
 */
object TypeSearch {

    /** How well a candidate matched — the ranking, best first. */
    enum class Quality {
        /** Identical up to variable naming: the query IS this signature. */
        EXACT,

        /** The candidate is more general; the query is an instance of it. */
        MORE_GENERAL,

        /** The candidate is more specific than the query asked for. */
        MORE_SPECIFIC,

        /** Unifiable, with both sides instantiated. */
        UNIFIABLE,
    }

    data class Hit(
        val decl: DeclRow,
        val quality: Quality,
        /** The candidate's own signature, rendered for display. */
        val signature: String?,
        /** How many variables had to be bound to make the match — the tie-break. */
        val bindings: Int,
    )

    /**
     * Parse [query] as a type and return matching declarations, best first.
     *
     * Throws [com.mezze.language.parser.ParseException] if [query] is not a type;
     * the caller shows the parser's own message, which points into what the user
     * typed.
     */
    fun search(store: MezzeStore, query: String, limit: Int = 25): List<Hit> =
        search(store, parseQuery(query), limit)

    /** Parse a query type. Separate so callers can report a parse error their own way. */
    fun parseQuery(query: String): MezzeType {
        val annotation = MezzeParser().parseTypeAnnotation(query)
        // The SAME converter the compiler uses for a written annotation. A search
        // that converted types its own way would answer questions about a type the
        // user did not write.
        return TypeAnnotationConverter.convert(annotation)
    }

    fun search(store: MezzeStore, query: MezzeType, limit: Int = 25): List<Hit> {
        val required = requirements(query)

        // Narrow by effects and constraints where we soundly can, and otherwise
        // consider every indexed `let`. `intersect` rather than union: a candidate
        // must satisfy EVERY named requirement, and an empty requirement set means
        // no narrowing at all.
        val candidates: Collection<DeclRow> = when {
            required.isEmpty() -> store.getDeclsByKind(DeclKind.LET)
            else -> required
                .map { req -> req.lookup(store).associateBy { it.hash } }
                .reduce { a, b -> a.filterKeys { it in b.keys } }
                .values
        }

        return candidates.asSequence()
            .mapNotNull { decl -> judge(store, decl, query) }
            .sortedWith(compareBy({ it.quality.ordinal }, { it.bindings }, { it.decl.declName }))
            .take(limit)
            .toList()
    }

    /** A named set on the residual arrow that a candidate must contain. */
    private sealed interface Requirement {
        fun lookup(store: MezzeStore): List<DeclRow>

        data class Effect(val name: String) : Requirement {
            override fun lookup(store: MezzeStore) = store.findDeclsByEffect(name)
        }

        data class Constraint(val name: String) : Requirement {
            override fun lookup(store: MezzeStore) = store.findDeclsByConstraint(name)
        }
    }

    private fun requirements(query: MezzeType): List<Requirement> {
        val fn = query as? MezzeType.FunctionType ?: return emptyList()
        val arrow = fn.residualArrow
        return buildList {
            arrow.effects?.allEffectNames?.forEach { add(Requirement.Effect(it)) }
            arrow.abilityConstraints.forEach { add(Requirement.Constraint(it.abilityName)) }
        }
    }

    private fun judge(store: MezzeStore, decl: DeclRow, query: MezzeType): Hit? {
        val rows = store.getSignature(decl.hash)
        if (rows.isEmpty) return null
        val candidate = SignatureIndex.rebuild(rows)

        val result = unify(query, candidate)
        if (result !is UnificationResult.Success) return null

        // WHICH side had to be instantiated is the ranking. A binding whose target
        // is itself a variable is a rename, not an instantiation — otherwise
        // `{x: 'a} -> 'a` searched against itself would rank below an exact hit on
        // a concrete signature.
        val substantive = result.substitution.mappings.filterValues { it !is MezzeType.VarType }
        val queryVars = collectVars(query)
        val candidateVars = collectVars(candidate)
        val queryBound = substantive.keys.any { it in queryVars }
        val candidateBound = substantive.keys.any { it in candidateVars }

        val quality = when {
            substantive.isEmpty() -> Quality.EXACT
            // Only the candidate's variables were filled in: it is the more
            // general signature and the query is one of its instances.
            candidateBound && !queryBound -> Quality.MORE_GENERAL
            queryBound && !candidateBound -> Quality.MORE_SPECIFIC
            else -> Quality.UNIFIABLE
        }

        return Hit(
            decl = decl,
            quality = quality,
            signature = SignatureIndex.render(rows),
            bindings = substantive.size,
        )
    }

    private fun collectVars(type: MezzeType): Set<com.mezze.language.ast.TypeVar> {
        val out = mutableSetOf<com.mezze.language.ast.TypeVar>()
        fun walk(t: MezzeType) {
            if (t is MezzeType.VarType) out.add(t.variable)
            t.anyChild { child -> walk(child); false }
        }
        walk(type)
        return out
    }
}
