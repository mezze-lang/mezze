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
        "b7c8d7273f200e6a3bcedd6330db64666ad497ddc81726c8c8bbc81cc1aa9ade11d5de3f3486b11b442f95b3c523fe7430f98bbe9922ad65f43fb4192f63e6f2"
}
