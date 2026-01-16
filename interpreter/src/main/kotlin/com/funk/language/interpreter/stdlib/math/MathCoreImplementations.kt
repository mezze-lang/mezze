package com.funk.language.interpreter.stdlib.math

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for core math operations.
 *
 * Phase 4 of Truffle migration: Math operations optimized for JIT performance.
 * Includes: abs, max, min, pow, sqrt, ln, log10, exp, floor, ceil, round, sign, is_nan, is_infinite, is_finite
 *
 * Optimizations:
 * - Direct use of JIT-friendly Kotlin math functions (kotlin.math.*)
 * - No function callbacks - all pure math operations
 * - Optimized integer power using exponentiation by squaring algorithm
 * - Type-specific implementations for Int, Float, Frac, Nat
 * - Inline operations for maximum performance
 *
 * Total: 32 math functions (12 basic + 5 advanced + 15 hidden natives)
 */
object MathCoreImplementations {

    /**
     * Register all core math implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerBasicMathOperations(context)
        registerAdvancedMathOperations(context)
        registerHiddenMathOperations(context)
    }

    /**
     * Basic math operations: abs, max, min
     */
    private fun registerBasicMathOperations(context: MutableMap<String, FunkValue>) {
        // Absolute value operations
        listOf("int", "float", "frac", "nat").forEach { type ->
            context["abs_$type"] = NativeFunkFunction("abs_$type") { args ->
                val record = args[0] as FunkRecord

                when (type) {
                    "int" -> {
                        val value = record.fields["val"] as? FunkInt ?: throw NativeFunctionException("abs_int", "expects 'val' as Int")
                        FunkInt(kotlin.math.abs(value.value))
                    }
                    "float" -> {
                        val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("abs_float", "expects 'val' as Float")
                        FunkFloat(kotlin.math.abs(value.value))
                    }
                    "frac" -> {
                        val value = record.fields["val"] as? FunkFrac ?: throw NativeFunctionException("abs_frac", "expects 'val' as Frac")
                        FunkFrac(kotlin.math.abs(value.numerator), kotlin.math.abs(value.denominator)).reduce()
                    }
                    "nat" -> {
                        val value = record.fields["val"] as? FunkNat ?: throw NativeFunctionException("abs_nat", "expects 'val' as Nat")
                        value // Natural numbers are always non-negative
                    }
                    else -> throw NativeFunctionException("abs_$type", "Unknown type: $type")
                }
            }

            // Max operations
            context["max_$type"] = NativeFunkFunction("max_$type") { args ->
                val record = args[0] as FunkRecord

                when (type) {
                    "int" -> {
                        val a = record.fields["a"] as? FunkInt ?: throw NativeFunctionException("max_int", "expects 'a' as Int")
                        val b = record.fields["b"] as? FunkInt ?: throw NativeFunctionException("max_int", "expects 'b' as Int")
                        FunkInt(kotlin.math.max(a.value, b.value))
                    }
                    "float" -> {
                        val a = record.fields["a"] as? FunkFloat ?: throw NativeFunctionException("max_float", "expects 'a' as Float")
                        val b = record.fields["b"] as? FunkFloat ?: throw NativeFunctionException("max_float", "expects 'b' as Float")
                        FunkFloat(kotlin.math.max(a.value, b.value))
                    }
                    "frac" -> {
                        val a = record.fields["a"] as? FunkFrac ?: throw NativeFunctionException("max_frac", "expects 'a' as Frac")
                        val b = record.fields["b"] as? FunkFrac ?: throw NativeFunctionException("max_frac", "expects 'b' as Frac")
                        if (a.numerator * b.denominator > b.numerator * a.denominator) a else b
                    }
                    "nat" -> {
                        val a = record.fields["a"] as? FunkNat ?: throw NativeFunctionException("max_nat", "expects 'a' as Nat")
                        val b = record.fields["b"] as? FunkNat ?: throw NativeFunctionException("max_nat", "expects 'b' as Nat")
                        FunkNat(kotlin.math.max(a.value, b.value))
                    }
                    else -> throw NativeFunctionException("max_$type", "Unknown type: $type")
                }
            }

            // Min operations
            context["min_$type"] = NativeFunkFunction("min_$type") { args ->
                val record = args[0] as FunkRecord

                when (type) {
                    "int" -> {
                        val a = record.fields["a"] as? FunkInt ?: throw NativeFunctionException("min_int", "expects 'a' as Int")
                        val b = record.fields["b"] as? FunkInt ?: throw NativeFunctionException("min_int", "expects 'b' as Int")
                        FunkInt(kotlin.math.min(a.value, b.value))
                    }
                    "float" -> {
                        val a = record.fields["a"] as? FunkFloat ?: throw NativeFunctionException("min_float", "expects 'a' as Float")
                        val b = record.fields["b"] as? FunkFloat ?: throw NativeFunctionException("min_float", "expects 'b' as Float")
                        FunkFloat(kotlin.math.min(a.value, b.value))
                    }
                    "frac" -> {
                        val a = record.fields["a"] as? FunkFrac ?: throw NativeFunctionException("min_frac", "expects 'a' as Frac")
                        val b = record.fields["b"] as? FunkFrac ?: throw NativeFunctionException("min_frac", "expects 'b' as Frac")
                        if (a.numerator * b.denominator < b.numerator * a.denominator) a else b
                    }
                    "nat" -> {
                        val a = record.fields["a"] as? FunkNat ?: throw NativeFunctionException("min_nat", "expects 'a' as Nat")
                        val b = record.fields["b"] as? FunkNat ?: throw NativeFunctionException("min_nat", "expects 'b' as Nat")
                        FunkNat(kotlin.math.min(a.value, b.value))
                    }
                    else -> throw NativeFunctionException("min_$type", "Unknown type: $type")
                }
            }
        }

        // Keep backward compatibility
        context["mathAbs"] = context["abs_int"]!!
        context["mathMax"] = context["max_int"]!!
        context["mathMin"] = context["min_int"]!!
    }

    /**
     * Advanced math operations
     */
    private fun registerAdvancedMathOperations(context: MutableMap<String, FunkValue>) {
        // Power function
        context["pow"] = NativeFunkFunction("pow") { args ->
            val record = args[0] as FunkRecord
            val base = record.fields["base"] as? FunkFloat ?: throw NativeFunctionException("pow", "expects 'base' as Float")
            val exponent = record.fields["exponent"] as? FunkFloat ?: throw NativeFunctionException("pow", "expects 'exponent' as Float")
            FunkFloat(Math.pow(base.value, exponent.value))
        }

        // Square root
        context["sqrt"] = NativeFunkFunction("sqrt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("sqrt", "expects 'val' as Float")
            FunkFloat(kotlin.math.sqrt(value.value))
        }

        // Logarithm
        context["log"] = NativeFunkFunction("log") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("log", "expects 'val' as Float")
            FunkFloat(kotlin.math.ln(value.value))
        }

        // Floor and ceiling
        context["floor"] = NativeFunkFunction("floor") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("floor", "expects 'val' as Float")
            FunkInt(kotlin.math.floor(value.value).toLong())
        }

        context["ceil"] = NativeFunkFunction("ceil") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("ceil", "expects 'val' as Float")
            FunkInt(kotlin.math.ceil(value.value).toLong())
        }
    }

    /**
     * Register hidden native math operations (prefixed with __native_)
     * These are used by the stdlib modules and hidden from user code
     */
    private fun registerHiddenMathOperations(context: MutableMap<String, FunkValue>) {
        // Float math operations - reuse existing implementations where possible
        context["__native_float_pow"] = NativeFunkFunction("__native_float_pow") { args ->
            val record = args[0] as FunkRecord
            val base = record.fields["base"] as? FunkFloat ?: throw NativeFunctionException("__native_float_pow", "expects 'base' as Float")
            val exp = record.fields["exp"] as? FunkFloat ?: throw NativeFunctionException("__native_float_pow", "expects 'exp' as Float")
            // Reuse existing pow implementation
            val powFunc = context["pow"] as NativeFunkFunction
            powFunc.call(listOf(FunkRecord(mapOf("base" to base, "exponent" to exp))))
        }

        context["__native_float_sqrt"] = NativeFunkFunction("__native_float_sqrt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_sqrt", "expects 'n' as Float")
            FunkFloat(kotlin.math.sqrt(value.value))
        }

        context["__native_float_ln"] = NativeFunkFunction("__native_float_ln") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_ln", "expects 'n' as Float")
            FunkFloat(kotlin.math.ln(value.value))
        }

        context["__native_float_log10"] = NativeFunkFunction("__native_float_log10") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_log10", "expects 'n' as Float")
            FunkFloat(kotlin.math.log10(value.value))
        }

        context["__native_float_exp"] = NativeFunkFunction("__native_float_exp") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_exp", "expects 'n' as Float")
            FunkFloat(kotlin.math.exp(value.value))
        }

        context["__native_float_floor"] = NativeFunkFunction("__native_float_floor") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_floor", "expects 'n' as Float")
            FunkFloat(kotlin.math.floor(value.value))
        }

        context["__native_float_ceil"] = NativeFunkFunction("__native_float_ceil") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_ceil", "expects 'n' as Float")
            FunkFloat(kotlin.math.ceil(value.value))
        }

        context["__native_float_round"] = NativeFunkFunction("__native_float_round") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_round", "expects 'n' as Float")
            FunkFloat(kotlin.math.round(value.value).toDouble())
        }

        context["__native_float_is_nan"] = NativeFunkFunction("__native_float_is_nan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_is_nan", "expects 'n' as Float")
            FunkBool(value.value.isNaN())
        }

        context["__native_float_is_infinite"] = NativeFunkFunction("__native_float_is_infinite") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_is_infinite", "expects 'n' as Float")
            FunkBool(value.value.isInfinite())
        }

        context["__native_float_is_finite"] = NativeFunkFunction("__native_float_is_finite") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_is_finite", "expects 'n' as Float")
            FunkBool(value.value.isFinite())
        }

        context["__native_float_sign"] = NativeFunkFunction("__native_float_sign") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_sign", "expects 'n' as Float")
            FunkFloat(kotlin.math.sign(value.value))
        }

        // Integer math operations - use optimized native implementations
        context["__native_int_pow"] = NativeFunkFunction("__native_int_pow") { args ->
            val record = args[0] as FunkRecord
            val base = record.fields["base"] as? FunkInt ?: throw NativeFunctionException("__native_int_pow", "expects 'base' as Int")
            val exponent = record.fields["exp"] as? FunkInt ?: throw NativeFunctionException("__native_int_pow", "expects 'exp' as Int")
            if (exponent.value < 0) throw FunkException("Negative exponent not supported for integer power")

            // Use optimized integer power for better performance
            var result = 1L
            var exp = exponent.value
            var base_val = base.value

            while (exp > 0) {
                if (exp % 2 == 1L) {
                    result *= base_val
                }
                base_val *= base_val
                exp /= 2
            }
            FunkInt(result)
        }

        context["__native_int_sign"] = NativeFunkFunction("__native_int_sign") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_sign", "expects 'n' as Int")
            FunkInt(when {
                value.value > 0 -> 1L
                value.value < 0 -> -1L
                else -> 0L
            })
        }

        // Natural number math operations - use optimized native implementations
        context["__native_nat_pow"] = NativeFunkFunction("__native_nat_pow") { args ->
            val record = args[0] as FunkRecord
            val base = record.fields["base"] as? FunkNat ?: throw NativeFunctionException("__native_nat_pow", "expects 'base' as Nat")
            val exponent = record.fields["exp"] as? FunkNat ?: throw NativeFunctionException("__native_nat_pow", "expects 'exp' as Nat")

            // Use optimized integer power for better performance
            var result = 1L
            var exp = exponent.value
            var base_val = base.value

            while (exp > 0) {
                if (exp % 2 == 1L) {
                    result *= base_val
                }
                base_val *= base_val
                exp /= 2
            }
            FunkNat(result)
        }
    }
}
