package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Truffle runtime implementations for Time effect operations
 */
object TimeImplementations {

    /**
     * Register all Time effect implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        context["__native_time_now"] = NativeFunkFunction("__native_time_now") { args ->
            FunkInt(System.currentTimeMillis())
        }

        context["__native_time_now_nanos"] = NativeFunkFunction("__native_time_now_nanos") { args ->
            FunkInt(System.nanoTime())
        }

        context["__native_time_sleep"] = NativeFunkFunction("__native_time_sleep") { args ->
            val record = args[0] as FunkRecord
            val millis = record.fields["millis"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_sleep", "expects 'millis' as Int")

            try {
                Thread.sleep(millis.value)
                FunkUnit
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FunkException("Sleep interrupted")
            }
        }

        context["__native_time_sleep_nanos"] = NativeFunkFunction("__native_time_sleep_nanos") { args ->
            val record = args[0] as FunkRecord
            val nanos = record.fields["nanos"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_sleep_nanos", "expects 'nanos' as Int")

            try {
                TimeUnit.NANOSECONDS.sleep(nanos.value)
                FunkUnit
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FunkException("Sleep interrupted")
            }
        }

        context["__native_time_current_year"] = NativeFunkFunction("__native_time_current_year") { args ->
            FunkInt(LocalDateTime.now().year.toLong())
        }

        context["__native_time_current_month"] = NativeFunkFunction("__native_time_current_month") { args ->
            FunkInt(LocalDateTime.now().monthValue.toLong())
        }

        context["__native_time_current_day"] = NativeFunkFunction("__native_time_current_day") { args ->
            FunkInt(LocalDateTime.now().dayOfMonth.toLong())
        }

        context["__native_time_current_hour"] = NativeFunkFunction("__native_time_current_hour") { args ->
            FunkInt(LocalDateTime.now().hour.toLong())
        }

        context["__native_time_current_minute"] = NativeFunkFunction("__native_time_current_minute") { args ->
            FunkInt(LocalDateTime.now().minute.toLong())
        }

        context["__native_time_current_second"] = NativeFunkFunction("__native_time_current_second") { args ->
            FunkInt(LocalDateTime.now().second.toLong())
        }

        context["__native_time_format"] = NativeFunkFunction("__native_time_format") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_format", "expects 'timestamp' as Int")
            val format = record.fields["format"] as? FunkString
                ?: throw NativeFunctionException("__native_time_format", "expects 'format' as String")

            try {
                val instant = Instant.ofEpochMilli(timestamp.value)
                val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                val formatter = DateTimeFormatter.ofPattern(format.value)
                FunkString(dateTime.format(formatter))
            } catch (e: Exception) {
                throw FunkException("Failed to format timestamp: ${e.message}")
            }
        }

        context["__native_time_parse"] = NativeFunkFunction("__native_time_parse") { args ->
            val record = args[0] as FunkRecord
            val dateString = record.fields["date_string"] as? FunkString
                ?: throw NativeFunctionException("__native_time_parse", "expects 'date_string' as String")
            val format = record.fields["format"] as? FunkString
                ?: throw NativeFunctionException("__native_time_parse", "expects 'format' as String")

            try {
                val formatter = DateTimeFormatter.ofPattern(format.value)
                val dateTime = LocalDateTime.parse(dateString.value, formatter)
                val instant = dateTime.atZone(ZoneId.systemDefault()).toInstant()
                FunkInt(instant.toEpochMilli())
            } catch (e: Exception) {
                throw FunkException("Failed to parse date string: ${e.message}")
            }
        }

        // Helper functions for mock time
        context["__native_time_year_from_timestamp"] = NativeFunkFunction("__native_time_year_from_timestamp") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_year_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            FunkInt(dateTime.year.toLong())
        }

        context["__native_time_month_from_timestamp"] = NativeFunkFunction("__native_time_month_from_timestamp") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_month_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            FunkInt(dateTime.monthValue.toLong())
        }

        context["__native_time_day_from_timestamp"] = NativeFunkFunction("__native_time_day_from_timestamp") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_day_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            FunkInt(dateTime.dayOfMonth.toLong())
        }

        context["__native_time_hour_from_timestamp"] = NativeFunkFunction("__native_time_hour_from_timestamp") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_hour_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            FunkInt(dateTime.hour.toLong())
        }

        context["__native_time_minute_from_timestamp"] = NativeFunkFunction("__native_time_minute_from_timestamp") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_minute_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            FunkInt(dateTime.minute.toLong())
        }

        context["__native_time_second_from_timestamp"] = NativeFunkFunction("__native_time_second_from_timestamp") { args ->
            val record = args[0] as FunkRecord
            val timestamp = record.fields["timestamp"] as? FunkInt
                ?: throw NativeFunctionException("__native_time_second_from_timestamp", "expects 'timestamp' as Int")

            val instant = Instant.ofEpochMilli(timestamp.value)
            val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            FunkInt(dateTime.second.toLong())
        }
    }
}
