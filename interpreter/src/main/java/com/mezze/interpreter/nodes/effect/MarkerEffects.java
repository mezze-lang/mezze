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
 * runtime jar stays slim), so we can't import the generated
 * {@code WellKnownHashConstants.EFFECT_MUT}. Instead we hardcode the
 * hex here and lock it down with a regression test in
 * {@code :typechecker} that asserts the two values are equal.
 */
public final class MarkerEffects {

    private MarkerEffects() {}

    /**
     * Content hash of the Mut effect declaration. Must match
     * {@code WellKnownHashConstants.EFFECT_MUT.getHex()} from the
     * generated typechecker file; the
     * {@code MarkerEffectsHashConsistencyTest} in :typechecker asserts
     * this and breaks the build if they drift.
     */
    public static final String MUT_EFFECT_HASH_HEX =
        "a0170b84db725d0a7e5e84a1bf06b194c340122a89494fc6d33703c2186e89cad5eb0bfe52761a0875aed1c919b2d26aaba004bbe21bb489edd2c27d58368429";

    /**
     * True if {@code hash} is the Mut effect hash. Cheap O(1) — the
     * hex String is compile-time interned by the JVM, so the equality
     * check reduces to a reference compare on common paths.
     */
    public static boolean isMutEffect(Hash hash) {
        return hash != null && hash.getHex().equals(MUT_EFFECT_HASH_HEX);
    }
}
