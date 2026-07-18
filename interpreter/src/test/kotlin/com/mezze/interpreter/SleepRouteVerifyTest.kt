package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * #274 — Async.sleep routes through Time.sleep. Verify it actually
 * blocks when SystemTime is installed.
 */
class SleepRouteVerifyTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `Async sleep blocks under SystemTime handler`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let main = {} -> perform SystemTime in perform LoomAsync in do
              Async.sleep { ms = 30 }
              42

            main {}
        """
        val t0 = System.nanoTime()
        val result = eval(source)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(42L, result)
        assertTrue(ms >= 25, "expected elapsed >= 25ms, got ${ms}ms")
    }
}
