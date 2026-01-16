package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for Frac type operations.
 *
 * Phase 5 of Truffle migration: Basic type operations optimized for JIT performance.
 * Frac is the fraction type representing exact rational numbers (numerator/denominator).
 *
 * Optimizations:
 * - Inline fraction arithmetic with automatic reduction to lowest terms
 * - Cross-multiplication for efficient comparison (avoids division)
 * - Direct GCD calculation for fraction reduction
 * - Zero-overhead rational number operations
 * - Proper handling of negative fractions
 * - Safe conversions to Int and Float types
 *
 * Arithmetic operations:
 * - a/b + c/d = (a*d + c*b) / (b*d)
 * - a/b - c/d = (a*d - c*b) / (b*d)
 * - a/b * c/d = (a*c) / (b*d)
 * - a/b ÷ c/d = (a*d) / (b*c)
 *
 * Total: 26 fraction operations (arithmetic, comparison, conversion, utilities)
 */
object FracImplementations {

    /**
     * Register all Frac runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Arithmetic operations
        context["add_frac"] = NativeFunkFunction("add_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("add_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("add_frac", "expects 'right' as Frac")

            // a/b + c/d = (a*d + c*b) / (b*d)
            val numerator = l.numerator * r.denominator + r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }

        context["subtract_frac"] = NativeFunkFunction("subtract_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("subtract_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("subtract_frac", "expects 'right' as Frac")

            // a/b - c/d = (a*d - c*b) / (b*d)
            val numerator = l.numerator * r.denominator - r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }

        context["multiply_frac"] = NativeFunkFunction("multiply_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("multiply_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("multiply_frac", "expects 'right' as Frac")

            // a/b * c/d = (a*c) / (b*d)
            val numerator = l.numerator * r.numerator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }

        context["divide_frac"] = NativeFunkFunction("divide_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("divide_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("divide_frac", "expects 'right' as Frac")

            if (r.numerator == 0L) throw FunkException("Division by zero")

            // a/b ÷ c/d = a/b * d/c = (a*d) / (b*c)
            val numerator = l.numerator * r.denominator
            val denominator = l.denominator * r.numerator
            FunkFrac(numerator, denominator).reduce()
        }

        // Comparison operations
        context["equal_frac"] = NativeFunkFunction("equal_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("equal_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("equal_frac", "expects 'right' as Frac")

            // Cross multiply: a/b == c/d iff a*d == b*c
            FunkBool(l.numerator * r.denominator == r.numerator * l.denominator)
        }

        context["lessThan_frac"] = NativeFunkFunction("lessThan_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("lessThan_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("lessThan_frac", "expects 'right' as Frac")

            // Cross multiply: a/b < c/d iff a*d < c*b (assuming positive denominators)
            FunkBool(l.numerator * r.denominator < r.numerator * l.denominator)
        }

        context["greaterThan_frac"] = NativeFunkFunction("greaterThan_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("greaterThan_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("greaterThan_frac", "expects 'right' as Frac")

            // Cross multiply: a/b > c/d iff a*d > c*b (assuming positive denominators)
            FunkBool(l.numerator * r.denominator > r.numerator * l.denominator)
        }

        // Conversion operations
        context["fracToInt"] = NativeFunkFunction("fracToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFrac ?: throw NativeFunctionException("fracToInt", "expects 'val' as Frac")
            FunkInt(value.numerator / value.denominator)
        }

        context["fracToFloat"] = NativeFunkFunction("fracToFloat") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("fracToFloat", "expects 'frac' as Frac")
            FunkFloat(n.numerator.toDouble() / n.denominator.toDouble())
        }

        context["fracToString"] = NativeFunkFunction("fracToString") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("fracToString", "expects 'frac' as Frac")
            FunkString("${n.numerator}/${n.denominator}")
        }

        // Hidden native operations - aliases for stdlib
        context["__native_frac_add"] = NativeFunkFunction("__native_frac_add") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_add", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_add", "expects 'right' as Frac")

            // a/b + c/d = (a*d + c*b) / (b*d)
            val numerator = l.numerator * r.denominator + r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }

        context["__native_frac_subtract"] = NativeFunkFunction("__native_frac_subtract") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_subtract", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_subtract", "expects 'right' as Frac")

            // a/b - c/d = (a*d - c*b) / (b*d)
            val numerator = l.numerator * r.denominator - r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }

        context["__native_frac_multiply"] = NativeFunkFunction("__native_frac_multiply") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_multiply", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_multiply", "expects 'right' as Frac")

            // a/b * c/d = (a*c) / (b*d)
            val numerator = l.numerator * r.numerator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }

        context["__native_frac_divide"] = NativeFunkFunction("__native_frac_divide") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_divide", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_divide", "expects 'right' as Frac")

            if (r.numerator == 0L) throw FunkException("Division by zero")

            // a/b ÷ c/d = a/b * d/c = (a*d) / (b*c)
            val numerator = l.numerator * r.denominator
            val denominator = l.denominator * r.numerator
            FunkFrac(numerator, denominator).reduce()
        }

        context["__native_frac_equals"] = NativeFunkFunction("__native_frac_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_equals", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_equals", "expects 'right' as Frac")

            // Cross multiply: a/b == c/d iff a*d == b*c
            FunkBool(l.numerator * r.denominator == r.numerator * l.denominator)
        }

        context["__native_frac_less_than"] = NativeFunkFunction("__native_frac_less_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_less_than", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_less_than", "expects 'right' as Frac")

            // Cross multiply: a/b < c/d iff a*d < c*b (assuming positive denominators)
            FunkBool(l.numerator * r.denominator < r.numerator * l.denominator)
        }

        context["__native_frac_greater_than"] = NativeFunkFunction("__native_frac_greater_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_greater_than", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_greater_than", "expects 'right' as Frac")

            // Cross multiply: a/b > c/d iff a*d > c*b (assuming positive denominators)
            FunkBool(l.numerator * r.denominator > r.numerator * l.denominator)
        }

        context["__native_frac_to_float"] = NativeFunkFunction("__native_frac_to_float") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_to_float", "expects 'frac' as Frac")
            FunkFloat(n.numerator.toDouble() / n.denominator.toDouble())
        }

        context["__native_frac_to_string"] = NativeFunkFunction("__native_frac_to_string") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_to_string", "expects 'frac' as Frac")
            FunkString("${n.numerator}/${n.denominator}")
        }

        // Frac utility operations
        context["__native_make_frac"] = NativeFunkFunction("__native_make_frac") { args ->
            val record = args[0] as FunkRecord
            val numerator = record.fields["numerator"] as? FunkInt ?: throw NativeFunctionException("__native_make_frac", "expects 'numerator' as Int")
            val denominator = record.fields["denominator"] as? FunkInt ?: throw NativeFunctionException("__native_make_frac", "expects 'denominator' as Int")
            if (denominator.value == 0L) throw FunkException("Fraction denominator cannot be zero")
            FunkFrac(numerator.value, denominator.value).reduce()
        }

        context["__native_frac_numerator"] = NativeFunkFunction("__native_frac_numerator") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_numerator", "expects 'frac' as Frac")
            FunkInt(n.numerator)
        }

        context["__native_frac_denominator"] = NativeFunkFunction("__native_frac_denominator") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_denominator", "expects 'frac' as Frac")
            FunkInt(n.denominator)
        }

        context["__native_reduce_frac"] = NativeFunkFunction("__native_reduce_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_reduce_frac", "expects 'frac' as Frac")
            n.reduce()
        }

        context["__native_abs_frac"] = NativeFunkFunction("__native_abs_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_abs_frac", "expects 'frac' as Frac")
            FunkFrac(kotlin.math.abs(n.numerator), n.denominator).reduce()
        }

        context["__native_negate_frac"] = NativeFunkFunction("__native_negate_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_negate_frac", "expects 'frac' as Frac")
            FunkFrac(-n.numerator, n.denominator).reduce()
        }

        context["__native_frac_neg"] = NativeFunkFunction("__native_frac_neg") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_neg", "expects 'n' as Frac")
            FunkFrac(-value.numerator, value.denominator).reduce()
        }
    }
}
