package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typedast.Hash

/**
 * The single funnel for building a name-carried [MezzeType.TypeApplication].
 *
 * IDENTITY IS CENTRALIZED HERE. Every TypeApplication a declared type flows into
 * needs a content hash so it carries a real nominal identity (Declared, never
 * Named). Rather than make ~40 call sites each thread a hash, [of] resolves it in
 * one place, in precedence order — nearest scope first, because a NAME is not an
 * identity and only the nearest declaration says which type it is:
 *   1. an explicit `typeHash` argument (the caller already has it) — wins;
 *   2. an installed per-module REGISTRY resolver ([withHashResolver]) — whatever
 *      THIS module declares or imports (`Tree`, `Color`, `UserId`, and the stdlib's
 *      own types when imported), whose hash is a pure function of the decl;
 *   3. the STDLIB well-known table ([WellKnownHashConstants]) — `Result`, `Option`,
 *      `Json`, … for the paths where no registry is installed.
 *
 * `null` only survives for a name that resolves to no declaration at all — a
 * builtin (`List`/`Bool`) or `__Native*` handle (identified by reserved name, no
 * content to hash), or a genuine unknown type (a checker error, handled upstream).
 */
internal object WellKnownTypeApp {

    /**
     * Per-thread registry-backed hash resolver, installed by the typechecker for
     * the duration of a module check via [withHashResolver]. Thread-local because
     * typechecking runs concurrently and the registry is per-module.
     */
    @PublishedApi
    internal val hashResolver = ThreadLocal<((String) -> Hash?)?>()

    /** Install [resolver] for the duration of [block] (nesting-safe). Inline so a
     *  non-local `return` from [block] restores the previous resolver via finally. */
    inline fun <T> withHashResolver(noinline resolver: (String) -> Hash?, block: () -> T): T {
        val prev = hashResolver.get()
        hashResolver.set(resolver)
        try {
            return block()
        } finally {
            hashResolver.set(prev)
        }
    }

    /**
     * Resolve a declared type's content hash: the module's own REGISTRY first, the
     * stdlib table second.
     *
     * The order is the whole point. The stdlib table is a NAME→hash map, so asking
     * it first meant a module declaring `type Option = | Yes, No |` got the stdlib
     * `Option`'s hash stamped as its identity — two unrelated types sharing one
     * identity, after which `Option Int` unified with the local `Option`, the
     * program type-checked, and it died at runtime with "Non-exhaustive match: no
     * variant tag matched — typechecker exhaustiveness guarantee violated".
     *
     * The registry answers for whatever THIS module can see, and that includes the
     * stdlib's own types when imported — so a genuine `Option` resolves to the same
     * hash either way, and only a shadowing declaration changes answer. The table is
     * what remains for the paths where no registry is installed.
     */
    fun resolveHash(constructor: String): Hash? =
        hashResolver.get()?.invoke(constructor) ?: WellKnownHashConstants.resolveTypeHash(constructor)

    fun of(
        constructor: String,
        args: List<MezzeType> = emptyList(),
        typeHash: Hash? = null
    ): MezzeType.TypeApplication {
        val hash = typeHash ?: resolveHash(constructor)
        // S3.1: identity decided by THE canonical factory, not open-coded here.
        val identity = TypeIdentity.derive(constructor, hash, allowBuiltin = true)
        return MezzeType.TypeApplication(
            constructor = constructor,
            args = args,
            identity = identity
        )
    }

    fun result(ok: MezzeType, err: MezzeType): MezzeType.TypeApplication =
        of("Result", listOf(ok, err))

    fun option(value: MezzeType): MezzeType.TypeApplication =
        of("Option", listOf(value))
}
