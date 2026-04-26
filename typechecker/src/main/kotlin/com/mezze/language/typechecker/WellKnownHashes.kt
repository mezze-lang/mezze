package com.mezze.language.typechecker

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
    val inspectAbilityHash: String = WellKnownHashConstants.ABILITY_INSPECT
    val mutEffectHash: String = WellKnownHashConstants.EFFECT_MUT

    fun isInspect(hash: String?): Boolean = WellKnownHashConstants.isInspect(hash)
    fun isMut(hash: String?): Boolean = WellKnownHashConstants.isMut(hash)
    fun isCodecAbility(hash: String?): Boolean = WellKnownHashConstants.isCodecAbility(hash)
    fun isEq(hash: String?): Boolean = WellKnownHashConstants.isEq(hash)
    fun isOrd(hash: String?): Boolean = WellKnownHashConstants.isOrd(hash)
    fun isNumAdd(hash: String?): Boolean = WellKnownHashConstants.isNumAdd(hash)
    fun isNumSub(hash: String?): Boolean = WellKnownHashConstants.isNumSub(hash)
    fun isNumMul(hash: String?): Boolean = WellKnownHashConstants.isNumMul(hash)
    fun isNumDiv(hash: String?): Boolean = WellKnownHashConstants.isNumDiv(hash)
    fun isNumMod(hash: String?): Boolean = WellKnownHashConstants.isNumMod(hash)

    fun resolveHash(name: String): String? = WellKnownHashConstants.resolveHash(name)

    // Keep computeAbilityHash for backward compat with callers outside typechecker
    fun computeAbilityHash(filePath: String, abilityName: String): String? =
        WellKnownHashConstants.resolveAbilityHash(abilityName)

    internal fun computeEffectHash(filePath: String, effectName: String): String? =
        WellKnownHashConstants.resolveEffectHash(effectName)
}
