package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Arithmetic operations
        context["add_frac"] = NativeMezzeFunction("add_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("add_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("add_frac", "expects 'right' as Frac")

            // a/b + c/d = (a*d + c*b) / (b*d)
            val numerator = l.numerator * r.denominator + r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["subtract_frac"] = NativeMezzeFunction("subtract_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("subtract_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("subtract_frac", "expects 'right' as Frac")

            // a/b - c/d = (a*d - c*b) / (b*d)
            val numerator = l.numerator * r.denominator - r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["multiply_frac"] = NativeMezzeFunction("multiply_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("multiply_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("multiply_frac", "expects 'right' as Frac")

            // a/b * c/d = (a*c) / (b*d)
            val numerator = l.numerator * r.numerator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["divide_frac"] = NativeMezzeFunction("divide_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("divide_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("divide_frac", "expects 'right' as Frac")

            if (r.numerator == 0L) throw MezzeException("Division by zero")

            // a/b ÷ c/d = a/b * d/c = (a*d) / (b*c)
            val numerator = l.numerator * r.denominator
            val denominator = l.denominator * r.numerator
            MezzeFrac(numerator, denominator).reduce()
        }

        // Comparison operations
        context["equal_frac"] = NativeMezzeFunction("equal_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("equal_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("equal_frac", "expects 'right' as Frac")

            // Cross multiply: a/b == c/d iff a*d == b*c
            MezzeBool(l.numerator * r.denominator == r.numerator * l.denominator)
        }

        context["lessThan_frac"] = NativeMezzeFunction("lessThan_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("lessThan_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("lessThan_frac", "expects 'right' as Frac")

            // Cross multiply: a/b < c/d iff a*d < c*b (assuming positive denominators)
            MezzeBool(l.numerator * r.denominator < r.numerator * l.denominator)
        }

        context["greaterThan_frac"] = NativeMezzeFunction("greaterThan_frac") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("greaterThan_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("greaterThan_frac", "expects 'right' as Frac")

            // Cross multiply: a/b > c/d iff a*d > c*b (assuming positive denominators)
            MezzeBool(l.numerator * r.denominator > r.numerator * l.denominator)
        }

        // Conversion operations
        context["fracToInt"] = NativeMezzeFunction("fracToInt") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeFrac ?: throw NativeFunctionException("fracToInt", "expects 'val' as Frac")
            MezzeInt(value.numerator / value.denominator)
        }

        context["fracToFloat"] = NativeMezzeFunction("fracToFloat") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("fracToFloat", "expects 'frac' as Frac")
            MezzeFloat(n.numerator.toDouble() / n.denominator.toDouble())
        }

        context["fracToString"] = NativeMezzeFunction("fracToString") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("fracToString", "expects 'frac' as Frac")
            MezzeString("${n.numerator}/${n.denominator}")
        }

        // Hidden native operations - aliases for stdlib
        context["__native_frac_add"] = NativeMezzeFunction("__native_frac_add") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_add", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_add", "expects 'right' as Frac")

            // a/b + c/d = (a*d + c*b) / (b*d)
            val numerator = l.numerator * r.denominator + r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_subtract"] = NativeMezzeFunction("__native_frac_subtract") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_subtract", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_subtract", "expects 'right' as Frac")

            // a/b - c/d = (a*d - c*b) / (b*d)
            val numerator = l.numerator * r.denominator - r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_multiply"] = NativeMezzeFunction("__native_frac_multiply") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_multiply", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_multiply", "expects 'right' as Frac")

            // a/b * c/d = (a*c) / (b*d)
            val numerator = l.numerator * r.numerator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_divide"] = NativeMezzeFunction("__native_frac_divide") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_divide", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_divide", "expects 'right' as Frac")

            if (r.numerator == 0L) throw MezzeException("Division by zero")

            // a/b ÷ c/d = a/b * d/c = (a*d) / (b*c)
            val numerator = l.numerator * r.denominator
            val denominator = l.denominator * r.numerator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_equals"] = NativeMezzeFunction("__native_frac_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_equals", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_equals", "expects 'right' as Frac")

            // Cross multiply: a/b == c/d iff a*d == b*c
            MezzeBool(l.numerator * r.denominator == r.numerator * l.denominator)
        }

        context["__native_frac_less_than"] = NativeMezzeFunction("__native_frac_less_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_less_than", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_less_than", "expects 'right' as Frac")

            // Cross multiply: a/b < c/d iff a*d < c*b (assuming positive denominators)
            MezzeBool(l.numerator * r.denominator < r.numerator * l.denominator)
        }

        context["__native_frac_greater_than"] = NativeMezzeFunction("__native_frac_greater_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_greater_than", "expects 'left' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_greater_than", "expects 'right' as Frac")

            // Cross multiply: a/b > c/d iff a*d > c*b (assuming positive denominators)
            MezzeBool(l.numerator * r.denominator > r.numerator * l.denominator)
        }

        // Compare: returns Ordering (LT, EQ, GT)
        context["__native_frac_compare"] = NativeMezzeFunction("__native_frac_compare") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_compare", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_compare", "expects 'r' as Frac")

            // Cross multiply: a*d vs b*c
            val leftProduct = l.numerator * r.denominator
            val rightProduct = r.numerator * l.denominator

            when {
                leftProduct < rightProduct -> MezzeVariant("LT", null)
                leftProduct > rightProduct -> MezzeVariant("GT", null)
                else -> MezzeVariant("EQ", null)
            }
        }

        context["__native_frac_to_float"] = NativeMezzeFunction("__native_frac_to_float") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_to_float", "expects 'frac' as Frac")
            MezzeFloat(n.numerator.toDouble() / n.denominator.toDouble())
        }

        context["__native_frac_to_string"] = NativeMezzeFunction("__native_frac_to_string") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_to_string", "expects 'frac' as Frac")
            MezzeString("${n.numerator}/${n.denominator}")
        }

        // Frac utility operations
        context["__native_make_frac"] = NativeMezzeFunction("__native_make_frac") { args ->
            val record = args[0] as MezzeRecord
            val numerator = record.fields["numerator"] as? MezzeInt ?: throw NativeFunctionException("__native_make_frac", "expects 'numerator' as Int")
            val denominator = record.fields["denominator"] as? MezzeInt ?: throw NativeFunctionException("__native_make_frac", "expects 'denominator' as Int")
            if (denominator.value == 0L) throw MezzeException("Fraction denominator cannot be zero")
            MezzeFrac(numerator.value, denominator.value).reduce()
        }

        context["__native_frac_numerator"] = NativeMezzeFunction("__native_frac_numerator") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_numerator", "expects 'frac' as Frac")
            MezzeInt(n.numerator)
        }

        context["__native_frac_denominator"] = NativeMezzeFunction("__native_frac_denominator") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_denominator", "expects 'frac' as Frac")
            MezzeInt(n.denominator)
        }

        context["__native_reduce_frac"] = NativeMezzeFunction("__native_reduce_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_reduce_frac", "expects 'frac' as Frac")
            n.reduce()
        }

        context["__native_abs_frac"] = NativeMezzeFunction("__native_abs_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_abs_frac", "expects 'frac' as Frac")
            MezzeFrac(kotlin.math.abs(n.numerator), n.denominator).reduce()
        }

        context["__native_negate_frac"] = NativeMezzeFunction("__native_negate_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_negate_frac", "expects 'frac' as Frac")
            MezzeFrac(-n.numerator, n.denominator).reduce()
        }

        context["__native_frac_neg"] = NativeMezzeFunction("__native_frac_neg") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_neg", "expects 'n' as Frac")
            MezzeFrac(-value.numerator, value.denominator).reduce()
        }
    }
}
