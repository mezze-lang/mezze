package com.funk.language.interpreter.stdlib.system

import com.funk.language.interpreter.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.net.InetAddress
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Truffle runtime implementations for system effects (Time, Environment, Network)
 */
object TruffleSystemImplementations {
    
    /**
     * Register all system effect implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerTimeOperations(context)
        registerEnvironmentOperations(context)
        registerJsonOperations(context)
    }
    
    /**
     * Time effect native implementations
     */
    private fun registerTimeOperations(context: MutableMap<String, FunkValue>) {
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
    
    /**
     * Environment effect native implementations
     */
    private fun registerEnvironmentOperations(context: MutableMap<String, FunkValue>) {
        context["__native_env_get"] = NativeFunkFunction("__native_env_get") { args ->
            val record = args[0] as FunkRecord
            val name = record.fields["name"] as? FunkString 
                ?: throw NativeFunctionException("__native_env_get", "expects 'name' as String")
            
            val value = System.getenv(name.value)
            if (value != null) {
                FunkVariant("Some", FunkRecord(mapOf("value" to FunkString(value))))
            } else {
                FunkVariant("None", null)
            }
        }
        
        context["__native_env_set"] = NativeFunkFunction("__native_env_set") { args ->
            val record = args[0] as FunkRecord
            val name = record.fields["name"] as? FunkString 
                ?: throw NativeFunctionException("__native_env_set", "expects 'name' as String")
            val value = record.fields["value"] as? FunkString 
                ?: throw NativeFunctionException("__native_env_set", "expects 'value' as String")
            
            // Note: Java doesn't allow setting environment variables at runtime
            // This would need to be implemented differently in a real system
            System.setProperty(name.value, value.value)
            FunkUnit
        }
        
        context["__native_env_get_all"] = NativeFunkFunction("__native_env_get_all") { args ->
            val envVars = System.getenv().map { (key, value) ->
                FunkTuple(listOf(FunkString(key), FunkString(value)))
            }
            FunkArrayList(ArrayList(envVars))
        }
        
        context["__native_env_current_dir"] = NativeFunkFunction("__native_env_current_dir") { args ->
            FunkString(System.getProperty("user.dir"))
        }
        
        context["__native_env_change_dir"] = NativeFunkFunction("__native_env_change_dir") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString 
                ?: throw NativeFunctionException("__native_env_change_dir", "expects 'path' as String")
            
            // Note: Java doesn't allow changing working directory at runtime
            // This would need to be implemented differently in a real system
            System.setProperty("user.dir", path.value)
            FunkUnit
        }
        
        context["__native_env_os_name"] = NativeFunkFunction("__native_env_os_name") { args ->
            FunkString(System.getProperty("os.name"))
        }
        
        context["__native_env_os_version"] = NativeFunkFunction("__native_env_os_version") { args ->
            FunkString(System.getProperty("os.version"))
        }
        
        context["__native_env_arch"] = NativeFunkFunction("__native_env_arch") { args ->
            FunkString(System.getProperty("os.arch"))
        }
        
        context["__native_env_process_id"] = NativeFunkFunction("__native_env_process_id") { args ->
            FunkInt(ProcessHandle.current().pid())
        }
        
        context["__native_env_process_args"] = NativeFunkFunction("__native_env_process_args") { args ->
            val args = ProcessHandle.current().info().arguments().orElse(arrayOf())
            val funkArgs = args.map { FunkString(it) }
            FunkArrayList(ArrayList(funkArgs))
        }
        
        context["__native_env_exit"] = NativeFunkFunction("__native_env_exit") { args ->
            val record = args[0] as FunkRecord
            val code = record.fields["code"] as? FunkInt 
                ?: throw NativeFunctionException("__native_env_exit", "expects 'code' as Int")
            
            System.exit(code.value.toInt())
            FunkUnit // This line will never be reached
        }
        
        // Mock environment helpers
        context["__native_mock_env_get"] = NativeFunkFunction("__native_mock_env_get") { args ->
            val record = args[0] as FunkRecord
            val envVars = record.fields["env_vars"] as? FunkArrayList 
                ?: throw NativeFunctionException("__native_mock_env_get", "expects 'env_vars' as ArrayList")
            val name = record.fields["name"] as? FunkString 
                ?: throw NativeFunctionException("__native_mock_env_get", "expects 'name' as String")
            
            for (pair in envVars.elements) {
                val tuple = pair as FunkTuple
                val key = tuple.elements[0] as FunkString
                val value = tuple.elements[1] as FunkString
                if (key.value == name.value) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf("value" to value)))
                }
            }
            FunkVariant("None", null)
        }
    }
    

    
    /**
     * JSON operations (simplified implementations)
     */
    private fun registerJsonOperations(context: MutableMap<String, FunkValue>) {
        context["__native_json_parse"] = NativeFunkFunction("__native_json_parse") { args ->
            val record = args[0] as FunkRecord
            val jsonString = record.fields["json_string"] as? FunkString 
                ?: throw NativeFunctionException("__native_json_parse", "expects 'json_string' as String")
            
            try {
                // Simplified JSON parsing - would need a proper JSON parser in production
                val trimmed = jsonString.value.trim()
                when {
                    trimmed == "null" -> FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonNull", null))))
                    trimmed == "true" -> FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonBool", FunkRecord(mapOf("value" to FunkBool(true)))))))
                    trimmed == "false" -> FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonBool", FunkRecord(mapOf("value" to FunkBool(false)))))))
                    trimmed.startsWith("\"") && trimmed.endsWith("\"") -> {
                        val stringValue = trimmed.substring(1, trimmed.length - 1)
                        FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonString", FunkRecord(mapOf("value" to FunkString(stringValue)))))))
                    }
                    trimmed.matches(Regex("-?\\d+(\\.\\d+)?")) -> {
                        val numberValue = trimmed.toDouble()
                        FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonNumber", FunkRecord(mapOf("value" to FunkFloat(numberValue)))))))
                    }
                    else -> FunkVariant("Err", FunkRecord(mapOf("error" to FunkString("Unsupported JSON format"))))
                }
            } catch (e: Exception) {
                FunkVariant("Err", FunkRecord(mapOf("error" to FunkString("JSON parsing failed: ${e.message}"))))
            }
        }
        
        context["__native_json_stringify"] = NativeFunkFunction("__native_json_stringify") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["value"] as? FunkVariant 
                ?: throw NativeFunctionException("__native_json_stringify", "expects 'value' as JsonValue")
            
            when (value.constructor) {
                "JsonNull" -> FunkString("null")
                "JsonBool" -> {
                    val boolRecord = value.value as FunkRecord
                    val boolValue = boolRecord.fields["value"] as FunkBool
                    FunkString(boolValue.value.toString())
                }
                "JsonNumber" -> {
                    val numberRecord = value.value as FunkRecord
                    val numberValue = numberRecord.fields["value"] as FunkFloat
                    FunkString(numberValue.value.toString())
                }
                "JsonString" -> {
                    val stringRecord = value.value as FunkRecord
                    val stringValue = stringRecord.fields["value"] as FunkString
                    FunkString("\"${stringValue.value}\"")
                }
                else -> FunkString("\"unsupported\"")
            }
        }
        
        context["__native_json_pretty_print"] = NativeFunkFunction("__native_json_pretty_print") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["value"] as? FunkVariant 
                ?: throw NativeFunctionException("__native_json_pretty_print", "expects 'value' as JsonValue")
            val indent = record.fields["indent"] as? FunkInt 
                ?: throw NativeFunctionException("__native_json_pretty_print", "expects 'indent' as Int")
            
            // Simplified pretty printing - just add some spacing
            val jsonString = context["__native_json_stringify"]!!.let { stringify ->
                (stringify as NativeFunkFunction).call(listOf(FunkRecord(mapOf("value" to value)))) as FunkString
            }
            
            FunkString(jsonString.value) // Would need proper indentation logic
        }
        
        context["__native_json_get_field"] = NativeFunkFunction("__native_json_get_field") { args ->
            val record = args[0] as FunkRecord
            val fields = record.fields["fields"] as? FunkArrayList 
                ?: throw NativeFunctionException("__native_json_get_field", "expects 'fields' as ArrayList")
            val field = record.fields["field"] as? FunkString 
                ?: throw NativeFunctionException("__native_json_get_field", "expects 'field' as String")
            
            for (pair in fields.elements) {
                val tuple = pair as FunkTuple
                val key = tuple.elements[0] as FunkString
                val value = tuple.elements[1]
                if (key.value == field.value) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf("value" to value)))
                }
            }
            FunkVariant("None", null)
        }
        
        context["__native_json_get_index"] = NativeFunkFunction("__native_json_get_index") { args ->
            val record = args[0] as FunkRecord
            val elements = record.fields["elements"] as? FunkArrayList 
                ?: throw NativeFunctionException("__native_json_get_index", "expects 'elements' as ArrayList")
            val index = record.fields["index"] as? FunkInt 
                ?: throw NativeFunctionException("__native_json_get_index", "expects 'index' as Int")
            
            if (index.value >= 0 && index.value < elements.size()) {
                val value = elements.get(index.value.toInt())
                FunkVariant("Some", FunkRecord(mapOf("value" to value)))
            } else {
                FunkVariant("None", null)
            }
        }
    }
    

}