package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `Time.advance { d }` across all three performers: a real clock advances
 * by waiting, a virtual one jumps, a frozen one stays put.
 */
class TimeAdvanceEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `MockTime advance moves the clock`() {
        val code = """
            use std::datetime::{ test_clock_at, instant_from_millis, instant_to_millis, duration_seconds }
            use std::effects::time::{ Time, MockTime }

            let clock = test_clock_at { i = instant_from_millis { n = 1_000 } }
            perform MockTime { clock = clock } in do
              Time.advance { d = duration_seconds { n = 5 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(6_000L, eval(code))
    }

    @Test
    fun `MockTime advance composes with sleep`() {
        val code = """
            use std::datetime::{ test_clock_at, instant_from_millis, instant_to_millis, duration_seconds }
            use std::effects::time::{ Time, MockTime }

            let clock = test_clock_at { i = instant_from_millis { n = 0 } }
            perform MockTime { clock = clock } in do
              Time.sleep { d = duration_seconds { n = 1 } }
              Time.advance { d = duration_seconds { n = 2 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(3_000L, eval(code))
    }

    @Test
    fun `FrozenTime stays frozen when advanced`() {
        val code = """
            use std::datetime::{ instant_from_millis, instant_to_millis, duration_seconds }
            use std::effects::time::{ Time, FrozenTime }

            perform FrozenTime { fixed = instant_from_millis { n = 7_777 } } in do
              Time.advance { d = duration_seconds { n = 5 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(7_777L, eval(code))
    }

    @Test
    fun `SystemTime advance actually waits`() {
        val code = """
            use std::datetime::{ duration_millis, duration_to_nanos, sub_duration }
            use std::effects::time::{ Time, SystemTime }

            perform SystemTime in do
              let t0 = Time.monotonic {}
              Time.advance { d = duration_millis { n = 40 } }
              let t1 = Time.monotonic {}
              duration_to_nanos { d = sub_duration { a = t1, b = t0 } }
        """.trimIndent()
        val elapsed = eval(code) as Long
        assertTrue(elapsed >= 35_000_000L, "a real clock advances by waiting; elapsed ${elapsed}ns")
    }

    @Test
    fun `the pub let wrapper advances too`() {
        val code = """
            use std::datetime::{ test_clock_at, instant_from_millis, instant_to_millis, duration_seconds }
            use std::effects::time::{ Time, MockTime, advance }

            let clock = test_clock_at { i = instant_from_millis { n = 500 } }
            perform MockTime { clock = clock } in do
              advance { d = duration_seconds { n = 1 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(1_500L, eval(code))
    }

    @Test
    fun `one helper written against Time advance works for any clock`() {
        // The reason `advance` is an op rather than a TestClock function.
        val code = """
            use std::datetime::{ test_clock_at, instant_from_millis, instant_to_millis, duration_millis, Duration }
            use std::effects::time::{ Time, MockTime }

            let idle_until_next: { d: Duration } -> {} <Time> = { d } -> Time.advance { d = d }

            let clock = test_clock_at { i = instant_from_millis { n = 0 } }
            perform MockTime { clock = clock } in do
              idle_until_next { d = duration_millis { n = 250 } }
              idle_until_next { d = duration_millis { n = 750 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(1_000L, eval(code))
    }
}
