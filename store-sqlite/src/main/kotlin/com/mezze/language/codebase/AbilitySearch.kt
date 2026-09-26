package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.SignatureIndex
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.solving.unify
import com.mezze.language.typedast.Hash

/**
 * The two questions people ask about abilities.
 *
 *   - "What implements `Ord`?" — before writing a `SortedSet`, you want to know
 *     whether your element type is already ordered.
 *   - "What does `Deque 'a` implement?" — the reverse, and the one that tells you
 *     what you can do with a value.
 *
 * Both were unanswerable, though every byte needed was stored. `ability_impls`
 * could say "Ord has 16 impls" and name none of them: its target key is a
 * STRUCTURAL type hash, which deliberately joins to no declaration (`Int` has no
 * declaration to join to), and an impl's own decl name is synthetic —
 * `impl_01a38979371c`. The missing piece was not a query but the target types'
 * STRUCTURE, which is now indexed under those same hashes.
 *
 * Structure, not names, and that is what makes the reverse direction work at all:
 * `impl Ord for Deque 'a` is generic, so asking whether `Deque Int` implements
 * `Ord` is a UNIFICATION question. Comparing type names would answer it wrongly in
 * both directions — missing `Deque Int` against `Deque 'a`, and matching one
 * module's `Color` against another's.
 */
object AbilitySearch {

    /** One `impl A for T…`, with its targets resolved to real types. */
    data class Impl(
        val implHash: Hash,
        val module: String?,
        /** The types this impl is FOR, in written order: class parameters, then the receiver. */
        val targets: List<MezzeType>,
    ) {
        /**
         * The type this impl is actually FOR.
         *
         * The LAST target, not the first. An ability writes its class parameters
         * BEFORE `for` and its receiver after — `ability FromJson 'a for 'in`,
         * `impl FromJson 'a for Json` — so the receiver is last and the leading
         * entries are class parameters.
         *
         * Reading the first target instead is a WRONG ANSWER, not an imprecise one:
         * for `impl FromJson 'a for Json` it tests `'a`, a bare variable that
         * unifies with anything, so every type in the codebase appeared to implement
         * `FromJson`. Measured: it claimed `Deque Int` implements 19 abilities where
         * the true count is 12.
         */
        val receiver: MezzeType? get() = targets.lastOrNull()

        /** The class parameters — everything before the receiver. */
        val classParams: List<MezzeType> get() = targets.dropLast(1)

        /** `Deque 'a`, or `Json` with class params shown separately. */
        val rendered: String
            get() = receiver?.let { com.mezze.language.ast.SignatureNormalizer.render(it) } ?: ""

        /** `FromJson 'a for Json` — how the impl was written. */
        fun renderedWithParams(abilityName: String): String {
            val params = classParams.joinToString(" ") { com.mezze.language.ast.SignatureNormalizer.render(it) }
            val head = if (params.isEmpty()) abilityName else "$abilityName $params"
            return "$head for ${rendered.ifEmpty { "?" }}"
        }
    }

    /**
     * Every impl of the ability declared as [abilityName].
     *
     * An impl whose targets are not indexed is still returned, with an empty
     * [Impl.targets] — it exists, and reporting "16 impls, 2 of which I cannot
     * describe" beats both silently dropping them and claiming there are 14.
     */
    fun implsOf(store: MezzeStore, abilityName: String): List<Impl> =
        store.findAbilityImplsByName(abilityName).map { row ->
            Impl(
                implHash = row.hash,
                module = row.module,
                targets = store.getImplTargets(row.hash).mapNotNull { targetHash ->
                    store.getTypeRows(targetHash).takeIf { !it.isEmpty }?.let { SignatureIndex.rebuild(it) }
                },
            )
        }.sortedBy { it.rendered }

    /** Does [type] implement the ability declared as [abilityName]? */
    fun implements(store: MezzeStore, abilityName: String, type: MezzeType): Impl? =
        implsOf(store, abilityName).firstOrNull { impl ->
            impl.receiver?.let { target -> unifies(type, target) } == true
        }

    /**
     * Every ability implemented for a type unifying with [type].
     *
     * Reads the impl table rather than a per-type index: there is no "abilities of
     * T" list anywhere, because an impl is attached to the ability, and building a
     * reverse index would be a second representation of the same fact.
     */
    fun abilitiesOf(store: MezzeStore, type: MezzeType): List<Pair<String, Impl>> =
        store.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY)
            .distinctBy { it.declName }
            .mapNotNull { abilityDecl ->
                implements(store, abilityDecl.declName, type)?.let { abilityDecl.declName to it }
            }
            .sortedBy { it.first }

    /**
     * Whether a value of type [candidate] satisfies a demand for [target].
     *
     * Argument order matches the convention `unify` documents — value first, demand
     * second — which is the same ordering the type search had to get right: written
     * the other way round, a generic impl target would have to satisfy the
     * caller's concrete type instead of the reverse.
     */
    private fun unifies(candidate: MezzeType, target: MezzeType): Boolean =
        unify(candidate, target) is UnificationResult.Success
}
