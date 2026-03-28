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
    // Comparison ability hashes  (stdlib/std/abilities/compare.mz)
    // -------------------------------------------------------------------------

    /** Content hash of the `Eq` ability declaration. */
    val eqAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/compare.mz", "Eq") }

    /** Content hash of the `Ord` ability declaration. */
    val ordAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/compare.mz", "Ord") }

    // -------------------------------------------------------------------------
    // Numeric ability hashes  (stdlib/std/abilities/numeric.mz)
    // -------------------------------------------------------------------------

    /** Content hash of the `NumAdd` ability declaration. */
    val numAddAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumAdd") }

    /** Content hash of the `NumSub` ability declaration. */
    val numSubAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumSub") }

    /** Content hash of the `NumMul` ability declaration. */
    val numMulAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumMul") }

    /** Content hash of the `NumDiv` ability declaration. */
    val numDivAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumDiv") }

    /** Content hash of the `NumMod` ability declaration. */
    val numModAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumMod") }

    /** Content hash of the `NumZero` ability declaration. */
    val numZeroAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumZero") }

    /** Content hash of the `NumOne` ability declaration. */
    val numOneAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/numeric.mz", "NumOne") }

    // -------------------------------------------------------------------------
    // Conversion ability hashes  (stdlib/std/abilities/convert.mz)
    // -------------------------------------------------------------------------

    /** Content hash of the `Into` ability declaration. */
    val intoAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/convert.mz", "Into") }

    /** Content hash of the `TryInto` ability declaration. */
    val tryIntoAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/convert.mz", "TryInto") }

    // -------------------------------------------------------------------------
    // Default ability hash  (stdlib/std/abilities/default.mz)
    // -------------------------------------------------------------------------

    /** Content hash of the `Default` ability declaration. */
    val defaultAbilityHash: String? by lazy { computeAbilityHash("stdlib/std/abilities/default.mz", "Default") }

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
    // Comparison ability helpers
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when the constraint identifies the `Eq` ability.
     *
     * Checks by content hash first; falls back to the canonical name "Eq".
     */
    fun isEq(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == eqAbilityHash) || abilityName == "Eq"

    /**
     * Returns `true` when the constraint identifies the `Ord` ability.
     *
     * Checks by content hash first; falls back to the canonical name "Ord".
     */
    fun isOrd(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == ordAbilityHash) || abilityName == "Ord"

    // -------------------------------------------------------------------------
    // Numeric ability helpers
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when the constraint identifies the `NumAdd` ability.
     */
    fun isNumAdd(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numAddAbilityHash) || abilityName == "NumAdd"

    /**
     * Returns `true` when the constraint identifies the `NumSub` ability.
     */
    fun isNumSub(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numSubAbilityHash) || abilityName == "NumSub"

    /**
     * Returns `true` when the constraint identifies the `NumMul` ability.
     */
    fun isNumMul(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numMulAbilityHash) || abilityName == "NumMul"

    /**
     * Returns `true` when the constraint identifies the `NumDiv` ability.
     */
    fun isNumDiv(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numDivAbilityHash) || abilityName == "NumDiv"

    /**
     * Returns `true` when the constraint identifies the `NumMod` ability.
     */
    fun isNumMod(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numModAbilityHash) || abilityName == "NumMod"

    /**
     * Returns `true` when the constraint identifies the `NumZero` ability.
     */
    fun isNumZero(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numZeroAbilityHash) || abilityName == "NumZero"

    /**
     * Returns `true` when the constraint identifies the `NumOne` ability.
     */
    fun isNumOne(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == numOneAbilityHash) || abilityName == "NumOne"

    /**
     * Returns `true` when the constraint identifies any numeric ability
     * (NumAdd, NumSub, NumMul, NumDiv, NumMod, NumZero, NumOne).
     */
    fun isNumericAbility(abilityName: String, abilityHash: String? = null): Boolean =
        isNumAdd(abilityName, abilityHash) ||
        isNumSub(abilityName, abilityHash) ||
        isNumMul(abilityName, abilityHash) ||
        isNumDiv(abilityName, abilityHash) ||
        isNumMod(abilityName, abilityHash) ||
        isNumZero(abilityName, abilityHash) ||
        isNumOne(abilityName, abilityHash)

    // -------------------------------------------------------------------------
    // Conversion ability helpers
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when the constraint identifies the `Into` ability.
     */
    fun isInto(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == intoAbilityHash) || abilityName == "Into"

    /**
     * Returns `true` when the constraint identifies the `TryInto` ability.
     */
    fun isTryInto(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == tryIntoAbilityHash) || abilityName == "TryInto"

    // -------------------------------------------------------------------------
    // Default ability helper
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when the constraint identifies the `Default` ability.
     */
    fun isDefault(abilityName: String, abilityHash: String? = null): Boolean =
        (abilityHash != null && abilityHash == defaultAbilityHash) || abilityName == "Default"

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

    /**
     * Generic helper that reads the stdlib file at [filePath], parses it, and returns the
     * content hash of the ability declaration named [abilityName].
     *
     * Both the canonical "stdlib/…" path and the stripped "std/…" path are tried so that
     * the function works both when running from the project root (JAR / Gradle) and when
     * resources are loaded from a native image or a flat classpath.
     *
     * Returns `null` if the file cannot be found or the named ability is not present.
     */
    fun computeAbilityHash(filePath: String, abilityName: String): String? {
        return try {
            // Try the given path first, then drop the leading "stdlib/" prefix as fallback.
            val strippedPath = if (filePath.startsWith("stdlib/")) filePath.removePrefix("stdlib/") else filePath
            val source = ModuleResolver.readStdlibFile(filePath)
                ?: ModuleResolver.readStdlibFile(strippedPath)
                ?: return null

            val parser = MezzeParser()
            val program = parser.parse(source)
            val decl = program.items.filterIsInstance<AbilityDeclaration>()
                .firstOrNull { it.name == abilityName }
                ?: return null

            ASTHasher.hashAbilityDeclaration(decl).toFullHexString()
        } catch (_: Exception) {
            null
        }
    }

    private fun computeInspectHash(): String? =
        computeAbilityHash("stdlib/std/abilities/inspect.mz", "Inspect")

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
