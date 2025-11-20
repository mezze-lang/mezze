package com.funk.language.typechecker

import com.funk.language.ast.FunkType
import com.funk.language.typechecker.types.BasicTypes
import com.funk.language.typechecker.types.collections.ArrayListTypes
import com.funk.language.typechecker.types.collections.ListTypes
import com.funk.language.typechecker.types.collections.MapTypes
import com.funk.language.typechecker.types.collections.SetTypes
import com.funk.language.typechecker.types.collections.NonEmptyListTypes
import com.funk.language.typechecker.types.math.MathCoreTypes
import com.funk.language.typechecker.types.math.MathTrigTypes
import com.funk.language.typechecker.types.math.MathRandomTypes
import com.funk.language.typechecker.types.effects.LogTypes
import com.funk.language.typechecker.types.effects.FilesystemTypes
import com.funk.language.typechecker.types.effects.TimeTypes
import com.funk.language.typechecker.types.effects.EnvironmentTypes

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
 * Type signatures for native functions in the Funk language typechecker
 */
object NativeFunctionTypes {
    
    /**
     * Register all native function types in the given context
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // Register basic types (int, float, nat, bool, string, frac)
        BasicTypes.registerAll(context)

        // Register collection types (array, list, map, set, nonemptylist)
        ArrayListTypes.registerAll(context)
        ListTypes.registerAll(context)
        MapTypes.registerAll(context)
        SetTypes.registerAll(context)
        NonEmptyListTypes.registerAll(context)

        // Register math types (core, trig, random)
        MathCoreTypes.registerAll(context)
        MathTrigTypes.registerAll(context)
        MathRandomTypes.registerAll(context)

        // Register effects types (log, filesystem, time, environment)
        LogTypes.registerAll(context)
        FilesystemTypes.registerAll(context)
        TimeTypes.registerAll(context)
        EnvironmentTypes.registerAll(context)

        // Basic I/O functions
        context["native_print"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            FunkType.RecordType(emptyMap()) // Unit type
        )
        
        // Type conversion functions
        context["intToString"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.StringType
        )
        
        context["floatToString"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("val" to FunkType.FloatType)),
            FunkType.StringType
        )
        
        context["boolToString"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("val" to FunkType.BooleanType)),
            FunkType.StringType
        )
        
        // Legacy array functions (deprecated - use array module instead)
        // These are kept for backward compatibility with existing tests
        
        // arrayLength : {arr: [Int]} -> Int (simplified for now)
        context["arrayLength"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("arr" to FunkType.ArrayType(FunkType.IntType))),
            FunkType.IntType
        )
        
        // arrayGet : {arr: [Int], index: Int} -> Int (simplified for now)
        context["arrayGet"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "arr" to FunkType.ArrayType(FunkType.IntType),
                "index" to FunkType.IntType
            )),
            FunkType.IntType
        )
        
        // arraySet : {arr: [Int], index: Int, value: Int} -> [Int] (simplified for now)
        context["arraySet"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "arr" to FunkType.ArrayType(FunkType.IntType),
                "index" to FunkType.IntType,
                "value" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.IntType)
        )
        
        // arrayAdd : {arr: [Int], value: Int} -> [Int] (simplified for now)
        context["arrayAdd"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "arr" to FunkType.ArrayType(FunkType.IntType),
                "value" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.IntType)
        )
        
        // arrayRemove : {arr: [Int], index: Int} -> [Int] (simplified for now)
        context["arrayRemove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "arr" to FunkType.ArrayType(FunkType.IntType),
                "index" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.IntType)
        )
        
        // arrayConcat : {arr1: [Int], arr2: [Int]} -> [Int] (simplified for now)
        context["arrayConcat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "arr1" to FunkType.ArrayType(FunkType.IntType),
                "arr2" to FunkType.ArrayType(FunkType.IntType)
            )),
            FunkType.ArrayType(FunkType.IntType)
        )
        
        // arrayIsEmpty : {arr: [Int]} -> Bool (simplified for now)
        context["arrayIsEmpty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("arr" to FunkType.ArrayType(FunkType.IntType))),
            FunkType.BooleanType
        )
        
        // String concatenation operator
        context["++"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.StringType,
                "right" to FunkType.StringType
            )),
            FunkType.StringType
        )
        
        context["(++)"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.StringType,
                "right" to FunkType.StringType
            )),
            FunkType.StringType
        )
        
        // === INLINED OPERATORS FROM STDLIB ===
        
        // Arithmetic operators for Int
        context["+"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.IntType
        )
        context["-"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.IntType
        )
        context["*"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.IntType
        )
        context["/"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.IntType
        )
        context["%"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.IntType
        )
        
        // Arithmetic operators for Float
        context["+"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["-"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["*"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["/"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["%"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.FloatType
        )
        
        // Comparison operators for Int
        context["=="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.BooleanType
        )
        context["!="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.BooleanType
        )
        context["<"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.BooleanType
        )
        context[">"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.BooleanType
        )
        context["<="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.BooleanType
        )
        context[">="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.IntType, "right" to FunkType.IntType)),
            FunkType.BooleanType
        )
        
        // Comparison operators for Float
        context["=="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context["!="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context["<"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context[">"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context["<="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context[">="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.FloatType, "right" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        
        // Comparison operators for String
        context["=="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.StringType, "right" to FunkType.StringType)),
            FunkType.BooleanType
        )
        context["!="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.StringType, "right" to FunkType.StringType)),
            FunkType.BooleanType
        )
        
        // Comparison operators for Boolean
        context["=="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.BooleanType, "right" to FunkType.BooleanType)),
            FunkType.BooleanType
        )
        context["!="] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.BooleanType, "right" to FunkType.BooleanType)),
            FunkType.BooleanType
        )
        
        // === STDLIB FUNCTIONS ===
        
        // Core functions from abilities.funk
        context["not"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BooleanType)),
            FunkType.BooleanType
        )
        
        context["concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.StringType, "right" to FunkType.StringType)),
            FunkType.StringType
        )
        
        // Basic types (int, float, nat, bool, string, frac) registered via BasicTypes.registerAll() above

        // System functions
        context["crash"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            FunkType.VariantType(emptyMap()) // Never returns - empty variant type
        )
        
        context["__native_crash"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            FunkType.VariantType(emptyMap()) // Never returns - empty variant type
        )

        // ===== TIME EFFECT NATIVES =====
        // Time effect types (now, sleep, date/time components, format, parse, timestamp helpers)
        // Now registered via TimeTypes.registerAll() above

        // ===== ENVIRONMENT EFFECT NATIVES =====
        // Environment effect types (get/set env vars, directory ops, system info, process info)
        // Now registered via EnvironmentTypes.registerAll() above

        // ===== MATH CORE NATIVES =====
        // Math core types (pow, sqrt, ln, log10, exp, floor, ceil, round, etc.)
        // Now registered via MathCoreTypes.registerAll() above

        // ===== MATH TRIG NATIVES =====
        // Trigonometric types (sin, cos, tan, asin, acos, atan, atan2)
        // Now registered via MathTrigTypes.registerAll() above

        // ===== RANDOM EFFECT NATIVES =====
        // Random number generation types (seeded_random_float, seeded_random_int, seeded_random_nat, update_seed)
        // Now registered via MathRandomTypes.registerAll() above

        // ===== LIST NATIVES =====
        // List types registered via ListTypes.registerAll() above

        // ===== ARRAYLIST NATIVES =====
        // ArrayList types registered via ArrayListTypes.registerAll() above

        // ============================================================================
        // MAP COLLECTION OPERATIONS
        // ============================================================================
        // Map types registered via MapTypes.registerAll() above

        // ============================================================================
        // SET COLLECTION OPERATIONS
        // ============================================================================
        // Set types registered via SetTypes.registerAll() above

        // ============================================================================
        // EFFECTS: CONSOLE AND LOGGING
        // ============================================================================
        // Console and logging effect types (print, println, info, warn, error, debug, trace)
        // Now registered via LogTypes.registerAll() above

        // ============================================================================
        // EFFECTS: FILESYSTEM (LocalDisk and MemoryFS)
        // ============================================================================
        // Filesystem effect types (read, write, delete, list, create directories, file size, etc.)
        // Includes both LocalDisk (__native_fs_*) and MemoryFS (__native_memfs_*) operations
        // Now registered via FilesystemTypes.registerAll() above
    }
}