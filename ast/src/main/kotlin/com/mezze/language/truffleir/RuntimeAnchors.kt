package com.mezze.language.truffleir

/**
 * Stdlib identities the RUNTIME recognises, written out because the runtime does
 * not depend on the typechecker that computes them (the runtime jar stays slim).
 *
 * One copy, for every runtime-side user. There used to be two copies of the Mut
 * hash, one per user, and they had drifted apart: the IR eraser's never matched,
 * so it never erased anything. `MarkerEffectsHashConsistencyTest` pins this one
 * to the identity the compiler computes; change the `Mut` effect, or anything its
 * declaration's names resolve to, and that test names the new value.
 */
object RuntimeAnchors {
    const val MUT_EFFECT_HASH_HEX =
        "b1e300f02b4155164f93d862b476ddb8915dede670b69e02197776b8e03b3caeae455395c86f124c99e116ae03881e7030c8a75f0dabce57eee513de76680eaa"
}
