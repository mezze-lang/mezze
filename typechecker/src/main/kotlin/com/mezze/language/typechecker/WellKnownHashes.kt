package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.typechecker.hashing.ParsedDeclHasher

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
 * These are identified by the *content hash* of the declaration, which is
 * stable across renames and aliased imports.  Name-based fallbacks have been
 * intentionally removed — if a hash is not available, that is a bug in the
 * caller (constraint creation, context building, etc.).
 *
 * ## Mut note
 *
 * `MezzeType.EffectRow` now carries `effectHashes: Map<String, String>` populated by
 * `ContextBuilder` when converting type annotations.  The Mut check uses
 * hash-only identification.
 */
object WellKnownHashes {

    /**
     * Content hash of the `Inspect` ability declaration
     * (`stdlib/std/abilities/inspect.mz`).
     *
     * Computed lazily on first access so that the parser/resource loader is not
     * invoked during static initialisation.  Returns `null` if the resource cannot
     * be found (should not happen in production).
     */
    val inspectAbilityHash: String? by lazy { computeInspectHash() }

    /**
     * Content hash of the `Mut` effect declaration
     * (`stdlib/std/effects/mutation.mz`).
     *
     * Computed lazily on first access.  Returns `null` if the resource cannot
     * be found (should not happen in production).
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
     * be found (should not happen in production).
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
     * Returns `true` when [hash] identifies the Inspect ability by content hash.
     * Hash-only identification — no name-based fallback. If hash is null, returns false.
     */
    fun isInspect(hash: String?): Boolean =
        hash != null && hash == inspectAbilityHash

    /**
     * Returns `true` when [hash] identifies the Mut effect by content hash.
     * Hash-only identification — no name-based fallback. If hash is null, returns false.
     */
    fun isMut(hash: String?): Boolean =
        hash != null && hash == mutEffectHash

    /**
     * Returns `true` when [hash] identifies one of the codec abilities by content hash.
     * Hash-only identification — no name-based fallback. If hash is null, returns false.
     */
    fun isCodecAbility(hash: String?): Boolean =
        hash != null && hash in codecAbilityHashes

    // -------------------------------------------------------------------------
    // Comparison ability helpers (hash-only)
    // -------------------------------------------------------------------------

    /** Returns `true` when [hash] identifies the `Eq` ability by content hash. */
    fun isEq(hash: String?): Boolean =
        hash != null && hash == eqAbilityHash

    /** Returns `true` when [hash] identifies the `Ord` ability by content hash. */
    fun isOrd(hash: String?): Boolean =
        hash != null && hash == ordAbilityHash

    // -------------------------------------------------------------------------
    // Numeric ability helpers (hash-only)
    // -------------------------------------------------------------------------

    /** Returns `true` when [hash] identifies the `NumAdd` ability by content hash. */
    fun isNumAdd(hash: String?): Boolean =
        hash != null && hash == numAddAbilityHash

    /** Returns `true` when [hash] identifies the `NumSub` ability by content hash. */
    fun isNumSub(hash: String?): Boolean =
        hash != null && hash == numSubAbilityHash

    /** Returns `true` when [hash] identifies the `NumMul` ability by content hash. */
    fun isNumMul(hash: String?): Boolean =
        hash != null && hash == numMulAbilityHash

    /** Returns `true` when [hash] identifies the `NumDiv` ability by content hash. */
    fun isNumDiv(hash: String?): Boolean =
        hash != null && hash == numDivAbilityHash

    /** Returns `true` when [hash] identifies the `NumMod` ability by content hash. */
    fun isNumMod(hash: String?): Boolean =
        hash != null && hash == numModAbilityHash

    /** Returns `true` when [hash] identifies the `NumZero` ability by content hash. */
    fun isNumZero(hash: String?): Boolean =
        hash != null && hash == numZeroAbilityHash

    /** Returns `true` when [hash] identifies the `NumOne` ability by content hash. */
    fun isNumOne(hash: String?): Boolean =
        hash != null && hash == numOneAbilityHash

    /**
     * Returns `true` when [hash] identifies any numeric ability
     * (NumAdd, NumSub, NumMul, NumDiv, NumMod, NumZero, NumOne) by content hash.
     */
    fun isNumericAbility(hash: String?): Boolean =
        isNumAdd(hash) || isNumSub(hash) || isNumMul(hash) || isNumDiv(hash) ||
        isNumMod(hash) || isNumZero(hash) || isNumOne(hash)

    // -------------------------------------------------------------------------
    // Conversion ability helpers (hash-only)
    // -------------------------------------------------------------------------

    /** Returns `true` when [hash] identifies the `Into` ability by content hash. */
    fun isInto(hash: String?): Boolean =
        hash != null && hash == intoAbilityHash

    /** Returns `true` when [hash] identifies the `TryInto` ability by content hash. */
    fun isTryInto(hash: String?): Boolean =
        hash != null && hash == tryIntoAbilityHash

    // -------------------------------------------------------------------------
    // Default ability helper (hash-only)
    // -------------------------------------------------------------------------

    /** Returns `true` when [hash] identifies the `Default` ability by content hash. */
    fun isDefault(hash: String?): Boolean =
        hash != null && hash == defaultAbilityHash

    // -------------------------------------------------------------------------
    // Internal computation
    // -------------------------------------------------------------------------

    /** Canonical names of codec abilities — used for error messages only, NOT for identification. */
    val CODEC_ABILITY_NAMES = setOf(
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
            val module = parser.parseParsedModule(source, "stdlib")
            module.decls.filterIsInstance<ParsedAbilityDecl>()
                .filter { it.name in CODEC_ABILITY_NAMES }
                .mapNotNull { decl ->
                    try {
                        ParsedDeclHasher.hashAbilityDecl(decl)
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
            val module = parser.parseParsedModule(source, "stdlib")
            val decl = module.decls.filterIsInstance<ParsedAbilityDecl>()
                .firstOrNull { it.name == abilityName }
                ?: return null

            ParsedDeclHasher.hashAbilityDecl(decl)
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
            val module = parser.parseParsedModule(source, "stdlib")
            val decl = module.decls.filterIsInstance<ParsedEffectDecl>()
                .firstOrNull { it.name == "Mut" }
                ?: return null

            ParsedDeclHasher.hashEffectDecl(decl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve ability/effect hash from its canonical name.
     *
     * This is a compile-time constant table of well-known ability/effect hashes.
     * Used when the hash isn't available in the immediate context (e.g., abilities
     * not in abilityDeclarations because they come from BuiltInAbilities or prelude).
     *
     * This is NOT a name-based fallback — it maps names to pre-computed hashes
     * from the actual stdlib source files.
     */
    fun resolveHash(name: String): String? = when (name) {
        "Inspect" -> inspectAbilityHash
        "Mut" -> mutEffectHash
        "Eq" -> eqAbilityHash
        "Ord" -> ordAbilityHash
        "NumAdd" -> numAddAbilityHash
        "NumSub" -> numSubAbilityHash
        "NumMul" -> numMulAbilityHash
        "NumDiv" -> numDivAbilityHash
        "NumMod" -> numModAbilityHash
        "NumZero" -> numZeroAbilityHash
        "NumOne" -> numOneAbilityHash
        "Into" -> intoAbilityHash
        "TryInto" -> tryIntoAbilityHash
        "Default" -> defaultAbilityHash
        else -> {
            // Check codec abilities — each has its own hash in codecAbilityHashes
            // The set stores hashes, but we need name→hash mapping
            // For codec, we can't resolve by name since we store them as a set
            // The constraint should have the hash from abilityDeclarations
            null
        }
    }
}
