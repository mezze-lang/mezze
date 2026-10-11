package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The provider↔toolchain version handshake: `mez sync` refuses a provider whose
 * `[polyglot.provider].min_toolchain` demands a newer RELEASE than the running
 * `mez`, so a consumer on a stale toolchain fails loudly at sync instead of
 * hitting opaque runtime errors from stdlib it doesn't have.
 *
 * Only clean `MAJOR.MINOR.PATCH` on BOTH sides decides; a dev `nightly-*` build
 * (or any unparseable version) is assumed current and never blocks.
 */
class ToolchainHandshakeTest {
    private val cmd = SyncCommand()

    @Test fun `older release is rejected`() {
        assertTrue(cmd.isOlderRelease(current = "0.4.0", min = "0.5.1"))
        assertTrue(cmd.isOlderRelease(current = "0.5.0", min = "0.5.1"))
        assertTrue(cmd.isOlderRelease(current = "1.9.9", min = "2.0.0"))
    }

    @Test fun `equal or newer release is accepted`() {
        assertFalse(cmd.isOlderRelease(current = "0.5.1", min = "0.5.1"))
        assertFalse(cmd.isOlderRelease(current = "0.6.0", min = "0.5.1"))
        assertFalse(cmd.isOlderRelease(current = "2.0.0", min = "1.9.9"))
    }

    @Test fun `a dev nightly toolchain never blocks`() {
        // The maintainer iterating on main must not be gated by a future min.
        assertFalse(cmd.isOlderRelease(current = "nightly-202610111200", min = "99.0.0"))
    }

    @Test fun `an unparseable min is ignored`() {
        assertFalse(cmd.isOlderRelease(current = "0.5.1", min = "latest"))
        assertFalse(cmd.isOlderRelease(current = "0.5.1", min = ""))
    }

    @Test fun `pre-release suffixes compare on the numeric core`() {
        // We compare the leading MAJOR.MINOR.PATCH and ignore any suffix.
        assertTrue(cmd.isOlderRelease(current = "0.4.0-rc1", min = "0.5.0"))
        assertFalse(cmd.isOlderRelease(current = "0.5.0-rc1", min = "0.5.0"))
    }

    @Test fun `toolchainTooOldFor returns the running version only on violation`() {
        // Against an impossibly-high min, a real (semver) release would be
        // reported; a nightly dev build returns null (not decidable → skip).
        val current = com.mezze.language.cli.BuildInfo.VERSION
        val verdict = cmd.toolchainTooOldFor("99999.0.0")
        if (cmd.isOlderRelease(current, "99999.0.0")) {
            kotlin.test.assertEquals(current, verdict)
        } else {
            kotlin.test.assertEquals(null, verdict)
        }
    }
}
