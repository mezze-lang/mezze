package com.mezze.language.typechecker.context

import com.mezze.language.parsedast.ParsedAbilityDecl
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
    fun lookupType(name: String, hash: Hash? = null): ParsedTypeDecl? =
        (hash?.let { typesByHash[it] }) ?: typesByName[name]

    /** The content hash for a type by name, consulting local + imported +
     *  transitive types. For hash-stamping only (see [allTypeHashesByName]). */
    fun typeHashFor(name: String): Hash? =
        typesByName[name]?.contentHash ?: allTypeHashesByName[name]

    fun lookupAbility(name: String, hash: Hash? = null): ParsedAbilityDecl? =
        (hash?.let { abilitiesByHash[it] }) ?: abilitiesByName[name]

    fun lookupEffect(name: String, hash: Hash? = null): ParsedEffectDecl? =
        (hash?.let { effectsByHash[it] }) ?: effectsByName[name]

    val typeNames: Set<String> get() = typesByName.keys
    val abilityNames: Set<String> get() = abilitiesByName.keys
    val effectNames: Set<String> get() = effectsByName.keys

    val allTypeDecls: Collection<ParsedTypeDecl> get() = typesByName.values
    val allAbilityDecls: Collection<ParsedAbilityDecl> get() = abilitiesByName.values
    val allEffectDecls: Collection<ParsedEffectDecl> get() = effectsByName.values

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
            val byHash = LinkedHashMap<Hash, ParsedTypeDecl>()
            val hashByName = LinkedHashMap<String, Hash>()
            for (d in hashOnlyTypeDecls) d.contentHash?.let { byHash[it] = d; hashByName[d.name] = it }
            // Locally-declared / imported types win over transitive ones on hash
            // collision (same hash = same type, so order is immaterial, but keep
            // it deterministic).
            for (d in typeDecls.values) d.contentHash?.let { byHash[it] = d; hashByName[d.name] = it }
            return DeclarationRegistry(
                typesByName = typeDecls,
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
