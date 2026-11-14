package com.mezze.language.typechecker.context

import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.typedast.Hash
import java.util.concurrent.ConcurrentHashMap

/**
 * How many parameters an ability declares, by CONTENT HASH — remembered from
 * wherever the declaration was seen, so a later pass that can no longer resolve
 * the ability still knows its shape.
 *
 * ## Why this exists
 *
 * An ability's parameters are positional, and several places rebuild a
 * constraint's full tuple from the `(typeVar, typeArgs)` split that
 * `Constraint.AbilityRequired` stores it in — a split that does not record where
 * the primary sat. The declared arity is what makes that reconstruction decidable
 * ("a tuple that already fills the arity is complete; one short of it is missing
 * its primary"), and `abilityClassArgs` prepends unconditionally when it is
 * absent, FABRICATING an argument.
 *
 * MEASURED: `registry.lookupAbility` answers for an ability in the early passes
 * and returns null in a later one — `lookup=3` four times, then `lookup=null`,
 * for an ability declared in the module being checked. The constraint census
 * (`MEZZE_CONSTRAINT_CENSUS=1`) counts what that costs: **138** emitted
 * constraints in the stdlib alone whose argument count does not match their
 * ability's, most of them `with Ord has 2 args, ability declares 1` — a
 * one-parameter ability cannot carry two, so those are the prepend firing on a
 * list that was already complete.
 *
 * ## Why a shared table is sound here, when shared state usually is not
 *
 * The value is a pure function of the ability's DECLARATION: the same content
 * hash always declares the same parameters, in every module and every context,
 * because the hash is computed from them. So an entry can never be stale or
 * context-specific, which is the property `cross_context_static_state_leaks`
 * warns is usually missing — and it is the same argument the generated
 * `WellKnownHashConstants` relies on for stdlib hashes.
 *
 * Keyed on the hash FIRST. The name is kept only as a fallback for a constraint
 * that carries no hash, and a name is not an identity — two modules may each
 * declare an `Ord`. A name whose abilities disagree on arity is therefore
 * DROPPED from the name index rather than answered wrongly.
 */
object AbilityArityTable {
    private val byHash = ConcurrentHashMap<Hash, Int>()
    private val byName = ConcurrentHashMap<String, Int>()
    /** Names seen with CONFLICTING arities — never answered by name again. */
    private val ambiguousNames = ConcurrentHashMap.newKeySet<String>()

    fun remember(decl: ParsedAbilityDecl) {
        val arity = decl.typeParams.size
        if (arity == 0) return
        decl.contentHash?.let { byHash[it] = arity }
        val prior = byName.put(decl.name, arity)
        if (prior != null && prior != arity) ambiguousNames.add(decl.name)
    }

    fun rememberAll(decls: Iterable<ParsedAbilityDecl>) = decls.forEach(::remember)

    /** The declared parameter count, or null when nothing has seen this ability. */
    fun of(name: String?, hash: Hash?): Int? {
        hash?.let { byHash[it] }?.let { return it }
        if (name == null || name in ambiguousNames) return null
        return byName[name]
    }

    /** Test seam only: the table is a pure fact about declarations, so production
     *  code never needs to forget one. */
    internal fun clearForTests() {
        byHash.clear(); byName.clear(); ambiguousNames.clear()
    }
}
