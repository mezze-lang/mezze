package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.stdlib.basic.IntImplementations
import com.mezze.language.interpreter.stdlib.basic.BoolImplementations
import com.mezze.language.interpreter.stdlib.basic.FloatImplementations
import com.mezze.language.interpreter.stdlib.basic.NatImplementations
import com.mezze.language.interpreter.stdlib.basic.StringImplementations
import com.mezze.language.interpreter.stdlib.basic.FracImplementations
import com.mezze.language.interpreter.stdlib.math.MathCoreImplementations
import com.mezze.language.interpreter.stdlib.math.MathTrigImplementations
import com.mezze.language.interpreter.stdlib.math.MathRandomImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleListImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleNonEmptyListImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleArrayListImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleMapImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleSetImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleDequeImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleBytesImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleByteBufferImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleSortedMapImplementations
import com.mezze.language.interpreter.stdlib.collections.TruffleSortedSetImplementations
import com.mezze.language.interpreter.stdlib.effects.LogImplementations
import com.mezze.language.interpreter.stdlib.effects.InspectImplementations
import com.mezze.language.interpreter.stdlib.effects.FilesystemImplementations
import com.mezze.language.interpreter.stdlib.effects.TimeImplementations
import com.mezze.language.interpreter.stdlib.effects.EnvironmentImplementations
import com.mezze.language.interpreter.stdlib.effects.ContinuationImplementations
import com.mezze.language.interpreter.stdlib.effects.LoomImplementations
import com.mezze.language.interpreter.stdlib.effects.OSThreadImplementations
import com.mezze.language.interpreter.stdlib.effects.ForkJoinParallelImplementations
import com.mezze.language.interpreter.stdlib.effects.CoopImplementations
import com.mezze.language.interpreter.stdlib.effects.ParallelImplementations
import com.mezze.language.interpreter.stdlib.concurrent.AtomImplementations
import com.mezze.language.interpreter.stdlib.concurrent.AgentImplementations
import com.mezze.language.interpreter.stdlib.concurrent.ChannelImplementations
import com.mezze.language.interpreter.stdlib.concurrent.MutexImplementations
import com.mezze.language.interpreter.stdlib.concurrent.SemaphoreImplementations
import com.mezze.language.interpreter.stdlib.concurrent.LatchImplementations
import com.mezze.language.interpreter.stdlib.concurrent.WaitGroupImplementations
import com.mezze.language.interpreter.stdlib.concurrent.STMImplementations
import com.mezze.language.interpreter.stdlib.concurrent.CancellationTokenImplementations
import com.mezze.language.interpreter.stdlib.conversion.TruffleConversionImplementations
import com.mezze.language.interpreter.stdlib.io.TruffleIOImplementations
import com.mezze.language.interpreter.stdlib.system.TruffleSystemImplementations
import com.mezze.language.interpreter.stdlib.codec.JsonImplementations
import com.mezze.language.interpreter.stdlib.effects.HttpImplementations


/**
 * Minimal native implementations for the Mezze standard library.
 * 
 * This provides only essential native functions needed for basic operation.
 * More comprehensive implementations will be added once the build is stable.
 */
object TruffleNativeImplementations {
    
    /**
     * Validate that all native functions are implemented (simplified version)
     */
    private fun validateImplementations(context: Map<String, MezzeValue>) {
        // Validation logic removed since NativeFunctionRegistry no longer exists
        // Native functions are now managed through the .mz module files
    }
    
    /**
     * Register minimal native implementations in the interpreter context
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Register basic type implementations (int, float, nat, bool, string, frac, char)
        IntImplementations.registerAll(context)
        BoolImplementations.registerAll(context)
        FloatImplementations.registerAll(context)
        NatImplementations.registerAll(context)
        StringImplementations.registerAll(context)
        StringImplementations.registerUtilities(context)
        FracImplementations.registerAll(context)
        TruffleCharImplementations.registerAll(context)

        // Register math implementations (core, trig, random)
        MathCoreImplementations.registerAll(context)
        MathTrigImplementations.registerAll(context)
        MathRandomImplementations.registerAll(context)

        // Register collection implementations (List, NonEmptyList, ArrayList, Map, Set, Deque, Bytes, ByteBuffer)
        TruffleListImplementations.registerAll(context)
        TruffleNonEmptyListImplementations.registerAll(context)
        TruffleArrayListImplementations.registerAll(context)
        TruffleMapImplementations.registerAll(context)
        TruffleSetImplementations.registerAll(context)
        TruffleDequeImplementations.registerAll(context)
        TruffleBytesImplementations.registerAll(context)
        TruffleByteBufferImplementations.registerAll(context)
        TruffleSortedMapImplementations.registerAll(context)
        TruffleSortedSetImplementations.registerAll(context)

        // Register effects implementations (log, inspect, filesystem, time, environment, continuation, loom)
        LogImplementations.registerAll(context)
        InspectImplementations.registerAll(context)
        FilesystemImplementations.registerAll(context)
        TimeImplementations.registerAll(context)
        EnvironmentImplementations.registerAll(context)
        ContinuationImplementations.registerAll(context, null)  // interpreter will be set up later
        LoomImplementations.registerAll(context)  // Project Loom virtual threads
        OSThreadImplementations.registerAll(context)  // OS platform threads
        CoopImplementations.registerAll(context)  // Cooperative async (single-threaded)
        ParallelImplementations.registerAll(context)  // Data parallelism (Virtual threads)
        ForkJoinParallelImplementations.registerAll(context)  // True ForkJoin work-stealing

        // Register concurrent primitives (Atom, Agent, Channel, Mutex, Semaphore, Latch, WaitGroup, STM)
        AtomImplementations.registerAll(context)
        AgentImplementations.registerAll(context)  // Clojure-style async atoms
        ChannelImplementations.registerAll(context)
        MutexImplementations.registerAll(context)
        SemaphoreImplementations.registerAll(context)
        LatchImplementations.registerAll(context)
        WaitGroupImplementations.registerAll(context)
        STMImplementations.registerAll(context)  // Software Transactional Memory
        CancellationTokenImplementations.registerAll(context)  // Cooperative cancellation

        // Register codec implementations (JSON serialization/deserialization)
        JsonImplementations.registerAll(context)

        // Register HTTP client implementations (Vert.x)
        HttpImplementations.registerAll(context)

        // Register legacy implementations (for backward compatibility)
        TruffleConversionImplementations.registerAll(context)
        TruffleIOImplementations.registerAll(context)
        TruffleSystemImplementations.registerAll(context)

        // Register crash function - built-in, no import needed
        // Terminates the program with an error message (like Rust's panic!)
        context["crash"] = NativeMezzeFunction("crash") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("crash", "expects MezzeRecord")
            val msg = record.fields["msg"] as? MezzeString ?: throw NativeFunctionException("crash", "expects 'msg' as String")
            throw MezzeCrashException("crash: ${msg.value}")
        }

        // NOTE: All native function implementations have been migrated to specialized files:
        // - Phase 1: Hot path arithmetic (IntImplementations.kt with overflow checking)
        // - Phase 2: String operations (StringImplementations.kt)
        // - Phase 3: Collection operations (List, ArrayList, Map, Set, NonEmptyList)
        // - Phase 4: Math operations (MathCore, MathTrig, MathRandom)
        // - Phase 5: Type conversions and basic types (Conversion, Bool, Float, Frac, Nat)
        // - Phase 6: I/O and System operations (Filesystem, Time, Environment, System)
        // - Phase 7: Effect system (Continuation, Loom, Log)
        //
        // All operators (+, -, *, /, %, ==, !=, <, >, <=, >=) are handled by dedicated
        // Truffle nodes (AddNode, SubtractNode, etc.) in nodes/arithmetic/ArithmeticNode.kt
        // which provide better optimization and overflow checking.
        //
        // Legacy inline operator implementations have been REMOVED as they were:
        // - Less optimized (no Truffle node specialization)
        // - Missing overflow checking for arithmetic
        // - Missing Nat and Frac support (only Int and Float)
        // - Completely unused (operators are desugared to ability calls, not direct context lookups)
        
        // Validate that all functions from the registry are implemented
        validateImplementations(context)
    }
}