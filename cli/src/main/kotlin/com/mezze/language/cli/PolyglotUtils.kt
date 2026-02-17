package com.mezze.language.cli

import org.graalvm.polyglot.Value

/**
 * Format a polyglot value for display
 */
fun formatPolyglotValue(value: Value): String {
    return when {
        value.isNull -> "null"
        value.isBoolean -> value.asBoolean().toString()
        value.isNumber -> {
            if (value.fitsInLong()) {
                value.asLong().toString()
            } else {
                value.asDouble().toString()
            }
        }
        value.isString -> "\"${value.asString()}\""
        value.hasMembers() -> {
            // Temporarily disable member formatting to isolate the issue
            value.toString()
        }
        else -> value.toString()
    }
}
