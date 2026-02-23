package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.BasicTypes
import com.mezze.language.typechecker.types.CharTypes
import com.mezze.language.typechecker.types.collections.ArrayListTypes
import com.mezze.language.typechecker.types.collections.ListTypes
import com.mezze.language.typechecker.types.collections.MapTypes
import com.mezze.language.typechecker.types.collections.SetTypes
import com.mezze.language.typechecker.types.collections.NonEmptyListTypes
import com.mezze.language.typechecker.types.collections.DequeTypes
import com.mezze.language.typechecker.types.collections.BytesTypes
import com.mezze.language.typechecker.types.collections.ByteBufferTypes
import com.mezze.language.typechecker.types.collections.SortedMapTypes
import com.mezze.language.typechecker.types.collections.SortedSetTypes
import com.mezze.language.typechecker.types.math.MathCoreTypes
import com.mezze.language.typechecker.types.math.MathTrigTypes
import com.mezze.language.typechecker.types.math.MathRandomTypes
import com.mezze.language.typechecker.types.effects.LogTypes
import com.mezze.language.typechecker.types.effects.FilesystemTypes
import com.mezze.language.typechecker.types.effects.TimeTypes
import com.mezze.language.typechecker.types.effects.EnvironmentTypes
import com.mezze.language.typechecker.types.effects.ContinuationTypes
import com.mezze.language.typechecker.types.effects.LoomTypes
import com.mezze.language.typechecker.types.effects.OSThreadTypes
import com.mezze.language.typechecker.types.effects.CoopTypes
import com.mezze.language.typechecker.types.effects.ParallelTypes
import com.mezze.language.typechecker.types.effects.STMTypes
import com.mezze.language.typechecker.types.effects.HttpTypes
import com.mezze.language.typechecker.types.concurrent.AtomTypes
import com.mezze.language.typechecker.types.concurrent.AgentTypes
import com.mezze.language.typechecker.types.concurrent.ChannelTypes
import com.mezze.language.typechecker.types.concurrent.MutexTypes
import com.mezze.language.typechecker.types.concurrent.SemaphoreTypes
import com.mezze.language.typechecker.types.concurrent.LatchTypes
import com.mezze.language.typechecker.types.concurrent.WaitGroupTypes
import com.mezze.language.typechecker.types.concurrent.CancellationTokenTypes
import com.mezze.language.typechecker.types.codec.JsonTypes

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Result", listOf(valueType, errorType))
}

/**
 * Central registry for all native function type signatures.
 * Coordinates registration of types from domain-specific modules.
 */
object NativeFunctionTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        BasicTypes.registerAll(context)
        CharTypes.registerAll(context)

        ArrayListTypes.registerAll(context)
        ListTypes.registerAll(context)
        MapTypes.registerAll(context)
        SetTypes.registerAll(context)
        NonEmptyListTypes.registerAll(context)
        DequeTypes.registerAll(context)
        BytesTypes.registerAll(context)
        ByteBufferTypes.registerAll(context)
        SortedMapTypes.registerAll(context)
        SortedSetTypes.registerAll(context)

        MathCoreTypes.registerAll(context)
        MathTrigTypes.registerAll(context)
        MathRandomTypes.registerAll(context)

        LogTypes.registerAll(context)
        FilesystemTypes.registerAll(context)
        TimeTypes.registerAll(context)
        EnvironmentTypes.registerAll(context)
        ContinuationTypes.registerAll(context)
        LoomTypes.registerAll(context)
        OSThreadTypes.registerAll(context)
        CoopTypes.registerAll(context)
        ParallelTypes.registerAll(context)
        STMTypes.registerAll(context)

        // Concurrent primitives (Atom, Agent, Channel, Mutex, Semaphore, Latch, WaitGroup)
        AtomTypes.registerAll(context)
        AgentTypes.registerAll(context)
        ChannelTypes.registerAll(context)
        MutexTypes.registerAll(context)
        SemaphoreTypes.registerAll(context)
        LatchTypes.registerAll(context)
        WaitGroupTypes.registerAll(context)
        CancellationTokenTypes.registerAll(context)

        // Codec types (JSON serialization/deserialization)
        JsonTypes.registerAll(context)

        // HTTP effect types
        HttpTypes.registerAll(context)

        // crash is a built-in function, no import needed
        // Terminates the program with an error message (like Rust's panic!)
        context["crash"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            MezzeType.VarType(TypeVar.fresh())  // Returns 'a (never returns, so can unify with any type)
        )
    }
}