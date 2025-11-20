package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Log effect operations
 */
object LogImplementations {

    /**
     * Register all Log effect implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Console logging operations
        context["__native_console_print"] = NativeFunkFunction("__native_console_print") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_console_print expects 'msg' as String")
            print(msg.value)
            FunkRecord(emptyMap())
        }

        context["__native_console_println"] = NativeFunkFunction("__native_console_println") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_console_println expects 'msg' as String")
            println(msg.value)
            FunkRecord(emptyMap())
        }

        // Log level operations
        context["__native_log_info"] = NativeFunkFunction("__native_log_info") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_info expects 'msg' as String")
            println("[INFO] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_warn"] = NativeFunkFunction("__native_log_warn") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_warn expects 'msg' as String")
            println("[WARN] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_error"] = NativeFunkFunction("__native_log_error") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_error expects 'msg' as String")
            System.err.println("[ERROR] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_debug"] = NativeFunkFunction("__native_log_debug") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_debug expects 'msg' as String")
            println("[DEBUG] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_trace"] = NativeFunkFunction("__native_log_trace") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_trace expects 'msg' as String")
            println("[TRACE] ${msg.value}")
            FunkRecord(emptyMap())
        }

        // Legacy print operation
        context["print"] = NativeFunkFunction("print") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("print expects 'msg' as String")
            println(msg.value)
            FunkRecord(emptyMap()) // Unit type
        }

        // Keep backward compatibility
        context["native_print"] = context["print"]!!

        // Legacy debug operation
        context["debug"] = NativeFunkFunction("debug") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("debug expects 'msg' as String")
            System.err.println("[DEBUG] ${msg.value}")
            FunkRecord(emptyMap()) // Unit type
        }

        // Keep backward compatibility
        context["native_debug"] = context["debug"]!!
    }
}
