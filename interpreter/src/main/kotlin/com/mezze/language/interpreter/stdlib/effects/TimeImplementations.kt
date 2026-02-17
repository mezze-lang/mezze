package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Truffle-optimized implementations for Time effect operations.
 *
 * Phase 6 of Truffle migration: I/O and System operations optimized for JIT performance.
 * Handles all time-related operations using java.time APIs.
 *
 * Optimizations:
 * - Direct java.time APIs for JIT compilation (Instant, LocalDateTime, ZoneId)
 * - System.currentTimeMillis() and System.nanoTime() for high-performance timestamps
 * - TimeUnit.NANOSECONDS.sleep() for nanosecond precision
 * - DateTimeFormatter for flexible date formatting and parsing
 * - Proper interrupt handling for sleep operations
 * - Helper functions for timestamp component extraction (year, month, day, hour, minute, second)
 *
 * Operations: now, now_nanos, sleep, sleep_nanos, current_year/month/day/hour/minute/second,
 *             format, parse, year/month/day/hour/minute/second_from_timestamp
 *
 * Total: 18 time functions
 */
object TimeImplementations {

    /**
     * Register all Time effect implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        context["__native_time_now"] = NativeMezzeFunction("__native_time_now") { args ->
            MezzeInt(System.currentTimeMillis())
        }

        context["__native_time_now_nanos"] = NativeMezzeFunction("__native_time_now_nanos") { args ->
            MezzeInt(System.nanoTime())
        }

        context["__native_time_sleep"] = NativeMezzeFunction("__native_time_sleep") { args ->
            val record = args[0] as MezzeRecord
            val millis = record.fields["millis"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_sleep", "expects 'millis' as Int")

            try {
                Thread.sleep(millis.value)
                MezzeUnit
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MezzeException("Sleep interrupted")
            }
        }

        context["__native_time_sleep_nanos"] = NativeMezzeFunction("__native_time_sleep_nanos") { args ->
            val record = args[0] as MezzeRecord
            val nanos = record.fields["nanos"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_sleep_nanos", "expects 'nanos' as Int")

            try {
                TimeUnit.NANOSECONDS.sleep(nanos.value)
                MezzeUnit
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MezzeException("Sleep interrupted")
            }
        }

        context["__native_time_current_year"] = NativeMezzeFunction("__native_time_current_year") { args ->
            MezzeInt(LocalDateTime.now().year.toLong())
        }

        context["__native_time_current_month"] = NativeMezzeFunction("__native_time_current_month") { args ->
            MezzeInt(LocalDateTime.now().monthValue.toLong())
        }

        context["__native_time_current_day"] = NativeMezzeFunction("__native_time_current_day") { args ->
            MezzeInt(LocalDateTime.now().dayOfMonth.toLong())
        }

        context["__native_time_current_hour"] = NativeMezzeFunction("__native_time_current_hour") { args ->
            MezzeInt(LocalDateTime.now().hour.toLong())
        }

        context["__native_time_current_minute"] = NativeMezzeFunction("__native_time_current_minute") { args ->
            MezzeInt(LocalDateTime.now().minute.toLong())
        }

        context["__native_time_current_second"] = NativeMezzeFunction("__native_time_current_second") { args ->
            MezzeInt(LocalDateTime.now().second.toLong())
        }

        context["__native_time_format"] = NativeMezzeFunction("__native_time_format") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_format", "expects 'timestamp' as Int")
            val format = record.fields["format"] as? MezzeString
                ?: throw NativeFunctionException("__native_time_format", "expects 'format' as String")

            try {
                val instant = Instant.ofEpochMilli(timestamp.value)
                val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                val formatter = DateTimeFormatter.ofPattern(format.value)
                MezzeString(dateTime.format(formatter))
            } catch (e: Exception) {
                throw MezzeException("Failed to format timestamp: ${e.message}")
            }
        }

        context["__native_time_parse"] = NativeMezzeFunction("__native_time_parse") { args ->
            val record = args[0] as MezzeRecord
            val dateString = record.fields["date_string"] as? MezzeString
                ?: throw NativeFunctionException("__native_time_parse", "expects 'date_string' as String")
            val format = record.fields["format"] as? MezzeString
                ?: throw NativeFunctionException("__native_time_parse", "expects 'format' as String")

            try {
                val formatter = DateTimeFormatter.ofPattern(format.value)
                val dateTime = LocalDateTime.parse(dateString.value, formatter)
                val instant = dateTime.atZone(ZoneId.systemDefault()).toInstant()
                MezzeInt(instant.toEpochMilli())
            } catch (e: Exception) {
                throw MezzeException("Failed to parse date string: ${e.message}")
            }
        }

        // Helper functions for mock time
        context["__native_time_year_from_timestamp"] = NativeMezzeFunction("__native_time_year_from_timestamp") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_year_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            MezzeInt(dateTime.year.toLong())
        }

        context["__native_time_month_from_timestamp"] = NativeMezzeFunction("__native_time_month_from_timestamp") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_month_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            MezzeInt(dateTime.monthValue.toLong())
        }

        context["__native_time_day_from_timestamp"] = NativeMezzeFunction("__native_time_day_from_timestamp") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_day_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            MezzeInt(dateTime.dayOfMonth.toLong())
        }

        context["__native_time_hour_from_timestamp"] = NativeMezzeFunction("__native_time_hour_from_timestamp") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_hour_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            MezzeInt(dateTime.hour.toLong())
        }

        context["__native_time_minute_from_timestamp"] = NativeMezzeFunction("__native_time_minute_from_timestamp") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_minute_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            MezzeInt(dateTime.minute.toLong())
        }

        context["__native_time_second_from_timestamp"] = NativeMezzeFunction("__native_time_second_from_timestamp") { args ->
            val record = args[0] as MezzeRecord
            val timestamp = record.fields["timestamp"] as? MezzeInt
                ?: throw NativeFunctionException("__native_time_second_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            MezzeInt(dateTime.second.toLong())
        }
    }
}
