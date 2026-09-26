package com.mezze.language.codebase

import com.mezze.language.store.DeclRow
import com.mezze.language.store.EdgeKind
import com.mezze.language.store.MezzeStore
import com.mezze.language.typedast.Hash

/**
 * What a function ACTUALLY performs, as opposed to what its own signature declares.
 *
 * Mezze already tracks effects precisely, and the index already stores each
 * declaration's residual row. The gap is one level up: a function whose signature
 * says `<Log>` may reach `FileSystem` three calls down, and no single row says so.
 * Unioning declared rows across the call graph is what turns "this function logs"
 * into "this request handler touches the filesystem, the network and the clock".
 *
 * Two things make that worth having rather than merely tidy:
 *
 *  - **Running something.** Whether a declaration can run with no handlers is
 *    decided by its residual row (`mez sample` refuses on exactly this). The
 *    transitive answer says WHICH handler to install, and where the demand comes
 *    from — a declared effect three hops away is the reason your entry point needs
 *    a handler, and the direct signature never mentions it.
 *  - **Reviewing a change.** "This used to be pure and now reaches `Network`" is a
 *    real review finding that nothing else surfaces.
 *
 * Every attribution names the declaration that DECLARES the effect and its distance,
 * because "your handler reaches `FileSystem`" is only actionable with "…through
 * `read_config`, two calls away".
 */
object EffectReach {

    /** One effect, and the nearest declaration responsible for it. */
    data class Attribution(
        val effect: String,
        /** The declaration whose own signature declares this effect. */
        val source: DeclRow?,
        /** Hops from the starting declaration; 0 means it declares the effect itself. */
        val depth: Int,
    )

    /**
     * Every effect reachable from [start], attributed to the nearest declaration
     * that declares it.
     *
     * Follows CALLS and REFERENCES: a function that merely references another can
     * still hand it somewhere it gets run, so excluding references would under-report
     * — and under-reporting effects is the direction that makes a program look safer
     * than it is.
     */
    fun of(store: MezzeStore, start: Hash, maxDepth: Int? = null): List<Attribution> {
        val depths = mutableMapOf(start to 0)
        store.transitiveEdgesFrom(
            start,
            kinds = setOf(EdgeKind.CALLS, EdgeKind.REFERENCES),
            maxDepth = maxDepth,
        ).forEach { depths[it.hash] = it.depth }

        val declared = store.getResidualEffectsFor(depths.keys)

        // Nearest wins: the same effect reached at depth 1 and depth 4 is best
        // explained by the closer declaration, which is the one a reader can act on.
        val best = mutableMapOf<String, Pair<Hash, Int>>()
        for ((hash, effects) in declared) {
            val depth = depths[hash] ?: continue
            for (effect in effects) {
                val current = best[effect]
                if (current == null || depth < current.second) best[effect] = hash to depth
            }
        }

        return best.entries
            .map { (effect, source) ->
                Attribution(effect, store.findDeclByHash(source.first), source.second)
            }
            .sortedWith(compareBy({ it.depth }, { it.effect }))
    }

    /**
     * Effects reachable from [start] that its OWN signature does not mention.
     *
     * The interesting subset: an effect in the declared row is visible in the
     * signature, so a reader already knows. These are the ones only a traversal
     * finds. Note this is not necessarily a defect — an effect is often HANDLED in
     * between, which is what the `*` performed marker records — so the report is
     * "reached", not "leaked".
     */
    fun undeclared(store: MezzeStore, start: Hash, maxDepth: Int? = null): List<Attribution> {
        val own = store.getResidualEffectsFor(listOf(start))[start].orEmpty()
        return of(store, start, maxDepth).filter { it.effect !in own }
    }
}
