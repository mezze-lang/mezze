package com.mezze.language.store

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MezzeStoreOpenerTest {

    @BeforeEach
    fun setUp() {
        MezzeStoreOpener.reset()
    }

    @AfterEach
    fun tearDown() {
        MezzeStoreOpener.reset()
    }

    @Test
    fun `re-registering the same instance is silent`() {
        val opener = MezzeStoreOpener { _ -> InMemoryMezzeStore() }
        val captured = withCapturedStderr {
            MezzeStoreOpener.register(opener)
            MezzeStoreOpener.register(opener)
        }
        assertSame(opener, MezzeStoreOpener.current())
        assertTrue(captured.isEmpty(),
            "Expected no stderr output for idempotent re-registration; got: $captured")
    }

    @Test
    fun `overwriting with a different instance logs a warning`() {
        val first = MezzeStoreOpener { _ -> InMemoryMezzeStore() }
        val second = MezzeStoreOpener { _ -> InMemoryMezzeStore() }
        val captured = withCapturedStderr {
            MezzeStoreOpener.register(first)
            MezzeStoreOpener.register(second)
        }
        assertSame(second, MezzeStoreOpener.current(),
            "Latest registration should win")
        assertTrue(captured.contains("overwriting an existing registration"),
            "Expected warning on stderr; got: $captured")
    }

    private fun withCapturedStderr(block: () -> Unit): String {
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setErr(originalErr)
        }
        return buffer.toString()
    }
}
