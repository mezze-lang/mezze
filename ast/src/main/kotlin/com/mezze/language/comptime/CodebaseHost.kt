package com.mezze.language.comptime

/**
 * The compiler's answers to `std::codebase` questions, during compile-time
 * evaluation (introspection §9).
 *
 * ## Why an interface here
 *
 * The answers come from the store, through `CodebaseQueries` in `:codebase`; the
 * question is asked by a native running inside the interpreter. Neither may
 * depend on the other — the interpreter must stay free of the type checker and
 * the store's implementations — so they meet in `:ast`, as [ComptimeEvaluator]
 * does.
 *
 * ## Why the answers are [HostValue]s
 *
 * Each answer is a value of a type declared in `std/codebase.mz` or
 * `std/syntax.mz`. The interpreter
 * turns a [HostValue] into a Mezze value without knowing what it means, so the
 * shapes are decided in exactly two places, which must agree: the Mezze types,
 * and the encoder that implements this interface. The end-to-end tests run the
 * one against the other.
 *
 * A declaration is named by its hash, as hex. Hashes are the identity; a name
 * is resolved to one by [resolve], once.
 *
 * Compile time only: there is no implementation at run time, and a native that
 * finds none fails loudly instead of answering from nothing.
 */
interface CodebaseHost {
    /** `Option DeclRef` */
    fun resolve(module: String, name: String): HostValue
    /** `Option DeclInfo` */
    fun info(hash: String): HostValue
    /** `[DeclRef]` */
    fun decls(module: String): HostValue
    /** `[Str]` */
    fun modules(prefix: String): HostValue
    /** `Option Str` */
    fun moduleDoc(module: String): HostValue
    /** `Option TypeShape` */
    fun signature(hash: String): HostValue
    /** `Option Definition` */
    fun definition(hash: String): HostValue
    /** `Option Str` */
    fun source(hash: String): HostValue
    /** `[ImplInfo]` */
    fun implsOf(abilityHash: String): HostValue
    /** `[ImplInfo]` */
    fun implsFor(typeHash: String): HostValue
    /** `[EffectRef]` */
    fun effectsOf(hash: String): HostValue
    /** `[DeclRef]` */
    fun performing(effectHash: String): HostValue
    /** `[DeclRef]` */
    fun requiring(abilityHash: String): HostValue
    /** `[DeclRef]` */
    fun performersOf(effectHash: String): HostValue
    /** `[DeclRef]` */
    fun callers(hash: String): HostValue
    /** `[DeclRef]` */
    fun callees(hash: String): HostValue
    /** `[DeclRef]` */
    fun references(hash: String): HostValue
    /** `[Span]` */
    fun usages(hash: String): HostValue
    /** `Option DeclRef` — the declaration whose (possibly specialised) function has this hash. */
    fun ref(functionHash: String): HostValue
    /** `[AbilityInfo]` */
    fun abilitiesOf(typeHash: String): HostValue
    /** `[DeclRef]` for the `DeclQuery` record [query]. */
    fun search(query: HostValue): HostValue
    /** `Option SyntaxDecl` (`std::syntax`): the declaration as written, with the compiler's facts on each node. */
    fun syntax(hash: String): HostValue
    /** `[SyntaxDecl]`: every declaration of [module] as written, `use` lines included, in order. */
    fun moduleSyntax(module: String): HostValue
    /** `[Occurrence]`: every written node of [kind] (a `std::syntax` tag), spelling [symbol] if given. */
    fun occurrences(kind: String, symbol: String?): HostValue
}

/**
 * A Mezze value as plain data, crossing from the compiler to the interpreter.
 *
 * Only what `std/codebase.mz` uses: no functions, no handles, nothing that
 * could not be written down — the same rule `Embeddable` states for a comptime
 * result.
 */
sealed interface HostValue {
    data class Str(val value: String) : HostValue
    data class IntV(val value: Long) : HostValue
    data class Bool(val value: Boolean) : HostValue
    data class FloatV(val value: Double) : HostValue
    data class Seq(val items: List<HostValue>) : HostValue

    /** A record; fields in written order. */
    data class Record(val fields: List<Pair<String, HostValue>>) : HostValue

    /** A variant constructor. [payload] is its record, or null for one that carries nothing. */
    data class Variant(val tag: String, val payload: Record?) : HostValue

    /**
     * `Option`, explicitly. The runtime builds `Some`/`None` through dedicated
     * factories, and a generic variant spelled "Some" is not guaranteed to be
     * the same value, so an option is never encoded as a [Variant].
     */
    data class Opt(val value: HostValue?) : HostValue

    companion object {
        fun record(vararg fields: Pair<String, HostValue>) = Record(fields.toList())
        fun str(value: String?): HostValue = Opt(value?.let { Str(it) })
    }
}
