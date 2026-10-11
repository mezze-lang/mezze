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
 * Mut effect; the BDSL Handle @Operation consults it.
 *
 * <p>The interpreter module does not depend on the typechecker (the
 * runtime jar stays slim), so the identity is read from
 * {@link com.mezze.language.truffleir.RuntimeAnchors}, which a regression
 * test pins to the identity the compiler computes.
 */
public final class MarkerEffects {

    private MarkerEffects() {}

    /** Identity of the Mut effect declaration — see RuntimeAnchors. */
    public static final String MUT_EFFECT_HASH_HEX =
        com.mezze.language.truffleir.RuntimeAnchors.MUT_EFFECT_HASH_HEX;

    /**
     * True if {@code hash} is the Mut effect hash. Cheap O(1) — the
     * hex String is compile-time interned by the JVM, so the equality
     * check reduces to a reference compare on common paths.
     */
    public static boolean isMutEffect(Hash hash) {
        return hash != null && hash.getHex().equals(MUT_EFFECT_HASH_HEX);
    }
}
