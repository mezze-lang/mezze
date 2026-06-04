package com.mezze.interpreter.nodes.effect;

import com.mezze.language.typedast.Hash;

/**
 * Effect-hash constants for runtime fast-path dispatch (#257).
 *
 * <p>Mut is a hermetic, marker-only effect — its handler impls
 * ({@code LocalMut}, {@code DebugLocalMut}) never reference
 * {@code resume}/{@code abort}, and Mut state can never leak across the
 * LocalMut handler boundary by the type system. So at runtime there is
 * nothing for the fiber/Continuation machinery to do: dispatch is just
 * "look up the handler record's op field and call it." This holder
 * carries the single hex constant the runtime needs to recognise the
 * Mut effect; {@link HandleNode} and {@link OpCallNode} both consult
 * it.
 *
 * <p>The interpreter module does not depend on the typechecker (the
 * runtime jar stays slim), so we can't import the generated
 * {@code WellKnownHashConstants.EFFECT_MUT}. Instead we hardcode the
 * hex here and lock it down with a regression test in
 * {@code :typechecker} that asserts the two values are equal.
 */
final class MarkerEffects {

    private MarkerEffects() {}

    /**
     * Content hash of the Mut effect declaration. Must match
     * {@code WellKnownHashConstants.EFFECT_MUT.getHex()} from the
     * generated typechecker file; the
     * {@code MarkerEffectsHashConsistencyTest} in :typechecker asserts
     * this and breaks the build if they drift.
     */
    static final String MUT_EFFECT_HASH_HEX =
        "05d9e70c83972f84e21da6095c1c22600854cc8c081877a5f7f49c689da6fb7265fdfdacc76b16d0561ad9be50bd32ff300977d466fbb16e98f988b41fbb6dba";

    /**
     * True if {@code hash} is the Mut effect hash. Cheap O(1) — the
     * hex String is compile-time interned by the JVM, so the equality
     * check reduces to a reference compare on common paths.
     */
    static boolean isMutEffect(Hash hash) {
        return hash != null && hash.getHex().equals(MUT_EFFECT_HASH_HEX);
    }
}
