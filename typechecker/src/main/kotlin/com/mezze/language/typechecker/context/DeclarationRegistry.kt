package com.mezze.language.typechecker.context

import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedDeclHasher
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.typedast.Hash

class DeclarationRegistry(
    private val typesByName: Map<String, ParsedTypeDecl>,
    private val typesByHash: Map<Hash, ParsedTypeDecl>,
    private val abilitiesByName: Map<String, ParsedAbilityDecl>,
    private val abilitiesByHash: Map<Hash, ParsedAbilityDecl>,
    private val effectsByName: Map<String, ParsedEffectDecl>,
    private val effectsByHash: Map<Hash, ParsedEffectDecl>,
    // #91: name → contentHash for EVERY loaded type (import-scoped + transitive).
    // Used only to STAMP a hash onto a TypeApplication during conversion, never
    // to make a name resolvable — so import-scoping for name references is
    // unchanged, but a cross-module ability-op type can later resolve by hash.
    private val allTypeHashesByName: Map<String, Hash> = emptyMap(),
) {
    /**
     * The single declaration-resolution rule: HASH-FIRST, name-only-as-fallback.
     *
     * A hash — when the caller has one — is authoritative identity and decides
     * outright. A NAME is consulted ONLY when no hash is supplied, i.e. at the
     * source-resolution boundary (a `use`d name being resolved to its decl for
     * the first time). This is the one legitimate place a name maps to a decl;
     * it is NOT a dispatch fallback. Every lookup* below delegates here so the
     * rule lives in exactly one spot and cannot drift per decl-kind.
     *
     * The implementation used to read `(hash?.let { byHash[it] }) ?: byName[name]`,
     * which is the rule above for a hash that HITS and its opposite for one that
     * misses: a caller who knew exactly which declaration it wanted, and whose
     * declaration this registry did not have, was handed a different one that
     * happened to share the name. A module declaring `type Option = | Yes, No |`
     * expanded the stdlib's `Option Int` with ITS constructors, the two unified,
     * the program type-checked, and the match died at runtime with "no variant tag
     * matched — typechecker exhaustiveness guarantee violated".
     *
     * A hash that misses means NOT HERE. That is an answer, and callers that can
     * look further afield must do so by hash too.
     */
    private fun <D> resolve(name: String, hash: Hash?, byHash: Map<Hash, D>, byName: Map<String, D>): D? =
        if (hash != null) byHash[hash] else byName[name]

    fun lookupType(name: String, hash: Hash? = null): ParsedTypeDecl? =
        resolve(name, hash, typesByHash, typesByName)

    /** The content hash for a type by name, consulting local + imported +
     *  transitive types. For hash-stamping only (see [allTypeHashesByName]). */
    fun typeHashFor(name: String): Hash? =
        typesByName[name]?.contentHash ?: allTypeHashesByName[name]

    fun lookupAbility(name: String, hash: Hash? = null): ParsedAbilityDecl? =
        resolve(name, hash, abilitiesByHash, abilitiesByName)

    fun lookupEffect(name: String, hash: Hash? = null): ParsedEffectDecl? =
        resolve(name, hash, effectsByHash, effectsByName)

    /** Content hash for an ability by name (check-time replacement for the
     *  generated WellKnownHashConstants.resolveAbilityHash). Same pure value:
     *  `decl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(decl)`. */
    fun resolveAbilityHash(name: String): Hash? =
        lookupAbility(name)?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }

    /** Content hash for an effect by name (check-time replacement for the
     *  generated WellKnownHashConstants.resolveEffectHash). */
    fun resolveEffectHash(name: String): Hash? =
        lookupEffect(name)?.let { it.contentHash ?: ParsedDeclHasher.hashEffectDecl(it) }

    val typeNames: Set<String> get() = typesByName.keys
    val abilityNames: Set<String> get() = abilitiesByName.keys
    val effectNames: Set<String> get() = effectsByName.keys

    val allTypeDecls: Collection<ParsedTypeDecl> get() = typesByName.values
    val allAbilityDecls: Collection<ParsedAbilityDecl> get() = abilitiesByName.values
    val allEffectDecls: Collection<ParsedEffectDecl> get() = effectsByName.values

    /**
     * Every loaded type decl — import-scoped names PLUS transitive types
     * reachable via hash (auto-imported through a returned/parameter type
     * without an explicit `use`). Used when a constructor name in a pattern
     * needs to resolve to its declaring type even though the alias isn't
     * imported at this call site: the value's type identity flows with it,
     * so `NoneFound` in `match lookup{} is Some { val = NoneFound }` should
     * find `OptErr` even without `use opt_err::{OptErr}`.
     */
    val allTypeDeclsIncludingTransitive: Collection<ParsedTypeDecl> get() =
        (typesByName.values.asSequence() + typesByHash.values.asSequence())
            .distinctBy { it.contentHash ?: it.name }
            .toList()

    // Expose string-keyed maps for callers that still need them directly
    val typeDeclarations: Map<String, ParsedTypeDecl> get() = typesByName
    val abilityDeclarations: Map<String, ParsedAbilityDecl> get() = abilitiesByName
    val effectDeclarations: Map<String, ParsedEffectDecl> get() = effectsByName

    companion object {
        val EMPTY = DeclarationRegistry(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun from(
            typeDecls: Map<String, ParsedTypeDecl>,
            abilityDecls: Map<String, ParsedAbilityDecl>,
            effectDecls: Map<String, ParsedEffectDecl>,
            // #91: extra type decls folded into typesByHash ONLY (not
            // typesByName). Lets a hash-tagged TypeApplication for a
            // cross-module type resolve by hash without making that type's name
            // resolvable (keeps import-scoping + avoids name collisions).
            hashOnlyTypeDecls: Collection<ParsedTypeDecl> = emptyList(),
        ): DeclarationRegistry {
            // Stamp a content hash onto EVERY type decl entering the registry. The
            // hash is a pure function of the decl and always computable, so no type
            // decl is ever stored hashless — which means every MezzeType built from a
            // registry decl (expandTypeApplicationToVariant, VariantSolver, alias
            // resolution, …) carries a real nominal identity (Declared), never Named.
            // Mirrors the effect/performer stamping idiom in ContextBuilder.
            val typeDeclsStamped = typeDecls.mapValues { (_, d) ->
                if (d.contentHash != null) d else d.copy(contentHash = ParsedDeclHasher.hashTypeDecl(d))
            }
            val byHash = LinkedHashMap<Hash, ParsedTypeDecl>()
            val hashByName = LinkedHashMap<String, Hash>()
            for (d in hashOnlyTypeDecls) d.contentHash?.let { byHash[it] = d; hashByName[d.name] = it }
            // Locally-declared / imported types win over transitive ones on hash
            // collision (same hash = same type, so order is immaterial, but keep
            // it deterministic).
            for (d in typeDeclsStamped.values) d.contentHash?.let { byHash[it] = d; hashByName[d.name] = it }
            return DeclarationRegistry(
                typesByName = typeDeclsStamped,
                typesByHash = byHash,
                abilitiesByName = abilityDecls,
                abilitiesByHash = abilityDecls.values.mapNotNull { d -> d.contentHash?.let { h -> h to d } }.toMap(),
                effectsByName = effectDecls,
                effectsByHash = effectDecls.values.mapNotNull { d -> d.contentHash?.let { h -> h to d } }.toMap(),
                allTypeHashesByName = hashByName,
            )
        }
    }
}
