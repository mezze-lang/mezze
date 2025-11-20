package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Type signatures for NonEmptyList operations
 *
 * Note: NonEmptyList is currently represented using regular List types in the type system.
 * The non-emptiness invariant is enforced at the value level in stdlib.
 *
 * Corresponds to stdlib file:
 * - std/collections/nonemptylist.funk
 */
object NonEmptyListTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        // NonEmptyList operations are currently handled through regular List types
        // No additional native functions are registered for NonEmptyList at this time
        // The non-empty guarantee is maintained through the stdlib API design
    }
}
