package com.mezze.language.typechecker.context

import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.typedast.Hash

/**
 * Unified declaration lookup — tries hash first, falls back to name.
 *
 * Wraps both string-keyed and hash-keyed declaration maps.
 * Callers use a single lookup method; the registry handles resolution.
 * As callers migrate to passing hashes, the string-keyed maps become
 * unused and can be removed.
 */
class DeclarationRegistry(
    private val typesByName: Map<String, ParsedTypeDecl>,
    private val typesByHash: Map<Hash, ParsedTypeDecl>,
    private val abilitiesByName: Map<String, ParsedAbilityDecl>,
    private val abilitiesByHash: Map<Hash, ParsedAbilityDecl>,
    private val effectsByName: Map<String, ParsedEffectDecl>,
    private val effectsByHash: Map<Hash, ParsedEffectDecl>,
) {
    fun lookupType(name: String, hash: Hash? = null): ParsedTypeDecl? =
        (hash?.let { typesByHash[it] }) ?: typesByName[name]

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
        ) = DeclarationRegistry(
            typesByName = typeDecls,
            typesByHash = typeDecls.values.mapNotNull { d -> d.contentHash?.let { h -> h to d } }.toMap(),
            abilitiesByName = abilityDecls,
            abilitiesByHash = abilityDecls.values.mapNotNull { d -> d.contentHash?.let { h -> h to d } }.toMap(),
            effectsByName = effectDecls,
            effectsByHash = effectDecls.values.mapNotNull { d -> d.contentHash?.let { h -> h to d } }.toMap(),
        )
    }
}
