package com.mezze.language.typechecker

import com.mezze.language.ast.AbilityDeclaration
import com.mezze.language.ast.EffectDeclaration
import com.mezze.language.ast.hashing.ASTHasher
import com.mezze.language.parser.MezzeParser

/**
 * Singleton that computes and caches the content hashes of compiler-known
 * ability/effect declarations at first use.
 *
 * ## Why this exists
 *
 * Several constructs are universally known to the compiler:
 * - **Inspect** — universally available ability (auto-derived for all types)
 * - **Mut** — hermetic effect (cannot coexist with other effects)
 * - **Codec abilities** — auto-derived serialization abilities (`Encode`, `EncodeRepr`,
 *   `Decode`, `DecodeRepr`, `StreamEncode`, `StreamDecode`, and their format counterparts)
 *
 * These were previously identified by name string ("Inspect", "Mut", "Encode", …), which
 * breaks when a user imports a declaration under an alias:
 *
 *   use std::abilities::inspect::{Inspect as Debug}
 *
 * After that import the compiler saw "Debug" in the constraint, not "Inspect", so
 * the special-case logic was silently skipped.
 *
 * The fix is to identify these by the *content hash* of the declaration, which is
 * stable across renames.
 *
 * ## Mut note
 *
 * `MezzeType.EffectRow` now carries `effectHashes: Map<String, String>` populated by
 * `ContextBuilder` when converting type annotations.  The Mut check therefore uses
 * hash-based identification (with a name-based fallback for robustness).
 */
object WellKnownHashes {

    /**
     * Content hash of the `Inspect` ability declaration
     * (`stdlib/std/abilities/inspect.mz`).
     *
     * Computed lazily on first access so that the parser/resource loader is not
     * invoked during static initialisation.  Returns `null` if the resource cannot
     * be found (should not happen in production; allows graceful degradation).
     */
    val inspectAbilityHash: String? by lazy { computeInspectHash() }

    /**
     * Content hash of the `Mut` effect declaration
     * (`stdlib/std/effects/mutation.mz`).
     *
     * Computed lazily on first access.  Returns `null` if the resource cannot
     * be found (allows graceful name-based fallback).
     */
    val mutEffectHash: String? by lazy { computeMutHash() }

    /**
     * Set of content hashes for all codec abilities declared in
     * `stdlib/std/codec/core.mz`:
     *
     *   Encode, EncodeRepr, Decode, DecodeRepr,
     *   StreamEncode, StreamDecode,
     *   Format (EncodeFormat), DecodeFormat, StreamFormat, StreamDecodeFormat
     *
     * Computed lazily on first access.  The set is empty if the resource cannot
     * be found (the name-based fallback in [isCodecAbility] then takes over).
     */
    val codecAbilityHashes: Set<String> by lazy { computeCodecHashes() }

    // -------------------------------------------------------------------------
    // Public helpers
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when [abilityHash] identifies the Inspect ability by content.
     *
     * Use this as the primary check.  A null hash never matches (the caller should
     * fall back to the name-based check below when the hash is unavailable).
     */
    fun isInspect(abilityHash: String?): Boolean =
        abilityHash != null && abilityHash == inspectAbilityHash

    /**
     * Returns `true` when the constraint identifies the Inspect ability.
     *
     * Checks by content hash first; falls back to the legacy name "Inspect" so that
     * code paths that haven't been migrated to hash-based constraints continue to
     * work.  The name fallback also keeps the compiler working when
     * `inspectAbilityHash` could not be computed (missing resource).
     */
    fun isInspect(abilityName: String, abilityHash: String?): Boolean =
        isInspect(abilityHash) || abilityName == "Inspect"

    /**
     * Returns `true` when [effectHash] identifies the Mut effect by content.
     *
     * Use this as the primary check.  A null hash never matches (the caller should
     * fall back to the name-based check when the hash is unavailable).
     */
    fun isMut(effectHash: String?): Boolean =
        effectHash != null && effectHash == mutEffectHash

    /**
     * Returns `true` when the constraint identifies the Mut effect.
     *
     * Checks by content hash first; falls back to the legacy name "Mut" so that
     * code paths that haven't been migrated to hash-based effect rows continue to
     * work.  The name fallback also keeps the compiler working when
     * `mutEffectHash` could not be computed (missing resource).
     */
    fun isMut(effectName: String, effectHash: String? = null): Boolean =
        isMut(effectHash) || effectName == "Mut"

    /**
     * Returns `true` when [abilityHash] identifies one of the codec abilities by content.
     *
     * Use this as the primary check.  A null hash never matches (the caller should fall
     * back to the name-based overload below when the hash is unavailable).
     */
    fun isCodecAbility(abilityHash: String?): Boolean =
        abilityHash != null && abilityHash in codecAbilityHashes

    /**
     * Returns `true` when the constraint identifies a codec ability.
     *
     * Checks by content hash first; falls back to a fixed set of canonical names so
     * that code paths that haven't been migrated to hash-based constraints continue to
     * work.  The name fallback also keeps the compiler working when
     * `codecAbilityHashes` could not be computed (missing resource).
     */
    fun isCodecAbility(abilityName: String, abilityHash: String? = null): Boolean =
        isCodecAbility(abilityHash) || abilityName in CODEC_ABILITY_NAMES

    // -------------------------------------------------------------------------
    // Internal computation
    // -------------------------------------------------------------------------

    /** Canonical names used as a name-based fallback for codec ability identification. */
    private val CODEC_ABILITY_NAMES = setOf(
        "Encode", "EncodeRepr", "Decode", "DecodeRepr",
        "StreamEncode", "StreamDecode",
        "Format", "DecodeFormat", "StreamFormat", "StreamDecodeFormat"
    )

    private fun computeCodecHashes(): Set<String> {
        return try {
            val source = ModuleResolver.readStdlibFile("stdlib/std/codec/core.mz")
                ?: ModuleResolver.readStdlibFile("std/codec/core.mz")
                ?: return emptySet()

            val parser = MezzeParser()
            val program = parser.parse(source)
            program.items.filterIsInstance<AbilityDeclaration>()
                .filter { it.name in CODEC_ABILITY_NAMES }
                .mapNotNull { decl ->
                    try {
                        ASTHasher.hashAbilityDeclaration(decl).toFullHexString()
                    } catch (_: Exception) {
                        null
                    }
                }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun computeInspectHash(): String? {
        return try {
            val source = ModuleResolver.readStdlibFile("stdlib/std/abilities/inspect.mz")
                ?: ModuleResolver.readStdlibFile("std/abilities/inspect.mz")
                ?: return null

            val parser = MezzeParser()
            val program = parser.parse(source)
            val decl = program.items.filterIsInstance<AbilityDeclaration>()
                .firstOrNull { it.name == "Inspect" }
                ?: return null

            ASTHasher.hashAbilityDeclaration(decl).toFullHexString()
        } catch (_: Exception) {
            null
        }
    }

    private fun computeMutHash(): String? {
        return try {
            val source = ModuleResolver.readStdlibFile("stdlib/std/effects/mutation.mz")
                ?: ModuleResolver.readStdlibFile("std/effects/mutation.mz")
                ?: return null

            val parser = MezzeParser()
            val program = parser.parse(source)
            val decl = program.items.filterIsInstance<EffectDeclaration>()
                .firstOrNull { it.name == "Mut" }
                ?: return null

            ASTHasher.hashEffectDeclaration(decl).toFullHexString()
        } catch (_: Exception) {
            null
        }
    }
}
