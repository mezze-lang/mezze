package com.funk.language.interpreter.stdlib.math

import com.funk.language.interpreter.*
import kotlin.random.Random

/**
 * Random number operations implementations
 * Includes: randomInt, randomFloat, randomNat, seeded random operations, thread-local state management
 */
object MathRandomImplementations {

    /**
     * Register all random number implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerRandomOperations(context)
        registerSeededRandomOperations(context)
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
