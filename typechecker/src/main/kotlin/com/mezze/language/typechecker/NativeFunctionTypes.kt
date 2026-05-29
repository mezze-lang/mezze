package com.mezze.language.typechecker

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.BasicTypes
import com.mezze.language.typechecker.types.CharTypes
import com.mezze.language.typechecker.types.collections.SeqTypes
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
import com.mezze.language.typechecker.types.effects.StdinTypes
import com.mezze.language.typechecker.types.effects.LoomTypes
import com.mezze.language.typechecker.types.effects.OSThreadTypes
import com.mezze.language.typechecker.types.effects.SchedulerTypes
import com.mezze.language.typechecker.types.effects.ParallelTypes
import com.mezze.language.typechecker.types.effects.STMTypes
import com.mezze.language.typechecker.types.effects.HttpTypes
import com.mezze.language.typechecker.types.effects.MutationTypes
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
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.RESULT, listOf(valueType, errorType))
}

/**
 * Central registry for all native function type signatures.
 * Coordinates registration of types from domain-specific modules.
 */
object NativeFunctionTypes {

    // Set of all registered native function names for O(1) lookup
    private val nativeFunctionNames: MutableSet<String> = mutableSetOf()

    // Track whether registerAll has been called
    private var initialized = false

    /**
     * Check if a function name is a registered native function.
     * Uses O(1) HashSet lookup instead of string prefix checking.
     *
     * This is the structured way to detect native functions - no string prefix parsing!
     */
    fun isNativeFunction(name: String): Boolean {
        // Ensure we're initialized before checking
        if (!initialized) {
            // Create a temporary map just to populate the names
            val tempContext = mutableMapOf<String, MezzeType>()
            registerAll(tempContext)
        }
        return nativeFunctionNames.contains(name)
    }

    /**
     * Wrapper map that captures keys as they're added.
     * This allows us to track native function names without modifying all the type modules.
     */
    private class CapturingMap(
        private val delegate: MutableMap<String, MezzeType>,
        private val capturedKeys: MutableSet<String>
    ) : MutableMap<String, MezzeType> by delegate {
        override fun put(key: String, value: MezzeType): MezzeType? {
            capturedKeys.add(key)
            return delegate.put(key, value)
        }
    }

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // Wrap the context to capture all registered native function names
        val capturingContext = CapturingMap(context, nativeFunctionNames)

        BasicTypes.registerAll(capturingContext)
        CharTypes.registerAll(capturingContext)

        SeqTypes.registerAll(capturingContext)
        ListTypes.registerAll(capturingContext)
        MapTypes.registerAll(capturingContext)
        SetTypes.registerAll(capturingContext)
        NonEmptyListTypes.registerAll(capturingContext)
        DequeTypes.registerAll(capturingContext)
        BytesTypes.registerAll(capturingContext)
        ByteBufferTypes.registerAll(capturingContext)
        SortedMapTypes.registerAll(capturingContext)
        SortedSetTypes.registerAll(capturingContext)

        MathCoreTypes.registerAll(capturingContext)
        MathTrigTypes.registerAll(capturingContext)
        MathRandomTypes.registerAll(capturingContext)

        LogTypes.registerAll(capturingContext)
        FilesystemTypes.registerAll(capturingContext)
        TimeTypes.registerAll(capturingContext)
        EnvironmentTypes.registerAll(capturingContext)
        StdinTypes.registerAll(capturingContext)
        LoomTypes.registerAll(capturingContext)
        OSThreadTypes.registerAll(capturingContext)
        SchedulerTypes.registerAll(capturingContext)
        ParallelTypes.registerAll(capturingContext)
        STMTypes.registerAll(capturingContext)

        // Concurrent primitives (Atom, Agent, Channel, Mutex, Semaphore, Latch, WaitGroup)
        AtomTypes.registerAll(capturingContext)
        AgentTypes.registerAll(capturingContext)
        ChannelTypes.registerAll(capturingContext)
        MutexTypes.registerAll(capturingContext)
        SemaphoreTypes.registerAll(capturingContext)
        LatchTypes.registerAll(capturingContext)
        WaitGroupTypes.registerAll(capturingContext)
        CancellationTokenTypes.registerAll(capturingContext)

        // Codec types (JSON serialization/deserialization)
        JsonTypes.registerAll(capturingContext)

        // HTTP effect types
        HttpTypes.registerAll(capturingContext)

        // Mutation effect types (safe local mutation)
        MutationTypes.registerAll(capturingContext)

        // crash is a built-in function, no import needed
        // Terminates the program with an error message (like Rust's panic!)
        capturingContext["crash"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            MezzeType.VarType(TypeVar.fresh())  // Returns 'a (never returns, so can unify with any type)
        )

        initialized = true
    }
}