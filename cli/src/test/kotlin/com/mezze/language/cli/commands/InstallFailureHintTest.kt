package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A provider install that fails for a known host reason gets a one-line answer,
 * not a bare subprocess tail.
 *
 * The install template belongs to the provider package, so mez cannot fix the
 * command — but nixpkgs' GraalPy ships without pip, so `graalpy -m pip install …`
 * dies "No module named pip" and the user was left reading a Python traceback
 * about a command they did not write. The demo worked around it in a devenv
 * `enterShell`; the fix mez can make is to say so.
 */
class InstallFailureHintTest {
    private val cmd = SyncCommand()

    @Test fun `missing pip names ensurepip`() {
        val hint = cmd.installFailureHint("/nix/store/x/bin/graalpy: No module named pip\n")
        assertTrue(hint != null && hint.contains("ensurepip"), "got: $hint")
    }

    @Test fun `missing ensurepip too says the distribution is the problem`() {
        val hint = cmd.installFailureHint("No module named ensurepip\n")
        assertTrue(hint != null && hint.contains("bundles pip"), "got: $hint")
    }

    /** An unrecognised failure gets no hint — a guess is worse than the tail. */
    @Test fun `an unknown failure gets no hint`() {
        assertNull(cmd.installFailureHint("error: network unreachable"))
    }
}
