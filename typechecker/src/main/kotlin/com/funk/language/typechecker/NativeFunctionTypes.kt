package com.funk.language.typechecker

import com.funk.language.ast.FunkType
import com.funk.language.typechecker.types.BasicTypes
import com.funk.language.typechecker.types.collections.ArrayListTypes
import com.funk.language.typechecker.types.collections.ListTypes
import com.funk.language.typechecker.types.collections.MapTypes
import com.funk.language.typechecker.types.collections.SetTypes
import com.funk.language.typechecker.types.collections.NonEmptyListTypes
import com.funk.language.typechecker.types.collections.DequeTypes
import com.funk.language.typechecker.types.collections.BytesTypes
import com.funk.language.typechecker.types.collections.ByteBufferTypes
import com.funk.language.typechecker.types.math.MathCoreTypes
import com.funk.language.typechecker.types.math.MathTrigTypes
import com.funk.language.typechecker.types.math.MathRandomTypes
import com.funk.language.typechecker.types.effects.LogTypes
import com.funk.language.typechecker.types.effects.FilesystemTypes
import com.funk.language.typechecker.types.effects.TimeTypes
import com.funk.language.typechecker.types.effects.EnvironmentTypes
import com.funk.language.typechecker.types.effects.ContinuationTypes
import com.funk.language.typechecker.types.effects.LoomTypes

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: FunkType, errorType: FunkType): FunkType {
    return FunkType.TypeApplication("Result", listOf(valueType, errorType))
}

/**
 * Central registry for all native function type signatures.
 * Coordinates registration of types from domain-specific modules.
 */
object NativeFunctionTypes {

    fun registerAll(context: MutableMap<String, FunkType>) {
        BasicTypes.registerAll(context)

        ArrayListTypes.registerAll(context)
        ListTypes.registerAll(context)
        MapTypes.registerAll(context)
        SetTypes.registerAll(context)
        NonEmptyListTypes.registerAll(context)
        DequeTypes.registerAll(context)
        BytesTypes.registerAll(context)
        ByteBufferTypes.registerAll(context)

        MathCoreTypes.registerAll(context)
        MathTrigTypes.registerAll(context)
        MathRandomTypes.registerAll(context)

        LogTypes.registerAll(context)
        FilesystemTypes.registerAll(context)
        TimeTypes.registerAll(context)
        EnvironmentTypes.registerAll(context)
        ContinuationTypes.registerAll(context)
        LoomTypes.registerAll(context)

        context["crash"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            FunkType.VariantType(emptyMap())
        )
    }
}