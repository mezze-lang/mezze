package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Frac type operations.
 *
 * Frac is the fraction type representing exact rational numbers (numerator/denominator).
 *
 * Arithmetic operations:
 * - a/b + c/d = (a*d + c*b) / (b*d)
 * - a/b - c/d = (a*d - c*b) / (b*d)
 * - a/b * c/d = (a*c) / (b*d)
 * - a/b / c/d = (a*d) / (b*c)
 */
object FracImplementations {

    /**
     * Register all Frac runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Arithmetic operations
        context["__native_frac_add"] = NativeMezzeFunction("__native_frac_add") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_add", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_add", "expects 'r' as Frac")

            // a/b + c/d = (a*d + c*b) / (b*d)
            val numerator = l.numerator * r.denominator + r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_subtract"] = NativeMezzeFunction("__native_frac_subtract") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_subtract", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_subtract", "expects 'r' as Frac")

            // a/b - c/d = (a*d - c*b) / (b*d)
            val numerator = l.numerator * r.denominator - r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_multiply"] = NativeMezzeFunction("__native_frac_multiply") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_multiply", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_multiply", "expects 'r' as Frac")

            // a/b * c/d = (a*c) / (b*d)
            val numerator = l.numerator * r.numerator
            val denominator = l.denominator * r.denominator
            MezzeFrac(numerator, denominator).reduce()
        }

        context["__native_frac_divide"] = NativeMezzeFunction("__native_frac_divide") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_divide", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_divide", "expects 'r' as Frac")

            if (r.numerator == 0L) throw MezzeException("Division by zero")

            // a/b / c/d = a/b * d/c = (a*d) / (b*c)
            val numerator = l.numerator * r.denominator
            val denominator = l.denominator * r.numerator
            MezzeFrac(numerator, denominator).reduce()
        }

        // Comparison operations
        context["__native_frac_equals"] = NativeMezzeFunction("__native_frac_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_equals", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_equals", "expects 'r' as Frac")

            // Cross multiply: a/b == c/d iff a*d == b*c
            MezzeBool(l.numerator * r.denominator == r.numerator * l.denominator)
        }

        context["__native_frac_less_than"] = NativeMezzeFunction("__native_frac_less_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_less_than", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_less_than", "expects 'r' as Frac")

            // Cross multiply: a/b < c/d iff a*d < c*b (assuming positive denominators)
            MezzeBool(l.numerator * r.denominator < r.numerator * l.denominator)
        }

        context["__native_frac_greater_than"] = NativeMezzeFunction("__native_frac_greater_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_greater_than", "expects 'l' as Frac")
            val r = record.fields["r"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_greater_than", "expects 'r' as Frac")

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

        // Conversion operations
        context["__native_frac_to_float"] = NativeMezzeFunction("__native_frac_to_float") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_to_float", "expects 'n' as Frac")
            MezzeFloat(n.numerator.toDouble() / n.denominator.toDouble())
        }

        context["__native_frac_to_string"] = NativeMezzeFunction("__native_frac_to_string") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_to_string", "expects 'n' as Frac")
            MezzeString("${n.numerator}/${n.denominator}")
        }

        // Construction and utility operations
        context["__native_make_frac"] = NativeMezzeFunction("__native_make_frac") { args ->
            val record = args[0] as MezzeRecord
            val numerator = record.fields["numerator"] as? MezzeInt ?: throw NativeFunctionException("__native_make_frac", "expects 'numerator' as Int")
            val denominator = record.fields["denominator"] as? MezzeInt ?: throw NativeFunctionException("__native_make_frac", "expects 'denominator' as Int")
            if (denominator.value == 0L) throw MezzeException("Fraction denominator cannot be zero")
            MezzeFrac(numerator.value, denominator.value).reduce()
        }

        context["__native_frac_numerator"] = NativeMezzeFunction("__native_frac_numerator") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_numerator", "expects 'n' as Frac")
            MezzeInt(n.numerator)
        }

        context["__native_frac_denominator"] = NativeMezzeFunction("__native_frac_denominator") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_frac_denominator", "expects 'n' as Frac")
            MezzeInt(n.denominator)
        }

        context["__native_reduce_frac"] = NativeMezzeFunction("__native_reduce_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_reduce_frac", "expects 'n' as Frac")
            n.reduce()
        }

        context["__native_abs_frac"] = NativeMezzeFunction("__native_abs_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_abs_frac", "expects 'n' as Frac")
            MezzeFrac(kotlin.math.abs(n.numerator), n.denominator).reduce()
        }

        context["__native_negate_frac"] = NativeMezzeFunction("__native_negate_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFrac ?: throw NativeFunctionException("__native_negate_frac", "expects 'n' as Frac")
            MezzeFrac(-n.numerator, n.denominator).reduce()
        }
    }
}
