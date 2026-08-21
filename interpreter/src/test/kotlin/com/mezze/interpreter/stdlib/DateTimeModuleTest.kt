package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the std::datetime module — pure primitive types Instant,
 * Duration, Zone, DateTime and their pure helpers. #72/#74.
 *
 * These tests never use the Time effect — they only exercise the
 * pure surface (constructors, arithmetic, decompose, format, parse).
 * For clock reads and Time-effect ops, see TimeEffectTest.
 */
class DateTimeModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==================== Duration constructors ====================

    @Test
    fun `duration_millis roundtrip`() {
        val code = """
            use std::datetime::{ duration_millis, duration_to_millis }
            duration_to_millis { d = duration_millis { n = 1500 } }
        """.trimIndent()
        assertEquals(1500L, eval(code))
    }

    @Test
    fun `duration_seconds roundtrip via nanos`() {
        val code = """
            use std::datetime::{ duration_seconds, duration_to_nanos }
            duration_to_nanos { d = duration_seconds { n = 3 } }
        """.trimIndent()
        assertEquals(3_000_000_000L, eval(code))
    }

    @Test
    fun `duration_hours to seconds`() {
        val code = """
            use std::datetime::{ duration_hours, duration_to_seconds }
            duration_to_seconds { d = duration_hours { n = 2 } }
        """.trimIndent()
        assertEquals(7200L, eval(code))
    }

    @Test
    fun `duration_zero to nanos is zero`() {
        val code = """
            use std::datetime::{ duration_zero, duration_to_nanos }
            duration_to_nanos { d = duration_zero {} }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    // ==================== Duration arithmetic ====================

    @Test
    fun `add_duration accumulates`() {
        val code = """
            use std::datetime::{ duration_seconds, add_duration, duration_to_seconds }
            duration_to_seconds {
              d = add_duration {
                a = duration_seconds { n = 10 },
                b = duration_seconds { n = 25 }
              }
            }
        """.trimIndent()
        assertEquals(35L, eval(code))
    }

    @Test
    fun `sub_duration can go negative`() {
        val code = """
            use std::datetime::{ duration_seconds, sub_duration, duration_to_seconds }
            duration_to_seconds {
              d = sub_duration {
                a = duration_seconds { n = 3 },
                b = duration_seconds { n = 10 }
              }
            }
        """.trimIndent()
        assertEquals(-7L, eval(code))
    }

    @Test
    fun `negate_duration flips sign`() {
        val code = """
            use std::datetime::{ duration_seconds, negate_duration, duration_to_seconds }
            duration_to_seconds {
              d = negate_duration { d = duration_seconds { n = 5 } }
            }
        """.trimIndent()
        assertEquals(-5L, eval(code))
    }

    @Test
    fun `abs_duration of negative is positive`() {
        val code = """
            use std::datetime::{ duration_seconds, negate_duration, abs_duration, duration_to_seconds }
            duration_to_seconds {
              d = abs_duration { d = negate_duration { d = duration_seconds { n = 7 } } }
            }
        """.trimIndent()
        assertEquals(7L, eval(code))
    }

    @Test
    fun `is_negative_duration true for negative`() {
        val code = """
            use std::datetime::{ duration_seconds, negate_duration, is_negative_duration }
            is_negative_duration { d = negate_duration { d = duration_seconds { n = 1 } } }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    // ==================== Instant construction + accessors ====================

    @Test
    fun `epoch is zero millis`() {
        val code = """
            use std::datetime::{ epoch, instant_to_millis }
            instant_to_millis { i = epoch {} }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `instant_from_millis roundtrip`() {
        val code = """
            use std::datetime::{ instant_from_millis, instant_to_millis }
            instant_to_millis { i = instant_from_millis { n = 1_700_000_000_000 } }
        """.trimIndent()
        assertEquals(1_700_000_000_000L, eval(code))
    }

    @Test
    fun `instant_to_seconds truncates`() {
        val code = """
            use std::datetime::{ instant_from_millis, instant_to_seconds }
            instant_to_seconds { i = instant_from_millis { n = 1500 } }
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    // ==================== Instant arithmetic ====================

    @Test
    fun `add duration to instant`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_seconds, add, instant_to_millis }
            instant_to_millis {
              i = add { i = instant_from_millis { n = 1000 }, d = duration_seconds { n = 5 } }
            }
        """.trimIndent()
        assertEquals(6000L, eval(code))
    }

    @Test
    fun `add_hours convenience helper`() {
        val code = """
            use std::datetime::{ instant_from_millis, add_hours, instant_to_millis }
            instant_to_millis { i = add_hours { i = instant_from_millis { n = 0 }, n = 3 } }
        """.trimIndent()
        assertEquals(3L * 3600L * 1000L, eval(code))
    }

    @Test
    fun `diff_seconds between two instants`() {
        val code = """
            use std::datetime::{ instant_from_millis, diff_seconds }
            diff_seconds {
              s = instant_from_millis { n = 1000 },
              e = instant_from_millis { n = 61_000 }
            }
        """.trimIndent()
        assertEquals(60L, eval(code))
    }

    @Test
    fun `before and after`() {
        val code = """
            use std::datetime::{ instant_from_millis, before, after }
            let a = instant_from_millis { n = 100 }
            let b = instant_from_millis { n =200 }
            {
              a_before_b = before { a = a, b = b },
              a_after_b  = after  { a = a, b = b },
            }
        """.trimIndent()
        val r = eval(code) as MezzeRecord
        assertEquals(true, r.getField("a_before_b"))
        assertEquals(false, r.getField("a_after_b"))
    }

    // ==================== Zone ====================

    @Test
    fun `utc zone id string`() {
        val code = """
            use std::datetime::{ utc, zone_id }
            zone_id { z = utc {} }
        """.trimIndent()
        assertEquals("UTC", eval(code))
    }

    @Test
    fun `zone_of valid IANA name`() {
        val code = """
            use std::datetime::{ zone_of, zone_id }
            use std::result

            match zone_of { id = "America/New_York" } is
              Ok { val } -> zone_id { z = val }
              Err { error } -> error
        """.trimIndent()
        assertEquals("America/New_York", eval(code))
    }

    @Test
    fun `zone_of invalid name returns Err`() {
        val code = """
            use std::datetime::{ zone_of }
            match zone_of { id = "Not/A/Real/Zone" } is
              Ok { val } -> "ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== Decompose / compose ====================

    @Test
    fun `decompose Unix epoch in UTC gives 1970-01-01`() {
        val code = """
            use std::datetime::{ epoch, utc, decompose }
            decompose { i = epoch {}, z = utc {} }
        """.trimIndent()
        val r = eval(code) as MezzeRecord
        assertEquals(1970L, r.getField("year"))
        assertEquals(1L, r.getField("month"))
        assertEquals(1L, r.getField("day"))
        assertEquals(0L, r.getField("hour"))
        assertEquals(0L, r.getField("minute"))
        assertEquals(0L, r.getField("second"))
    }

    @Test
    fun `decompose then compose roundtrips`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc, decompose, compose, instant_to_millis }
            use std::result

            let orig = instant_from_millis { n = 1_700_000_000_000 }
            let dt = decompose { i = orig, z = utc {} }
            match compose { dt = dt, z = utc {} } is
              Ok { val = round } -> instant_to_millis { i = round }
              Err { error } -> 0 - 1
        """.trimIndent()
        // Should roundtrip to the original millis (compose truncates
        // nanos beyond what decompose returned — millis roundtrip is safe).
        assertEquals(1_700_000_000_000L, eval(code))
    }

    @Test
    fun `compose invalid month returns Err`() {
        val code = """
            use std::datetime::{ utc, compose }
            match compose {
              dt = { year = 2026, month = 13, day = 1, hour = 0, minute = 0, second = 0, nanos = 0 },
              z = utc {}
            } is
              Ok { val } -> "ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== Field accessors ====================

    @Test
    fun `year of epoch`() {
        val code = """
            use std::datetime::{ epoch, utc, year }
            year { i = epoch {}, z = utc {} }
        """.trimIndent()
        assertEquals(1970L, eval(code))
    }

    // ==================== Format / parse ====================

    @Test
    fun `format_iso8601 of epoch`() {
        val code = """
            use std::datetime::{ epoch, format_iso8601 }
            format_iso8601 { i = epoch {} }
        """.trimIndent()
        assertEquals("1970-01-01T00:00:00Z", eval(code))
    }

    @Test
    fun `format_date of specific instant`() {
        val code = """
            use std::datetime::{ instant_from_millis, utc, format_date }
            # 2023-11-14T22:13:20Z, in millis
            format_date { i = instant_from_millis { n = 1_700_000_000_000 }, z = utc {} }
        """.trimIndent()
        assertEquals("2023-11-14", eval(code))
    }

    @Test
    fun `parse then format roundtrip`() {
        val code = """
            use std::datetime::{ utc, parse, format_date }
            use std::result

            match parse { s = "2026-07-22", p = "yyyy-MM-dd", z = utc {} } is
              Ok { val = i } -> format_date { i = i, z = utc {} }
              Err { error } -> error
        """.trimIndent()
        assertEquals("2026-07-22", eval(code))
    }

    @Test
    fun `parse invalid format returns Err`() {
        val code = """
            use std::datetime::{ utc, parse }
            match parse { s = "nonsense", p = "yyyy-MM-dd", z = utc {} } is
              Ok { val } -> "ok"
              Err { error } -> "err"
        """.trimIndent()
        assertEquals("err", eval(code))
    }

    // ==================== Calendar utilities ====================

    @Test
    fun `is_leap_year 2024 is true`() {
        val code = """
            use std::datetime::{ is_leap_year }
            is_leap_year { year = 2024 }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `is_leap_year 2100 is false (century rule)`() {
        val code = """
            use std::datetime::{ is_leap_year }
            is_leap_year { year = 2100 }
        """.trimIndent()
        assertEquals(false, eval(code))
    }

    @Test
    fun `is_leap_year 2000 is true (400-year exception)`() {
        val code = """
            use std::datetime::{ is_leap_year }
            is_leap_year { year = 2000 }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `days_in_month February 2024 leap`() {
        val code = """
            use std::datetime::{ days_in_month }
            days_in_month { year = 2024, month = 2 }
        """.trimIndent()
        assertEquals(29L, eval(code))
    }

    @Test
    fun `days_in_month February 2023 non-leap`() {
        val code = """
            use std::datetime::{ days_in_month }
            days_in_month { year = 2023, month = 2 }
        """.trimIndent()
        assertEquals(28L, eval(code))
    }

    // ==================== Ability impls ====================

    @Test
    fun `instant Eq via operator`() {
        val code = """
            use std::datetime::{ instant_from_millis }
            let a = instant_from_millis { n = 1000 }
            let b = instant_from_millis { n = 1000 }
            let c = instant_from_millis { n =2000 }
            {
              same = a == b,
              diff = a == c,
            }
        """.trimIndent()
        val r = eval(code) as MezzeRecord
        assertEquals(true, r.getField("same"))
        assertEquals(false, r.getField("diff"))
    }

    @Test
    fun `duration Ord via operator`() {
        val code = """
            use std::datetime::{ duration_seconds }
            let a = duration_seconds { n = 5 }
            let b = duration_seconds { n = 10 }
            {
              a_lt_b = a < b,
              a_gt_b = a > b,
            }
        """.trimIndent()
        val r = eval(code) as MezzeRecord
        assertEquals(true, r.getField("a_lt_b"))
        assertEquals(false, r.getField("a_gt_b"))
    }

    // ==================== `+` / `-` operators ====================
    // NumAdd/NumSub class-param impls on the time algebra.

    @Test
    fun `instant plus duration via operator`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_seconds, instant_to_millis }
            let i = instant_from_millis { n = 1000 }
            let d = duration_seconds { n = 5 }
            instant_to_millis { i = i + d }
        """.trimIndent()
        assertEquals(6000L, eval(code))
    }

    @Test
    fun `instant minus duration via operator`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_seconds, instant_to_millis }
            let i = instant_from_millis { n = 10_000 }
            let d = duration_seconds { n = 3 }
            instant_to_millis { i = i - d }
        """.trimIndent()
        assertEquals(7000L, eval(code))
    }

    @Test
    fun `instant minus instant is a duration`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_to_millis }
            let e = instant_from_millis { n = 5000 }
            let s = instant_from_millis { n = 2000 }
            duration_to_millis { d = e - s }
        """.trimIndent()
        assertEquals(3000L, eval(code))
    }

    @Test
    fun `instant minus instant can be negative`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_to_millis }
            let s = instant_from_millis { n = 5000 }
            let e = instant_from_millis { n = 2000 }
            duration_to_millis { d = e - s }
        """.trimIndent()
        assertEquals(-3000L, eval(code))
    }

    @Test
    fun `duration plus duration via operator`() {
        val code = """
            use std::datetime::{ duration_seconds, duration_to_millis }
            let a = duration_seconds { n = 4 }
            let b = duration_seconds { n = 6 }
            duration_to_millis { d = a + b }
        """.trimIndent()
        assertEquals(10_000L, eval(code))
    }

    @Test
    fun `duration minus duration via operator`() {
        val code = """
            use std::datetime::{ duration_seconds, duration_to_millis }
            let a = duration_seconds { n = 4 }
            let b = duration_seconds { n = 10 }
            duration_to_millis { d = a - b }
        """.trimIndent()
        assertEquals(-6000L, eval(code))
    }

    @Test
    fun `shift instant forward then back is identity`() {
        val code = """
            use std::datetime::{ instant_from_millis, duration_seconds, instant_to_millis }
            let i = instant_from_millis { n = 1_700_000_000_000 }
            let d = duration_seconds { n = 90 }
            instant_to_millis { i = (i + d) - d }
        """.trimIndent()
        assertEquals(1_700_000_000_000L, eval(code))
    }
}
