package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

/**
 * Type signatures for time effect operations
 *
 * Corresponds to stdlib file: std/effects/time.mz
 *
 * Provides types for:
 * - Current time retrieval (milliseconds and nanoseconds)
 * - Sleep operations (milliseconds and nanoseconds)
 * - Date/time component extraction (year, month, day, hour, minute, second)
 * - Time formatting and parsing
 * - Helper functions for timestamp manipulation
 */
object TimeTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Time retrieval
        fn("__native_time_now") { returns(int) }
        fn("__native_time_now_nanos") { returns(int) }

        // Sleep operations
        fn("__native_time_sleep") { param("millis" to int); returns(unit) }
        fn("__native_time_sleep_nanos") { param("nanos" to int); returns(unit) }

        // Date/time components
        fn("__native_time_current_year") { returns(int) }
        fn("__native_time_current_month") { returns(int) }
        fn("__native_time_current_day") { returns(int) }
        fn("__native_time_current_hour") { returns(int) }
        fn("__native_time_current_minute") { returns(int) }
        fn("__native_time_current_second") { returns(int) }

        // Formatting and parsing
        fn("__native_time_format") { param("timestamp" to int, "format" to str); returns(str) }
        fn("__native_time_parse") { param("date_string" to str, "format" to str); returns(int) }

        // Timestamp helpers
        fn("__native_time_year_from_timestamp") { param("timestamp" to int); returns(int) }
        fn("__native_time_month_from_timestamp") { param("timestamp" to int); returns(int) }
        fn("__native_time_day_from_timestamp") { param("timestamp" to int); returns(int) }
        fn("__native_time_hour_from_timestamp") { param("timestamp" to int); returns(int) }
        fn("__native_time_minute_from_timestamp") { param("timestamp" to int); returns(int) }
        fn("__native_time_second_from_timestamp") { param("timestamp" to int); returns(int) }
    }
}
