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
        "f268c6e39679f3b95091f75b959ae77d3fb48662021bbfb4daa9a36340ccdd8832d6bc6d15f84e657470ea87d2981c849ff1ca76e8fa6510f60019acd4d598c2"
}
