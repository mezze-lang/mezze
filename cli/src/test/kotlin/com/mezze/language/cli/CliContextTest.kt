package com.mezze.language.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The global verbosity flag (F1). `initFrom` recognises `--verbose`/`-V` in any
 * position; the boundary in `main` reads [CliContext.verbose] to decide whether
 * a stack trace is printed. Env-var-driven paths (MEZZE_DEBUG/MEZZE_VERBOSE) are
 * not exercised here — env can't be mutated portably in-process — but the flag
 * arm is the primary path.
 */
class CliContextTest {

    @AfterEach fun reset() { CliContext.verbose = false }

    @Test fun `no flag leaves verbose off`() {
        CliContext.initFrom(arrayOf("dev", "--no-watch"))
        assertFalse(CliContext.verbose)
    }

    @Test fun `--verbose after the command enables verbose`() {
        CliContext.initFrom(arrayOf("dev", "--verbose"))
        assertTrue(CliContext.verbose)
    }

    @Test fun `--verbose before the command enables verbose`() {
        CliContext.initFrom(arrayOf("--verbose", "dev"))
        assertTrue(CliContext.verbose)
    }

    @Test fun `short -V enables verbose`() {
        CliContext.initFrom(arrayOf("build", "-V"))
        assertTrue(CliContext.verbose)
    }

    @Test fun `MezzeCliError carries message hint and cause`() {
        val cause = IllegalStateException("underlying")
        val e = MezzeCliError("front-door message", hint = "do this", cause = cause)
        assertEquals("front-door message", e.message)
        assertEquals("do this", e.hint)
        assertEquals(cause, e.cause)
    }

    @Test fun `MezzeCliError hint and cause are optional`() {
        val e = MezzeCliError("just a message")
        assertNull(e.hint)
        assertNull(e.cause)
    }
}
