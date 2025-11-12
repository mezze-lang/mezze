package com.funk.language.interpreter.stdlib.math

import com.funk.language.interpreter.*
import kotlin.random.Random

/**
 * Truffle runtime implementations for math abilities
 */
object TruffleMathImplementations {
    
    /**
     * Register all math runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerBasicMathOperations(context)
        registerRandomOperations(context)
        registerAdvancedMathOperations(context)
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
     * Random number operations
     */
    private fun registerRandomOperations(context: MutableMap<String, FunkValue>) {
        // Random integer
        context["randomInt"] = NativeFunkFunction("randomInt") { args ->
            val record = args[0] as FunkRecord
            val min = record.fields["min"] as? FunkInt ?: throw NativeFunctionException("randomInt", "expects 'min' as Int")
            val max = record.fields["max"] as? FunkInt ?: throw NativeFunctionException("randomInt", "expects 'max' as Int")
            FunkInt(Random.nextLong(min.value, max.value + 1)) // +1 because nextLong is exclusive of upper bound
        }
        
        // Random float
        context["randomFloat"] = NativeFunkFunction("randomFloat") { args ->
            FunkFloat(Random.nextDouble())
        }
        
        // Random natural number
        context["randomNat"] = NativeFunkFunction("randomNat") { args ->
            val record = args[0] as FunkRecord
            val max = record.fields["max"] as? FunkNat ?: throw NativeFunctionException("randomNat", "expects 'max' as Nat")
            FunkNat(Random.nextLong(0, max.value + 1))
        }
        
        // Keep backward compatibility
        context["native_random_int"] = context["randomInt"]!!
        context["native_random_double"] = context["randomFloat"]!!
        
        // Legacy random function
        context["native_random"] = NativeFunkFunction("native_random") { args ->
            val record = args[0] as FunkRecord
            val max = record.fields["max"] as? FunkInt ?: throw NativeFunctionException("native_random", "expects 'max' as Int")
            FunkInt(Random.nextLong(0, max.value + 1))
        }
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
        
        // Trigonometric functions
        context["sin"] = NativeFunkFunction("sin") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("sin", "expects 'val' as Float")
            FunkFloat(kotlin.math.sin(value.value))
        }
        
        context["cos"] = NativeFunkFunction("cos") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("cos", "expects 'val' as Float")
            FunkFloat(kotlin.math.cos(value.value))
        }
        
        context["tan"] = NativeFunkFunction("tan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("tan", "expects 'val' as Float")
            FunkFloat(kotlin.math.tan(value.value))
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
        
        // Register hidden native implementations for stdlib modules
        registerHiddenMathOperations(context)
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
        
        // Trigonometric functions
        context["__native_float_sin"] = NativeFunkFunction("__native_float_sin") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_sin", "expects 'n' as Float")
            FunkFloat(kotlin.math.sin(value.value))
        }

        context["__native_float_cos"] = NativeFunkFunction("__native_float_cos") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_cos", "expects 'n' as Float")
            FunkFloat(kotlin.math.cos(value.value))
        }

        context["__native_float_tan"] = NativeFunkFunction("__native_float_tan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_tan", "expects 'n' as Float")
            FunkFloat(kotlin.math.tan(value.value))
        }

        context["__native_float_asin"] = NativeFunkFunction("__native_float_asin") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_asin", "expects 'n' as Float")
            FunkFloat(kotlin.math.asin(value.value))
        }

        context["__native_float_acos"] = NativeFunkFunction("__native_float_acos") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_acos", "expects 'n' as Float")
            FunkFloat(kotlin.math.acos(value.value))
        }

        context["__native_float_atan"] = NativeFunkFunction("__native_float_atan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_atan", "expects 'n' as Float")
            FunkFloat(kotlin.math.atan(value.value))
        }
        
        context["__native_float_atan2"] = NativeFunkFunction("__native_float_atan2") { args ->
            val record = args[0] as FunkRecord
            val y = record.fields["y"] as? FunkFloat ?: throw NativeFunctionException("__native_float_atan2", "expects 'y' as Float")
            val x = record.fields["x"] as? FunkFloat ?: throw NativeFunctionException("__native_float_atan2", "expects 'x' as Float")
            FunkFloat(kotlin.math.atan2(y.value, x.value))
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
        
        // Random operations with proper seeded generator
        registerSeededRandomOperations(context)
    }
    
    /**
     * Register seeded random operations with proper thread-local state management
     */
    private fun registerSeededRandomOperations(context: MutableMap<String, FunkValue>) {
        // Thread-local random generator for proper seeding
        val threadLocalRandom = ThreadLocal.withInitial { kotlin.random.Random.Default as kotlin.random.Random }
        
        // Global seeded random generators (keyed by seed for deterministic behavior)
        val seededRandoms = mutableMapOf<Int, kotlin.random.Random>()
        
        // System random implementations
        context["__native_random_float"] = NativeFunkFunction("__native_random_float") { args ->
            FunkFloat(threadLocalRandom.get().nextDouble())
        }
        
        context["__native_random_int"] = NativeFunkFunction("__native_random_int") { args ->
            val record = args[0] as FunkRecord
            val min = record.fields["min"] as? FunkInt ?: throw NativeFunctionException("__native_random_int", "expects 'min' as Int")
            val max = record.fields["max"] as? FunkInt ?: throw NativeFunctionException("__native_random_int", "expects 'max' as Int")
            FunkInt(threadLocalRandom.get().nextLong(min.value, max.value + 1))
        }
        
        context["__native_random_nat"] = NativeFunkFunction("__native_random_nat") { args ->
            val record = args[0] as FunkRecord
            val max = record.fields["max"] as? FunkNat ?: throw NativeFunctionException("__native_random_nat", "expects 'max' as Nat")
            FunkNat(threadLocalRandom.get().nextLong(0, max.value + 1))
        }
        
        context["__native_random_seed"] = NativeFunkFunction("__native_random_seed") { args ->
            val record = args[0] as FunkRecord
            val seed = record.fields["seed"] as? FunkInt ?: throw NativeFunctionException("__native_random_seed", "expects 'seed' as Int")
            // Set a new seeded random generator for this thread
            threadLocalRandom.set(kotlin.random.Random(seed.value.toInt()))
            FunkRecord(emptyMap()) // Return empty record (unit type)
        }
        
        // Seeded random implementations (deterministic)
        context["__native_seeded_random_float"] = NativeFunkFunction("__native_seeded_random_float") { args ->
            val record = args[0] as FunkRecord
            val seed = record.fields["seed"] as? FunkInt ?: throw NativeFunctionException("__native_seeded_random_float", "expects 'seed' as Int")
            val random = seededRandoms.getOrPut(seed.value.toInt()) { kotlin.random.Random(seed.value.toInt()) }
            FunkFloat(random.nextDouble())
        }
        
        context["__native_seeded_random_int"] = NativeFunkFunction("__native_seeded_random_int") { args ->
            val record = args[0] as FunkRecord
            val seed = record.fields["seed"] as? FunkInt ?: throw NativeFunctionException("__native_seeded_random_int", "expects 'seed' as Int")
            val min = record.fields["min"] as? FunkInt ?: throw NativeFunctionException("__native_seeded_random_int", "expects 'min' as Int")
            val max = record.fields["max"] as? FunkInt ?: throw NativeFunctionException("__native_seeded_random_int", "expects 'max' as Int")
            val random = seededRandoms.getOrPut(seed.value.toInt()) { kotlin.random.Random(seed.value.toInt()) }
            FunkInt(random.nextLong(min.value, max.value + 1))
        }
        
        context["__native_seeded_random_nat"] = NativeFunkFunction("__native_seeded_random_nat") { args ->
            val record = args[0] as FunkRecord
            val seed = record.fields["seed"] as? FunkInt ?: throw NativeFunctionException("__native_seeded_random_nat", "expects 'seed' as Int")
            val max = record.fields["max"] as? FunkNat ?: throw NativeFunctionException("__native_seeded_random_nat", "expects 'max' as Nat")
            val random = seededRandoms.getOrPut(seed.value.toInt()) { kotlin.random.Random(seed.value.toInt()) }
            FunkNat(random.nextLong(0, max.value + 1))
        }
        
        context["__native_update_seed"] = NativeFunkFunction("__native_update_seed") { args ->
            val record = args[0] as FunkRecord
            val oldSeed = record.fields["old_seed"] as? FunkInt ?: throw NativeFunctionException("__native_update_seed", "expects 'old_seed' as Int")
            val newSeed = record.fields["new_seed"] as? FunkInt ?: throw NativeFunctionException("__native_update_seed", "expects 'new_seed' as Int")
            // Update the seeded random generator
            seededRandoms[oldSeed.value.toInt()] = kotlin.random.Random(newSeed.value.toInt())
            FunkRecord(emptyMap()) // Return empty record (unit type)
        }
        
        // Also register for backward compatibility with existing random functions
        context["__native_random_float"]?.let { func ->
            context["randomFloat"] = func
            context["native_random_double"] = func
        }
        
        context["__native_random_int"]?.let { func ->
            context["randomInt"] = func
            context["native_random_int"] = func
        }
        
        context["__native_random_nat"]?.let { func ->
            context["randomNat"] = func
        }
        
        // Legacy random function
        context["native_random"] = NativeFunkFunction("native_random") { args ->
            val record = args[0] as FunkRecord
            val max = record.fields["max"] as? FunkInt ?: throw NativeFunctionException("native_random", "expects 'max' as Int")
            FunkInt(threadLocalRandom.get().nextLong(0, max.value + 1))
        }
    }
}