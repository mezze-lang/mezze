package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType

/**
 * Type signatures for time effect operations
 *
 * Corresponds to stdlib file: std/effects/time.funk
 *
 * Provides types for:
 * - Current time retrieval (milliseconds and nanoseconds)
 * - Sleep operations (milliseconds and nanoseconds)
 * - Date/time component extraction (year, month, day, hour, minute, second)
 * - Time formatting and parsing
 * - Helper functions for timestamp manipulation
 */
object TimeTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerTimeRetrievalOperations(context)
        registerSleepOperations(context)
        registerDateTimeComponentOperations(context)
        registerTimeFormattingOperations(context)
        registerTimestampHelperOperations(context)
    }

    private fun registerTimeRetrievalOperations(context: MutableMap<String, FunkType>) {
        context["__native_time_now"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_now_nanos"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )
    }

    private fun registerSleepOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        context["__native_time_sleep"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("millis" to FunkType.IntType)),
            unitType
        )

        context["__native_time_sleep_nanos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("nanos" to FunkType.IntType)),
            unitType
        )
    }

    private fun registerDateTimeComponentOperations(context: MutableMap<String, FunkType>) {
        context["__native_time_current_year"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_month"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_day"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_hour"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_minute"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_second"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )
    }

    private fun registerTimeFormattingOperations(context: MutableMap<String, FunkType>) {
        context["__native_time_format"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "timestamp" to FunkType.IntType,
                "format" to FunkType.StringType
            )),
            FunkType.StringType
        )

        context["__native_time_parse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "date_string" to FunkType.StringType,
                "format" to FunkType.StringType
            )),
            FunkType.IntType
        )
    }

    private fun registerTimestampHelperOperations(context: MutableMap<String, FunkType>) {
        // Mock time helper functions for extracting components from timestamps
        context["__native_time_year_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_month_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_day_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_hour_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_minute_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_second_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )
    }
}
