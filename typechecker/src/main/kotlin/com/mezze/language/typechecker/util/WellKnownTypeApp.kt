package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.WellKnownHashes

/**
 * Helper for constructing `MezzeType.TypeApplication` with the constructor's
 * content hash already populated. The 341+ literal `TypeApplication("Foo", ..)`
 * call sites in the typechecker should go through this so that:
 *
 *   1. `typeHash` is set whenever we know the type (rename-safe identity).
 *   2. Future code that prefers `typeHash == X` over `constructor == "X"`
 *      doesn't need to add hash-lookup boilerplate at every call site.
 *
 * For built-in synthetic types (Option/Result/Bool/Ordering — built in
 * Kotlin, not parsed from `.mz`) this returns null hash. Those types are
 * protected from user redeclaration by DuplicateDeclarationValidator, so
 * the lack of a hash doesn't create ambiguity in practice — but a future
 * refactor that surfaces them through the parser will fix this gap.
 */
internal object WellKnownTypeApp {

    /**
     * Build a TypeApplication with the well-known type hash auto-resolved.
     * Equivalent to:
     *
     *   com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, args, WellKnownHashes.resolveTypeHash(name))
     *
     * — but the indirection makes the intent explicit and gives a single
     * place to special-case names if needed.
     */
    fun of(
        constructor: String,
        args: List<MezzeType> = emptyList(),
        typeHash: com.mezze.language.typedast.Hash? = null
    ): MezzeType.TypeApplication {
        // Explicit `typeHash` (e.g. from a user-defined ParsedTypeDecl) wins;
        // otherwise auto-resolve via the well-known registry. Either way
        // the resulting TypeApplication is hash-bearing whenever possible.
        return MezzeType.TypeApplication(
            constructor = constructor,
            args = args,
            typeHash = typeHash ?: WellKnownHashes.resolveTypeHash(constructor)
        )
    }
}
