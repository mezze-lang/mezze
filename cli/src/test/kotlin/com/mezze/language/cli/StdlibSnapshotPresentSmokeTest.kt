package com.mezze.language.cli

import com.mezze.language.typechecker.StdlibSnapshotData
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #204 — CI smoke test for the bundled stdlib CBOR snapshot.
 *
 * StdlibSnapshotData is the fast-path source for user-jar / native-image
 * boot. When it silently fails to decode, the runtime falls through to
 * a much slower SQLite-or-source recompile path (~1.5s added to every
 * boot, see #201 root-cause). The fallback is silent enough that the
 * regression went unnoticed during validation of #220.
 *
 * This test fails any time the snapshot is absent or its decode fell
 * through. Catches:
 *   - field-default omissions in @Serializable types
 *   - schema drift between encoder and decoder
 *   - missing META-INF resource at packaging time
 *
 * Lives in `:cli:test` because that's the project that physically
 * generates the snapshot resource (via the compileStdlib task that
 * writes META-INF/mezze/stdlib-snapshot.cbor into the runtime jar).
 */
class StdlibSnapshotPresentSmokeTest {

    @Test
    fun `stdlib snapshot decoded successfully at boot`() {
        assertTrue(
            StdlibSnapshotData.present,
            "StdlibSnapshotData.present is false. The bundled CBOR resource " +
                "is missing or failed to decode. Check META-INF/mezze/stdlib-snapshot.cbor " +
                "in the runtime jar, and look at stderr during this test run for the " +
                "snapshot-decode warning (now always emitted — see #203)."
        )
    }

    @Test
    fun `snapshot has non-trivial payload`() {
        // Sanity check — present can be true with empty maps if the
        // decoder ever silently produces a no-op snapshot.
        assertTrue(StdlibSnapshotData.functions.isNotEmpty(),
            "snapshot is present but contains zero functions")
        assertTrue(StdlibSnapshotData.declHashes.isNotEmpty(),
            "snapshot is present but contains zero decl hashes")
        assertTrue(StdlibSnapshotData.moduleHashes.isNotEmpty(),
            "snapshot is present but contains zero module hashes")
        assertNotNull(StdlibSnapshotData.importContext,
            "snapshot is present but importContext is null")
    }
}
