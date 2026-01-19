package com.funk.language.interpreter.stdlib

import com.funk.language.interpreter.*
import com.funk.language.interpreter.stdlib.basic.IntImplementations
import com.funk.language.interpreter.stdlib.basic.FloatImplementations
import com.funk.language.interpreter.stdlib.basic.NatImplementations
import com.funk.language.interpreter.stdlib.basic.BoolImplementations
import com.funk.language.interpreter.stdlib.basic.StringImplementations
import com.funk.language.interpreter.stdlib.basic.FracImplementations
import com.funk.language.interpreter.stdlib.math.MathCoreImplementations
import com.funk.language.interpreter.stdlib.math.MathTrigImplementations
import com.funk.language.interpreter.stdlib.math.MathRandomImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleNonEmptyListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleArrayListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleMapImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleSetImplementations
import com.funk.language.interpreter.stdlib.effects.LogImplementations
import com.funk.language.interpreter.stdlib.effects.FilesystemImplementations
import com.funk.language.interpreter.stdlib.effects.TimeImplementations
import com.funk.language.interpreter.stdlib.effects.EnvironmentImplementations
import com.funk.language.interpreter.stdlib.effects.ContinuationImplementations
import com.funk.language.interpreter.stdlib.effects.LoomImplementations
import com.funk.language.interpreter.stdlib.conversion.TruffleConversionImplementations
import com.funk.language.interpreter.stdlib.io.TruffleIOImplementations
import com.funk.language.interpreter.stdlib.system.TruffleSystemImplementations
import com.funk.language.interpreter.stdlib.logic.TruffleLogicImplementations


/**
 * Minimal native implementations for the Funk standard library.
 * 
 * This provides only essential native functions needed for basic operation.
 * More comprehensive implementations will be added once the build is stable.
 */
object TruffleNativeImplementations {
    
    /**
     * Validate that all native functions are implemented (simplified version)
     */
    private fun validateImplementations(context: Map<String, FunkValue>) {
        // Validation logic removed since NativeFunctionRegistry no longer exists
        // Native functions are now managed through the .funk module files
    }
    
    /**
     * Register minimal native implementations in the interpreter context
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Register basic type implementations (int, float, nat, bool, string, frac)
        IntImplementations.registerAll(context)
        FloatImplementations.registerAll(context)
        NatImplementations.registerAll(context)
        BoolImplementations.registerAll(context)
        StringImplementations.registerAll(context)
        FracImplementations.registerAll(context)

        // Register math implementations (core, trig, random)
        MathCoreImplementations.registerAll(context)
        MathTrigImplementations.registerAll(context)
        MathRandomImplementations.registerAll(context)

        // Register collection implementations (List, NonEmptyList, ArrayList, Map, Set)
        TruffleListImplementations.registerAll(context)
        TruffleNonEmptyListImplementations.registerAll(context)
        TruffleArrayListImplementations.registerAll(context)
        TruffleMapImplementations.registerAll(context)
        TruffleSetImplementations.registerAll(context)

        // Register effects implementations (log, filesystem, time, environment, continuation, loom)
        LogImplementations.registerAll(context)
        FilesystemImplementations.registerAll(context)
        TimeImplementations.registerAll(context)
        EnvironmentImplementations.registerAll(context)
        ContinuationImplementations.registerAll(context)
        LoomImplementations.registerAll(context)  // Project Loom virtual threads

        // Register logic implementations (boolean operations, comparisons)
        TruffleLogicImplementations.registerAll(context)

        // Register legacy implementations (for backward compatibility)
        TruffleConversionImplementations.registerAll(context)
        TruffleIOImplementations.registerAll(context)
        TruffleSystemImplementations.registerAll(context)

        // Register minimal crash function for testing
        context["__native_crash"] = NativeFunkFunction("__native_crash") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_crash", "expects FunkRecord")
            val msg = record.fields["msg"] as? FunkString ?: throw NativeFunctionException("__native_crash", "expects 'msg' as String")
            System.err.println("Program crashed: ${msg.value}")
            System.exit(1)
            // This line will never be reached, but needed for type checking
            throw FunkException("Program crashed: ${msg.value}")
        }

        // NOTE: All native function implementations have been migrated to specialized files:
        // - Phase 1: Hot path arithmetic (IntImplementations.kt with overflow checking)
        // - Phase 2: String operations (StringImplementations.kt)
        // - Phase 3: Collection operations (List, ArrayList, Map, Set, NonEmptyList)
        // - Phase 4: Math operations (MathCore, MathTrig, MathRandom)
        // - Phase 5: Type conversions and basic types (Conversion, Bool, Float, Frac, Nat)
        // - Phase 6: I/O and System operations (Filesystem, Time, Environment, System)
        // - Phase 7: Logic operations (TruffleLogicImplementations)
        // - Phase 8: Effect system (Continuation, Loom, Log)
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