package com.mezze.language.typechecker

import com.mezze.language.typedast.Hash

/**
 * The stdlib declarations the compiler treats specially, by identity.
 *
 * Every check here compares IDENTITIES, never spellings: a re-export or a local
 * alias still matches, and a same-named user declaration never does. The
 * identities come from [StdlibIdentities]; one whose module has not been loaded
 * matches nothing, because nothing in play is that declaration.
 *
 * Was generated at build time from parsed stdlib text. An identity now includes
 * what a declaration's names resolve to, which parsing alone cannot know.
 */
object WellKnownHashConstants {

    private fun ability(name: String): Hash = StdlibIdentities.ability(name) ?: StdlibIdentities.absent(name)
    private fun effect(name: String): Hash = StdlibIdentities.effect(name) ?: StdlibIdentities.absent(name)

    val ABILITY_ARBITRARY: Hash get() = ability("Arbitrary")
    val ABILITY_DECODE: Hash get() = ability("Decode")
    val ABILITY_EMBEDDABLE: Hash get() = ability("Embeddable")
    val ABILITY_ENCODE: Hash get() = ability("Encode")
    val ABILITY_EQ: Hash get() = ability("Eq")
    val ABILITY_HASH: Hash get() = ability("Hash")
    val ABILITY_INSPECT: Hash get() = ability("Inspect")
    val ABILITY_LOGICALAND: Hash get() = ability("LogicalAnd")
    val ABILITY_LOGICALOR: Hash get() = ability("LogicalOr")
    val ABILITY_ORD: Hash get() = ability("Ord")

    val EFFECT_MUT: Hash get() = effect("Mut")
    val EFFECT_COMPTIME: Hash get() = effect("Comptime")

    fun resolveAbilityHash(name: String): Hash? = StdlibIdentities.ability(name)
    fun resolveEffectHash(name: String): Hash? = StdlibIdentities.effect(name)
    fun resolveTypeHash(name: String): Hash? = StdlibIdentities.type(name)
    fun resolveHash(name: String): Hash? = resolveAbilityHash(name) ?: resolveEffectHash(name) ?: resolveTypeHash(name)

    /** True for a perform of stdlib `Comptime.comptime`. */
    fun isComptime(effectHash: Hash?, operationName: String): Boolean =
        effectHash != null && effectHash == EFFECT_COMPTIME && operationName == "comptime"

    fun isInspect(hash: Hash?): Boolean = hash != null && hash == ABILITY_INSPECT
    fun isMut(hash: Hash?): Boolean = hash != null && hash == EFFECT_MUT
    fun isEq(hash: Hash?): Boolean = hash != null && hash == ABILITY_EQ
    fun isOrd(hash: Hash?): Boolean = hash != null && hash == ABILITY_ORD
    fun isHash(hash: Hash?): Boolean = hash != null && hash == ABILITY_HASH
    fun isNumAdd(hash: Hash?): Boolean = hash != null && hash == ability("NumAdd")
    fun isNumSub(hash: Hash?): Boolean = hash != null && hash == ability("NumSub")
    fun isNumMul(hash: Hash?): Boolean = hash != null && hash == ability("NumMul")
    fun isNumDiv(hash: Hash?): Boolean = hash != null && hash == ability("NumDiv")
    fun isNumMod(hash: Hash?): Boolean = hash != null && hash == ability("NumMod")
    /** Marker ability gating what a `comptime` block may return. */
    fun isEmbeddable(hash: Hash?): Boolean = hash != null && hash == ABILITY_EMBEDDABLE
    /** Generation: derived structurally like Eq, and a newtype opts in instead. */
    fun isArbitrary(hash: Hash?): Boolean = hash != null && hash == ABILITY_ARBITRARY

    private val CODEC = listOf(
        "Encode", "EncodeRepr", "Decode", "DecodeRepr", "StreamEncode", "StreamDecode",
        "EncodeFormat", "DecodeFormat", "StreamFormat", "StreamDecodeFormat", "Format",
    )
    private val ENCODE_FLAVORED = listOf("Encode", "EncodeRepr")
    private val DECODE_FLAVORED = listOf("Decode", "DecodeRepr")
    /** Abilities whose ops are target-directed decode ENTRY points (`from_json` …). */
    private val DECODE_ENTRY = listOf("FromJson")

    private fun among(hash: Hash?, names: List<String>): Boolean =
        hash != null && names.any { StdlibIdentities.ability(it) == hash }

    /**
     * The abilities an OPERATOR desugars to.
     *
     * `a + b` becomes `a.add { r = b }` and `a < b` becomes `a.lt { r = b }`, so
     * these operation names arrive at the compiler from the desugarer and from a
     * user's own method call alike. Asking the ABILITY and not the name is the
     * difference: a user ability declaring `add` is a different declaration with
     * a different identity, and must not inherit the treatment the arithmetic
     * intrinsics get.
     *
     * `neg` and `not` are deliberately absent — no ability declares them, so
     * they exist only as intrinsics and there is nothing to confuse them with.
     */
    private val OPERATOR = listOf(
        "Eq", "Ord", "LogicalAnd", "LogicalOr",
        "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod",
    )

    /** True for an ability an operator desugars to. See [OPERATOR]. */
    fun isOperatorAbility(hash: Hash?): Boolean = among(hash, OPERATOR)

    fun isCodecAbility(hash: Hash?): Boolean = among(hash, CODEC)
    /** Encode-flavored codec: codec auto-gen emits an encode-shaped impl body. */
    fun isEncodeFlavoredCodec(hash: Hash?): Boolean = among(hash, ENCODE_FLAVORED)
    /** Decode-flavored codec: codec auto-gen emits a decode-shaped impl body. */
    fun isDecodeFlavoredCodec(hash: Hash?): Boolean = among(hash, DECODE_FLAVORED)
    fun isDecodeEntryAbility(hash: Hash?): Boolean = among(hash, DECODE_ENTRY)

    /** Decode-entry ability identities, for seeding derived entry sets. */
    val decodeEntryAbilityHashes: Set<Hash> get() = DECODE_ENTRY.mapNotNull { StdlibIdentities.ability(it) }.toSet()

    /** Op names of the decode-entry abilities, from their stdlib declarations. */
    val decodeEntryOpNames: Set<String> get() = DECODE_ENTRY.flatMap { StdlibIdentities.abilityOperations(it) }.toSet()
}
