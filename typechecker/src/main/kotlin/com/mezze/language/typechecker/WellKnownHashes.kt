package com.mezze.language.typechecker

import com.mezze.language.typedast.Hash

/**
 * Hash-based identification of compiler-known abilities and effects.
 *
 * All hashes are build-time constants computed from stdlib source files by
 * WellKnownHashGenerator. No runtime filesystem access or lazy parsing.
 *
 * These enable rename-safe identification: even if the user imports
 * `Inspect as Debug` or `NumAdd as Plus`, the hash stays the same because
 * it's computed from the declaration CONTENT, not the name.
 */
object WellKnownHashes {

    // Delegate everything to build-time generated constants
    val inspectAbilityHash: Hash = WellKnownHashConstants.ABILITY_INSPECT
    val mutEffectHash: Hash = WellKnownHashConstants.EFFECT_MUT

    fun isInspect(hash: Hash?): Boolean = WellKnownHashConstants.isInspect(hash)
    fun isMut(hash: Hash?): Boolean = WellKnownHashConstants.isMut(hash)
    fun isCodecAbility(hash: Hash?): Boolean = WellKnownHashConstants.isCodecAbility(hash)
    fun isEncodeFlavoredCodec(hash: Hash?): Boolean = WellKnownHashConstants.isEncodeFlavoredCodec(hash)
    fun isDecodeFlavoredCodec(hash: Hash?): Boolean = WellKnownHashConstants.isDecodeFlavoredCodec(hash)
    fun isEq(hash: Hash?): Boolean = WellKnownHashConstants.isEq(hash)
    fun isOrd(hash: Hash?): Boolean = WellKnownHashConstants.isOrd(hash)
    fun isNumAdd(hash: Hash?): Boolean = WellKnownHashConstants.isNumAdd(hash)
    fun isNumSub(hash: Hash?): Boolean = WellKnownHashConstants.isNumSub(hash)
    fun isNumMul(hash: Hash?): Boolean = WellKnownHashConstants.isNumMul(hash)
    fun isNumDiv(hash: Hash?): Boolean = WellKnownHashConstants.isNumDiv(hash)
    fun isNumMod(hash: Hash?): Boolean = WellKnownHashConstants.isNumMod(hash)

    fun resolveHash(name: String): Hash? = WellKnownHashConstants.resolveHash(name)

    /**
     * Resolve a public stdlib type by its declared name. For #309 — gives
     * callers a hash-based identity for stdlib types so `constructor == "Json"`
     * style checks can be replaced by `typeHash == WellKnownHashes.resolveTypeHash("Json")`.
     *
     * Built-in synthetic types (Option, Result, Bool, Ordering — built in
     * Kotlin in ContextBuilder, not parsed from `.mz`) are NOT in this map.
     * They're protected from user redeclaration by DuplicateDeclarationValidator.
     */
    fun resolveTypeHash(name: String): Hash? = WellKnownHashConstants.resolveTypeHash(name)

    // Keep computeAbilityHash for backward compat with callers outside typechecker
    fun computeAbilityHash(filePath: String, abilityName: String): Hash? =
        WellKnownHashConstants.resolveAbilityHash(abilityName)

    internal fun computeEffectHash(filePath: String, effectName: String): Hash? =
        WellKnownHashConstants.resolveEffectHash(effectName)
}
