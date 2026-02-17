package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

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
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerTimeRetrievalOperations(context)
        registerSleepOperations(context)
        registerDateTimeComponentOperations(context)
        registerTimeFormattingOperations(context)
        registerTimestampHelperOperations(context)
    }

    private fun registerTimeRetrievalOperations(context: MutableMap<String, MezzeType>) {
        context["__native_time_now"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_time_now_nanos"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )
    }

    private fun registerSleepOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())

        context["__native_time_sleep"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("millis" to MezzeType.IntType)),
            unitType
        )

        context["__native_time_sleep_nanos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("nanos" to MezzeType.IntType)),
            unitType
        )
    }

    private fun registerDateTimeComponentOperations(context: MutableMap<String, MezzeType>) {
        context["__native_time_current_year"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_time_current_month"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_time_current_day"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_time_current_hour"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_time_current_minute"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_time_current_second"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )
    }

    private fun registerTimeFormattingOperations(context: MutableMap<String, MezzeType>) {
        context["__native_time_format"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "timestamp" to MezzeType.IntType,
                "format" to MezzeType.StringType
            )),
            MezzeType.StringType
        )

        context["__native_time_parse"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "date_string" to MezzeType.StringType,
                "format" to MezzeType.StringType
            )),
            MezzeType.IntType
        )
    }

    private fun registerTimestampHelperOperations(context: MutableMap<String, MezzeType>) {
        // Mock time helper functions for extracting components from timestamps
        context["__native_time_year_from_timestamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("timestamp" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_time_month_from_timestamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("timestamp" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_time_day_from_timestamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("timestamp" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_time_hour_from_timestamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("timestamp" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_time_minute_from_timestamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("timestamp" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_time_second_from_timestamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("timestamp" to MezzeType.IntType)),
            MezzeType.IntType
        )
    }
}
