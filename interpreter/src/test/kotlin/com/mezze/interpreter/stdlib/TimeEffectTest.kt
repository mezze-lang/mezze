package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for std::effects::time — the Time effect (3 ops) and its
 * two impls (SystemTime, MockTime).
 *
 * Design (#72/#75): rewritten from the old 12-op API. Values are
 * now opaque Instant/Duration native handles, extracted back to Int
 * via std::datetime accessors (instant_to_millis, duration_to_nanos).
 */
class TimeEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==================== MockTime ====================

    @Test
    fun `MockTime now returns the fixed Instant`() {
        val code = """
            use std::datetime::{ instant_from_millis, instant_to_millis }
            use std::effects::time::{ Time, FrozenTime }

            perform FrozenTime { fixed = instant_from_millis { n = 1_640_995_200_000 } } in do
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(1_640_995_200_000L, eval(code))
    }

    @Test
    fun `MockTime monotonic is zero`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_to_nanos }
            use std::effects::time::{ Time, FrozenTime }

            perform FrozenTime { fixed = instant_from_millis { n = 0 } } in do
              duration_to_nanos { d = Time.monotonic {} }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `MockTime sleep is a no-op and returns unit`() {
        // Doesn't actually block; test itself would time out if it did.
        val code = """
            use std::datetime::{ instant_from_millis, duration_seconds }
            use std::effects::time::{ Time, FrozenTime }

            perform FrozenTime { fixed = instant_from_millis { n = 0 } } in do
              Time.sleep { d = duration_seconds { n = 9999 } }
              42
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    // ==================== SystemTime — light checks ====================

    @Test
    fun `SystemTime now is after epoch`() {
        val code = """
            use std::datetime::{ epoch, before }
            use std::effects::time::{ Time, SystemTime }

            perform SystemTime in do
              let n = Time.now {}
              before { a = epoch {}, b = n }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `SystemTime monotonic returns a Duration without crashing`() {
        val code = """
            use std::datetime::{ is_negative_duration }
            use std::effects::time::{ Time, SystemTime }

            perform SystemTime in do
              let m = Time.monotonic {}
              # System.nanoTime is an arbitrary origin — sign is not
              # guaranteed. We just check we got a Bool back.
              is_negative_duration { d = m }
        """.trimIndent()
        val r = eval(code)
        assertTrue(r is Boolean)
    }

    @Test
    fun `SystemTime monotonic advances (non-negative delta)`() {
        val code = """
            use std::datetime::{ sub_duration, is_negative_duration }
            use std::effects::time::{ Time, SystemTime }

            perform SystemTime in do
              let m1 = Time.monotonic {}
              let m2 = Time.monotonic {}
              # m2 >= m1 → m2 - m1 is non-negative
              is_negative_duration { d = sub_duration { a = m2, b = m1 } }
        """.trimIndent()
        assertEquals(false, eval(code))
    }

    // ==================== Sugar helpers ====================

    @Test
    fun `current_datetime under MockTime returns 1970 in UTC`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc }
            use std::effects::time::{ Time, FrozenTime, current_datetime }

            perform FrozenTime { fixed = instant_from_millis { n = 0 } } in do
              current_datetime { z = utc {} }
        """.trimIndent()
        val r = eval(code) as MezzeRecord
        assertEquals(1970L, r.getField("year"))
        assertEquals(1L, r.getField("month"))
        assertEquals(1L, r.getField("day"))
    }

    @Test
    fun `current_year sugar`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc }
            use std::effects::time::{ Time, FrozenTime, current_year }

            perform FrozenTime { fixed = instant_from_millis { n = 1_700_000_000_000 } } in do
              current_year { z = utc {} }
        """.trimIndent()
        // 1_700_000_000_000 ms → 2023-11-14 in UTC
        assertEquals(2023L, eval(code))
    }

    @Test
    fun `current_month sugar`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc }
            use std::effects::time::{ Time, FrozenTime, current_month }

            perform FrozenTime { fixed = instant_from_millis { n = 1_700_000_000_000 } } in do
              current_month { z = utc {} }
        """.trimIndent()
        assertEquals(11L, eval(code))
    }

    @Test
    fun `elapsed_since with FrozenTime is zero (frozen clock)`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_to_millis }
            use std::effects::time::{ Time, FrozenTime, elapsed_since }

            perform FrozenTime { fixed = instant_from_millis { n = 100 } } in do
              let start = Time.now {}
              # FrozenTime.now never advances, so elapsed is zero
              duration_to_millis { d = elapsed_since { s = start } }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    // ==================== MockTime (advancing) ====================

    @Test
    fun `MockTime advances on sleep`() {
        val code = """
            use std::datetime::{ epoch, instant_to_millis, duration_seconds, test_clock_at }
            use std::effects::time::{ Time, MockTime }

            let clock = test_clock_at { i = epoch {} }
            perform MockTime { clock = clock } in do
              Time.sleep { d = duration_seconds { n = 5 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(5_000L, eval(code))
    }

    @Test
    fun `MockTime multiple sleeps accumulate`() {
        val code = """
            use std::datetime::{ instant_from_millis, instant_to_millis, duration_millis, test_clock_at }
            use std::effects::time::{ Time, MockTime }

            let clock = test_clock_at { i = instant_from_millis { n = 1000 } }
            perform MockTime { clock = clock } in do
              Time.sleep { d = duration_millis { n = 250 } }
              Time.sleep { d = duration_millis { n = 750 } }
              instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertEquals(2_000L, eval(code))
    }

    @Test
    fun `MockTime elapsed_since measures advanced time`() {
        val code = """
            use std::datetime::{ epoch, duration_to_millis, duration_seconds, test_clock_at }
            use std::effects::time::{ Time, MockTime, elapsed_since }

            let clock = test_clock_at { i = epoch {} }
            perform MockTime { clock = clock } in do
              let start = Time.now {}
              Time.sleep { d = duration_seconds { n = 3 } }
              duration_to_millis { d = elapsed_since { s = start } }
        """.trimIndent()
        assertEquals(3_000L, eval(code))
    }

    // ==================== Codecs ====================

    @Test
    fun `Instant encode then decode roundtrips`() {
        val code = """
            use std::datetime::{ instant_from_millis, instant_encode, instant_decode, instant_to_millis }

            let orig = instant_from_millis { n = 1_700_000_000_000 }
            match instant_decode { s = instant_encode { i = orig } } is
              Ok { val = round } -> instant_to_millis { i = round }
              Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(1_700_000_000_000L, eval(code))
    }

    @Test
    fun `Duration encode then decode roundtrips`() {
        val code = """
            use std::datetime::{ duration_seconds, duration_encode, duration_decode, duration_to_seconds }

            let orig = duration_seconds { n = 42 }
            match duration_decode { s = duration_encode { d = orig } } is
              Ok { val = round } -> duration_to_seconds { d = round }
              Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `Zone encode then decode roundtrips`() {
        val code = """
            use std::datetime::{ utc, zone_encode, zone_decode, zone_id }

            match zone_decode { s = zone_encode { z = utc {} } } is
              Ok { val = round } -> zone_id { z = round }
              Err { error } -> error
        """.trimIndent()
        assertEquals("UTC", eval(code))
    }

    @Test
    fun `Instant decode of invalid string returns Err`() {
        val code = """
            use std::datetime::{ instant_decode }
            match instant_decode { s = "not-an-instant" } is
              Ok { val } -> "ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== TestClock direct ops ====================

    @Test
    fun `test_clock_set overrides the current Instant`() {
        val code = """
            use std::datetime::{ epoch, instant_from_millis, instant_to_millis, test_clock_at, test_clock_set, test_clock_now }
            let clock = test_clock_at { i = epoch {} }
            test_clock_set { c = clock, i = instant_from_millis { n = 5_000 } }
            instant_to_millis { i = test_clock_now { c = clock } }
        """.trimIndent()
        assertEquals(5_000L, eval(code))
    }

    @Test
    fun `test_clock_advance is additive across calls`() {
        val code = """
            use std::datetime::{ epoch, instant_to_millis, duration_millis, test_clock_at, test_clock_advance, test_clock_now }
            let clock = test_clock_at { i = epoch {} }
            test_clock_advance { c = clock, d = duration_millis { n = 100 } }
            test_clock_advance { c = clock, d = duration_millis { n = 250 } }
            test_clock_advance { c = clock, d = duration_millis { n = 650 } }
            instant_to_millis { i = test_clock_now { c = clock } }
        """.trimIndent()
        assertEquals(1_000L, eval(code))
    }

    @Test
    fun `test_clock_monotonic tracks the clock's Instant`() {
        val code = """
            use std::datetime::{ epoch, duration_seconds, duration_to_nanos, test_clock_at, test_clock_advance, test_clock_monotonic }
            let clock = test_clock_at { i = epoch {} }
            test_clock_advance { c = clock, d = duration_seconds { n = 3 } }
            duration_to_nanos { d = test_clock_monotonic { c = clock } }
        """.trimIndent()
        assertEquals(3_000_000_000L, eval(code))
    }

    // ==================== Fast field accessors parity ====================
    //
    // The seven fast-path accessors (year/month/day/hour/minute/second/nanos)
    // must agree with what decompose returns for the same Instant + Zone.

    @Test
    fun `year accessor matches decompose result`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc, decompose, year }
            let i = instant_from_millis { n = 1_700_000_000_000 }
            let z = utc {}
            {
              fast = year { i = i, z = z },
              slow = (decompose { i = i, z = z }).year,
            }
        """.trimIndent()
        val r = eval(code) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(r.getField("slow"), r.getField("fast"))
        assertEquals(2023L, r.getField("fast"))
    }

    @Test
    fun `all seven fast accessors agree with decompose`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc, decompose, year, month, day, hour, minute, second, nanos }
            let i = instant_from_millis { n = 1_700_000_000_000 }
            let z = utc {}
            let dt = decompose { i = i, z = z }
            {
              y_ok = year   { i = i, z = z } == dt.year,
              mo_ok = month { i = i, z = z } == dt.month,
              d_ok  = day   { i = i, z = z } == dt.day,
              h_ok  = hour  { i = i, z = z } == dt.hour,
              mi_ok = minute { i = i, z = z } == dt.minute,
              s_ok  = second { i = i, z = z } == dt.second,
              n_ok  = nanos  { i = i, z = z } == dt.nanos,
            }
        """.trimIndent()
        val r = eval(code) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(true, r.getField("y_ok"))
        assertEquals(true, r.getField("mo_ok"))
        assertEquals(true, r.getField("d_ok"))
        assertEquals(true, r.getField("h_ok"))
        assertEquals(true, r.getField("mi_ok"))
        assertEquals(true, r.getField("s_ok"))
        assertEquals(true, r.getField("n_ok"))
    }

    // ==================== FrozenTime monotonic returns Duration.zero ====================

    @Test
    fun `FrozenTime monotonic equals duration_zero`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_zero, duration_to_nanos }
            use std::effects::time::{ Time, FrozenTime }

            perform FrozenTime { fixed = instant_from_millis { n = 999 } } in
              duration_to_nanos { d = Time.monotonic {} }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    // ==================== elapsed_monotonic_since under MockTime ====================

    @Test
    fun `elapsed_monotonic_since under MockTime measures advanced monotonic`() {
        val code = """
            use std::datetime::{ epoch, duration_to_millis, duration_seconds, test_clock_at }
            use std::effects::time::{ Time, MockTime, elapsed_monotonic_since }

            let clock = test_clock_at { i = epoch {} }
            perform MockTime { clock = clock } in do
              let mark = Time.monotonic {}
              Time.sleep { d = duration_seconds { n = 2 } }
              duration_to_millis { d = elapsed_monotonic_since { s = mark } }
        """.trimIndent()
        assertEquals(2_000L, eval(code))
    }

    // ==================== Duration codec edge cases ====================

    @Test
    fun `Duration codec roundtrips negative`() {
        val code = """
            use std::datetime::{ duration_seconds, negate_duration, duration_encode, duration_decode, duration_to_seconds }
            let neg = negate_duration { d = duration_seconds { n = 7 } }
            match duration_decode { s = duration_encode { d = neg } } is
              Ok { val = round } -> duration_to_seconds { d = round }
              Err { error } -> 0
        """.trimIndent()
        assertEquals(-7L, eval(code))
    }

    @Test
    fun `Duration codec roundtrips large value`() {
        val code = """
            use std::datetime::{ duration_days, duration_encode, duration_decode, duration_to_seconds }
            let big = duration_days { n = 365 }
            match duration_decode { s = duration_encode { d = big } } is
              Ok { val = round } -> duration_to_seconds { d = round }
              Err { error } -> 0
        """.trimIndent()
        assertEquals(365L * 24L * 3600L, eval(code))
    }

    // ==================== Zone codec on IANA name ====================

    @Test
    fun `Zone codec roundtrips IANA zone`() {
        val code = """
            use std::datetime::{ zone_of, zone_encode, zone_decode, zone_id }
            use std::result

            match zone_of { id = "America/New_York" } is
              Ok { val = z } ->
                match zone_decode { s = zone_encode { z = z } } is
                  Ok { val = round } -> zone_id { z = round }
                  Err { error } -> "err"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("America/New_York", eval(code))
    }

    // ==================== Codec Err on garbage ====================

    @Test
    fun `Duration decode of invalid string returns Err`() {
        val code = """
            use std::datetime::{ duration_decode }
            match duration_decode { s = "not-a-duration" } is
              Ok { val } -> "ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    @Test
    fun `Zone decode of invalid string returns Err`() {
        val code = """
            use std::datetime::{ zone_decode }
            match zone_decode { s = "Not/A/Zone" } is
              Ok { val } -> "ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }
}
