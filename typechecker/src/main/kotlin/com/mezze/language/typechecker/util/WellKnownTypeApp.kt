package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typedast.Hash

/**
 * The single funnel for building a name-carried [MezzeType.TypeApplication].
 *
 * IDENTITY IS CENTRALIZED HERE. Every TypeApplication a declared type flows into
 * needs a content hash so it carries a real nominal identity (Declared, never
 * Named). Rather than make ~40 call sites each thread a hash, [of] resolves it in
 * one place, in precedence order:
 *   1. an explicit `typeHash` argument (the caller already has it) — wins;
 *   2. the STDLIB well-known table ([WellKnownHashConstants]) — for `Result`,
 *      `Option`, `Json`, … whose hashes are fixed at build time;
 *   3. an installed per-module REGISTRY resolver ([withHashResolver]) — for
 *      USER/LOCAL declared types (`Tree`, `Color`, `UserId`, …), whose hash is a
 *      pure function of the decl and always computable from the registry.
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

    /** Resolve a declared type's content hash: stdlib table, then the installed registry resolver. */
    fun resolveHash(constructor: String): Hash? =
        WellKnownHashConstants.resolveTypeHash(constructor) ?: hashResolver.get()?.invoke(constructor)

    fun of(
        constructor: String,
        args: List<MezzeType> = emptyList(),
        typeHash: Hash? = null
    ): MezzeType.TypeApplication {
        return MezzeType.TypeApplication(
            constructor = constructor,
            args = args,
            typeHash = typeHash ?: resolveHash(constructor)
        )
    }

    fun result(ok: MezzeType, err: MezzeType): MezzeType.TypeApplication =
        of("Result", listOf(ok, err))

    fun option(value: MezzeType): MezzeType.TypeApplication =
        of("Option", listOf(value))
}
