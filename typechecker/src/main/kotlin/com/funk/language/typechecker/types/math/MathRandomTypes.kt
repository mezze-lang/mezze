package com.funk.language.typechecker.types.math

import com.funk.language.ast.FunkType

/**
 * Type signatures for random number generation functions
 *
 * Corresponds to stdlib file: std/math/random.funk
 *
 * Provides types for:
 * - Seeded random number generation (float, int, nat)
 * - Seed management functions
 */
object MathRandomTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerSeededRandomFunctions(context)
        registerSeedManagement(context)
    }

    private fun registerSeededRandomFunctions(context: MutableMap<String, FunkType>) {
        // Generate random float from seed
        context["__native_seeded_random_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("seed" to FunkType.IntType)),
            FunkType.FloatType
        )

        // Generate random int in range from seed
        context["__native_seeded_random_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "seed" to FunkType.IntType,
                "min" to FunkType.IntType,
                "max" to FunkType.IntType
            )),
            FunkType.IntType
        )

        // Generate random nat up to max from seed
        context["__native_seeded_random_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "seed" to FunkType.IntType,
                "max" to FunkType.NatType
            )),
            FunkType.NatType
        )
    }

    private fun registerSeedManagement(context: MutableMap<String, FunkType>) {
        // Update random seed
        context["__native_update_seed"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "old_seed" to FunkType.IntType,
                "new_seed" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )
    }
}
