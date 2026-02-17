package com.mezze.language.typechecker.types.math

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for random number generation functions
 *
 * Corresponds to stdlib file: std/math/random.mz
 *
 * Provides types for:
 * - Seeded random number generation (float, int, nat)
 * - Seed management functions
 */
object MathRandomTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSeededRandomFunctions(context)
        registerSeedManagement(context)
    }

    private fun registerSeededRandomFunctions(context: MutableMap<String, MezzeType>) {
        // Generate random float from seed
        context["__native_seeded_random_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("seed" to MezzeType.IntType)),
            MezzeType.FloatType
        )

        // Generate random int in range from seed
        context["__native_seeded_random_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "seed" to MezzeType.IntType,
                "min" to MezzeType.IntType,
                "max" to MezzeType.IntType
            )),
            MezzeType.IntType
        )

        // Generate random nat up to max from seed
        context["__native_seeded_random_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "seed" to MezzeType.IntType,
                "max" to MezzeType.NatType
            )),
            MezzeType.NatType
        )
    }

    private fun registerSeedManagement(context: MutableMap<String, MezzeType>) {
        // Update random seed
        context["__native_update_seed"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "old_seed" to MezzeType.IntType,
                "new_seed" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )
    }
}
