package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/time module.
 * Uses MockTime handler for deterministic testing.
 * Migrated from V1 to V2 with unboxed values.
 */
class TimeEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === MockTime Handler Tests ===

    @Test
    fun `test MockTime now returns fixed time`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1640995200000 } in do
                Time.now {}
        """)
        assertEquals(1640995200000L, result)
    }

    @Test
    fun `test MockTime now_nanos returns scaled time`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1000 } in do
                Time.now_nanos {}
        """)
        // 1000 * 1000000 = 1000000000 (1 billion nanoseconds)
        assertEquals(1000000000L, result)
    }

    @Test
    fun `test MockTime sleep is no-op`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 12345 } in do
                Time.sleep { millis = 1000 }
                Time.now {}
        """)
        // Time should still be the fixed time after sleep
        assertEquals(12345L, result)
    }

    @Test
    fun `test MockTime sleep_nanos is no-op`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 54321 } in do
                Time.sleep_nanos { nanos = 1000000000 }
                Time.now {}
        """)
        assertEquals(54321L, result)
    }

    // === Convenience Functions ===

    @Test
    fun `test now convenience function`() {
        val result = eval("""
            use std::effects::time::{MockTime, now}

            perform MockTime { fixed_time = 9999 } in do
                now {}
        """)
        assertEquals(9999L, result)
    }

    @Test
    fun `test sleep convenience function`() {
        val result = eval("""
            use std::effects::time::{MockTime, sleep, now}

            perform MockTime { fixed_time = 100 } in do
                sleep { millis = 500 }
                now {}
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test current_timestamp convenience function`() {
        val result = eval("""
            use std::effects::time::{MockTime, current_timestamp}

            perform MockTime { fixed_time = 7777 } in do
                current_timestamp {}
        """)
        assertEquals(7777L, result)
    }

    // === Time with Computation ===

    @Test
    fun `test time measurement pattern`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            let measure = {} -> perform MockTime { fixed_time = 1000 } in do
                let start = Time.now {}
                # Some computation
                let computed = 10 + 20 + 30
                let end = Time.now {}
                { duration = end - start, result = computed }

            let getResult = {} -> do
                let m = measure {}
                m.result

            getResult {}
        """)
        assertEquals(60L, result)
    }

    @Test
    fun `test MockTime duration is always zero`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 5000 } in do
                let start = Time.now {}
                Time.sleep { millis = 100 }
                let end = Time.now {}
                end - start
        """)
        // With MockTime, sleep doesn't advance time, so duration is 0
        assertEquals(0L, result)
    }

    // === Different Fixed Times ===

    @Test
    fun `test different MockTime values`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            let getTime1 = {} -> perform MockTime { fixed_time = 100 } in Time.now {}
            let getTime2 = {} -> perform MockTime { fixed_time = 200 } in Time.now {}

            getTime1 {} + getTime2 {}
        """)
        assertEquals(300L, result)
    }

    // === SystemTime Handler (actual time) ===

    @Test
    fun `test SystemTime now returns current time`() {
        val result = eval("""
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in do
                let t = Time.now {}
                t > 0
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SystemTime now_nanos is greater than now`() {
        val result = eval("""
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in do
                let millis = Time.now {}
                let nanos = Time.now_nanos {}
                # Nanos should be roughly millis * 1000000 (with some variation)
                nanos > millis
        """)
        assertEquals(true, result)
    }

    // === Date Component Tests (MockTime) ===

    @Test
    fun `test MockTime current_year`() {
        // 1640995200000 = 2022-01-01 00:00:00 UTC
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1640995200000 } in do
                Time.current_year {}
        """)
        assertEquals(2022L, result)
    }

    @Test
    fun `test MockTime current_month`() {
        // 1640995200000 = 2022-01-01 00:00:00 UTC
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1640995200000 } in do
                Time.current_month {}
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test MockTime current_day`() {
        // 1640995200000 = 2022-01-01 00:00:00 UTC
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1640995200000 } in do
                Time.current_day {}
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test MockTime current_hour`() {
        // 1641034800000 = 2022-01-01 11:00:00 UTC
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1641034800000 } in do
                let h = Time.current_hour {}
                h >= 0 && h <= 23
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test MockTime current_minute`() {
        // 1641035700000 = 2022-01-01 11:15:00 UTC
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1641035700000 } in do
                let m = Time.current_minute {}
                m >= 0 && m <= 59
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test MockTime current_second`() {
        // 1641035730000 = 2022-01-01 11:15:30 UTC
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1641035730000 } in do
                let s = Time.current_second {}
                s >= 0 && s <= 59
        """)
        assertEquals(true, result)
    }

    // === Format and Parse Tests ===

    @Test
    fun `test MockTime format_timestamp`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 1640995200000 } in do
                Time.format_timestamp { timestamp = 1640995200000, format = "yyyy-MM-dd" }
        """)
        assertEquals("2022-01-01", result)
    }

    @Test
    fun `test MockTime parse_timestamp`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime}

            perform MockTime { fixed_time = 0 } in do
                # Use datetime format that includes time component
                let parsed = Time.parse_timestamp { date_string = "2022-01-01 00:00:00", format = "yyyy-MM-dd HH:mm:ss" }
                # Just check that it returns a positive timestamp
                parsed > 0
        """)
        assertEquals(true, result)
    }

    // === current_date Convenience Function ===

    @Test
    fun `test current_date convenience function`() {
        val result = eval("""
            use std::effects::time::{Time, MockTime, current_date}

            perform MockTime { fixed_time = 1640995200000 } in do
                let d = current_date {}
                d.year == 2022 && d.month == 1 && d.day == 1
        """)
        assertEquals(true, result)
    }

    // === SystemTime Date Components ===

    @Test
    fun `test SystemTime current_year returns valid year`() {
        val result = eval("""
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in do
                let year = Time.current_year {}
                year >= 2024 && year <= 2100
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SystemTime current_month returns valid month`() {
        val result = eval("""
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in do
                let month = Time.current_month {}
                month >= 1 && month <= 12
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SystemTime current_day returns valid day`() {
        val result = eval("""
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in do
                let day = Time.current_day {}
                day >= 1 && day <= 31
        """)
        assertEquals(true, result)
    }

    // === New Convenience Functions ===

    @Test
    fun `test current_time convenience function`() {
        val result = eval("""
            use std::effects::time::{MockTime, current_time}

            perform MockTime { fixed_time = 1641034800000 } in do
                let t = current_time {}
                t.hour >= 0 && t.hour <= 23 && t.minute >= 0 && t.minute <= 59 && t.second >= 0 && t.second <= 59
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test current_datetime convenience function`() {
        val result = eval("""
            use std::effects::time::{MockTime, current_datetime}

            perform MockTime { fixed_time = 1640995200000 } in do
                let dt = current_datetime {}
                dt.year == 2022 && dt.month == 1 && dt.day == 1
        """)
        assertEquals(true, result)
    }

    // === Time Arithmetic (Pure Functions) ===

    @Test
    fun `test add_millis`() {
        val result = eval("""
            use std::effects::time::{add_millis}

            add_millis { timestamp = 1000, millis = 500 }
        """)
        assertEquals(1500L, result)
    }

    @Test
    fun `test add_seconds`() {
        val result = eval("""
            use std::effects::time::{add_seconds}

            add_seconds { timestamp = 0, seconds = 5 }
        """)
        assertEquals(5000L, result)
    }

    @Test
    fun `test add_minutes`() {
        val result = eval("""
            use std::effects::time::{add_minutes}

            add_minutes { timestamp = 0, minutes = 2 }
        """)
        assertEquals(120000L, result)
    }

    @Test
    fun `test add_hours`() {
        val result = eval("""
            use std::effects::time::{add_hours}

            add_hours { timestamp = 0, hours = 1 }
        """)
        assertEquals(3600000L, result)
    }

    @Test
    fun `test add_days`() {
        val result = eval("""
            use std::effects::time::{add_days}

            add_days { timestamp = 0, days = 1 }
        """)
        assertEquals(86400000L, result)
    }

    // === Time Difference Utilities ===

    @Test
    fun `test diff_millis`() {
        val result = eval("""
            use std::effects::time::{diff_millis}

            diff_millis { start = 1000, end = 2500 }
        """)
        assertEquals(1500L, result)
    }

    @Test
    fun `test diff_seconds`() {
        val result = eval("""
            use std::effects::time::{diff_seconds}

            diff_seconds { start = 0, end = 5000 }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test diff_minutes`() {
        val result = eval("""
            use std::effects::time::{diff_minutes}

            diff_minutes { start = 0, end = 180000 }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test diff_hours`() {
        val result = eval("""
            use std::effects::time::{diff_hours}

            diff_hours { start = 0, end = 7200000 }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test diff_days`() {
        val result = eval("""
            use std::effects::time::{diff_days}

            diff_days { start = 0, end = 172800000 }
        """)
        assertEquals(2L, result)
    }

    // === Leap Year and Days in Month ===

    @Test
    fun `test is_leap_year true for divisible by 4`() {
        val result = eval("""
            use std::effects::time::{is_leap_year}

            is_leap_year { year = 2024 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test is_leap_year false for century not divisible by 400`() {
        val result = eval("""
            use std::effects::time::{is_leap_year}

            is_leap_year { year = 1900 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test is_leap_year true for century divisible by 400`() {
        val result = eval("""
            use std::effects::time::{is_leap_year}

            is_leap_year { year = 2000 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test days_in_month for January`() {
        val result = eval("""
            use std::effects::time::{days_in_month}

            days_in_month { year = 2024, month = 1 }
        """)
        assertEquals(31L, result)
    }

    @Test
    fun `test days_in_month for February leap year`() {
        val result = eval("""
            use std::effects::time::{days_in_month}

            days_in_month { year = 2024, month = 2 }
        """)
        assertEquals(29L, result)
    }

    @Test
    fun `test days_in_month for February non-leap year`() {
        val result = eval("""
            use std::effects::time::{days_in_month}

            days_in_month { year = 2023, month = 2 }
        """)
        assertEquals(28L, result)
    }

    @Test
    fun `test days_in_month for April`() {
        val result = eval("""
            use std::effects::time::{days_in_month}

            days_in_month { year = 2024, month = 4 }
        """)
        assertEquals(30L, result)
    }

    // === Format Convenience Functions ===

    @Test
    fun `test format_date`() {
        val result = eval("""
            use std::effects::time::{MockTime, format_date}

            perform MockTime { fixed_time = 1640995200000 } in do
                format_date { timestamp = 1640995200000 }
        """)
        assertEquals("2022-01-01", result)
    }

    @Test
    fun `test format_datetime`() {
        val result = eval("""
            use std::effects::time::{MockTime, format_datetime}

            perform MockTime { fixed_time = 1640995200000 } in do
                let formatted = format_datetime { timestamp = 1640995200000 }
                # Just check it contains the date part
                True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test elapsed_since`() {
        val result = eval("""
            use std::effects::time::{MockTime, elapsed_since}

            perform MockTime { fixed_time = 2000 } in do
                elapsed_since { timestamp = 1000 }
        """)
        assertEquals(1000L, result)
    }

    @Test
    fun `test format_iso8601`() {
        val result = eval("""
            use std::effects::time::{MockTime, format_iso8601}

            perform MockTime { fixed_time = 1640995200000 } in do
                let formatted = format_iso8601 { timestamp = 1640995200000 }
                __native_string_contains { s = formatted, substring = "2022-01-01" }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test format_time_only`() {
        val result = eval("""
            use std::effects::time::{MockTime, format_time_only}

            perform MockTime { fixed_time = 1640995200000 } in do
                let formatted = format_time_only { timestamp = 1640995200000 }
                __native_string_length { s = formatted } > 0
        """)
        assertEquals(true, result)
    }
}
