package com.funk.language.typechecker

import com.funk.language.ast.FunkType

/**
 * Type signatures for native functions in the Funk language typechecker
 */
object NativeFunctionTypes {
    
    /**
     * Register all native function types in the given context
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // Basic I/O functions
        context["native_print"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            FunkType.RecordType(emptyMap()) // Unit type
        )
        
        // Type conversion functions
        context["intToString"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("val" to FunkType.IntType)),
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
            FunkType.RecordType(mapOf("val" to FunkType.BooleanType)),
            FunkType.BooleanType
        )
        
        context["concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to FunkType.StringType, "right" to FunkType.StringType)),
            FunkType.StringType
        )
        
        // Native implementations for primitive type modules
        context["__native_int_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.StringType
        )

        context["__native_float_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.StringType
        )
        
        context["__native_string_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("value" to FunkType.StringType)),
            FunkType.StringType
        )
        
        context["__native_boolean_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("value" to FunkType.BooleanType)),
            FunkType.StringType
        )

        // Native string operations
        context["__native_string_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.StringType, "r" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.IntType
        )

        context["__native_string_to_upper"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_to_lower"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_trim"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_starts_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "prefix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["__native_string_ends_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "suffix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["__native_string_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "substring" to FunkType.StringType)),
            FunkType.BooleanType
        )

        // Native arithmetic operations for int module
        context["__native_int_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_modulo"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.StringType
        )

        context["__native_int_equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.BooleanType
        )

        context["__native_int_less_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.BooleanType
        )

        context["__native_int_greater_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.BooleanType
        )

        // Native float operations
        context["__native_float_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.FloatType, "exp" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FloatType
        )

        context["__native_float_from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.FloatType
        )

        context["__native_float_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.OptionType(FunkType.FloatType)
        )

        // Module-qualified arithmetic functions for int
        context["int::add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::modulo"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.StringType
        )
        
        // Module-qualified functions for float
        context["float::abs"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::min"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::max"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.FloatType, "exp" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FloatType
        )

        context["float::from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.FloatType
        )

        context["float::from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.FloatType
        )

        // Module-qualified functions for boolean
        context["boolean::and"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::or"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::not"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::xor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::implies"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::iff"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        // Aliases for std::bool module (module name is bool, not boolean)
        context["bool::and"] = context["boolean::and"]!!
        context["bool::or"] = context["boolean::or"]!!
        context["bool::not"] = context["boolean::not"]!!
        context["bool::xor"] = context["boolean::xor"]!!
        context["bool::implies"] = context["boolean::implies"]!!
        context["bool::iff"] = context["boolean::iff"]!!

        // Module-qualified functions for string
        context["string::concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.StringType, "r" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.IntType
        )

        context["string::is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::to_upper"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::to_lower"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::trim"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::starts_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "prefix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::ends_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "suffix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "substring" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::repeat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "n" to FunkType.IntType)),
            FunkType.StringType
        )

        // Native nat operations
        context["__native_nat_subtract_to_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.IntType
        )

        context["__native_nat_from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.OptionType(FunkType.NatType)
        )

        context["__native_nat_from_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.OptionType(FunkType.NatType)
        )

        context["__native_nat_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.OptionType(FunkType.NatType)
        )

        // Module-qualified functions for nat
        context["nat::subtract_to_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.IntType
        )

        context["nat::from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.OptionType(FunkType.NatType)
        )

        context["nat::from_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.OptionType(FunkType.NatType)
        )

        context["nat::from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.OptionType(FunkType.NatType)
        )

        context["nat::min"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.NatType
        )

        context["nat::max"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.NatType
        )

        context["nat::is_zero"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.BooleanType
        )

        context["nat::is_even"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.BooleanType
        )

        context["nat::is_odd"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.BooleanType
        )

        // Native frac operations
        context["__native_frac_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["__native_frac_less_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["__native_frac_greater_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["__native_frac_to_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FloatType
        )

        context["__native_frac_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.StringType
        )

        context["__native_make_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "numerator" to FunkType.IntType,
                "denominator" to FunkType.IntType
            )),
            FunkType.FracType
        )

        context["__native_int_to_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("int" to FunkType.IntType)),
            FunkType.FracType
        )

        context["__native_nat_to_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("nat" to FunkType.NatType)),
            FunkType.FracType
        )

        context["__native_frac_numerator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.IntType
        )

        context["__native_frac_denominator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.IntType
        )

        context["__native_reduce_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FracType
        )

        context["__native_abs_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FracType
        )

        context["__native_negate_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FracType
        )

        // Module-qualified functions for frac
        context["frac::add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["frac::less_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["frac::greater_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FracType,
                "right" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["frac::to_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FloatType
        )

        context["frac::to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.StringType
        )

        context["frac::make"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "numerator" to FunkType.IntType,
                "denominator" to FunkType.IntType
            )),
            FunkType.FracType
        )

        context["frac::from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("int" to FunkType.IntType)),
            FunkType.FracType
        )

        context["frac::from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("nat" to FunkType.NatType)),
            FunkType.FracType
        )

        context["frac::numerator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.IntType
        )

        context["frac::denominator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.IntType
        )

        context["frac::reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FracType
        )

        context["frac::abs"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FracType
        )

        context["frac::negate"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("frac" to FunkType.FracType)),
            FunkType.FracType
        )

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

        context["__native_time_now"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_now_nanos"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_sleep"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("millis" to FunkType.IntType)),
            FunkType.RecordType(emptyMap())
        )

        context["__native_time_sleep_nanos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("nanos" to FunkType.IntType)),
            FunkType.RecordType(emptyMap())
        )

        context["__native_time_current_year"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_month"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_day"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_hour"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_minute"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_current_second"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_time_format"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "timestamp" to FunkType.IntType,
                "format" to FunkType.StringType
            )),
            FunkType.StringType
        )

        context["__native_time_parse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "date_string" to FunkType.StringType,
                "format" to FunkType.StringType
            )),
            FunkType.IntType
        )

        // Mock time helper functions
        context["__native_time_year_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_month_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_day_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_hour_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_minute_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_time_second_from_timestamp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("timestamp" to FunkType.IntType)),
            FunkType.IntType
        )

        // ===== ENVIRONMENT EFFECT NATIVES =====

        context["__native_env_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("name" to FunkType.StringType)),
            FunkType.OptionType(FunkType.StringType)
        )

        context["__native_env_set"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "name" to FunkType.StringType,
                "value" to FunkType.StringType
            )),
            FunkType.RecordType(emptyMap())
        )

        context["__native_env_get_all"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.ArrayType(FunkType.RecordType(mapOf("key" to FunkType.StringType, "value" to FunkType.StringType)))
        )

        context["__native_env_current_dir"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_change_dir"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            FunkType.RecordType(emptyMap())
        )

        context["__native_env_os_name"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_os_version"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_arch"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_process_id"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_env_process_args"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.ArrayType(FunkType.StringType)
        )

        context["__native_env_exit"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("code" to FunkType.IntType)),
            FunkType.RecordType(emptyMap())
        )

        // ===== MATH CORE NATIVES =====

        context["__native_float_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.FloatType,
                "exp" to FunkType.FloatType
            )),
            FunkType.FloatType
        )

        context["__native_float_sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_ln"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_log10"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_exp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_is_nan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        context["__native_float_is_infinite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        context["__native_float_is_finite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        context["__native_float_sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_int_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.IntType,
                "exp" to FunkType.IntType
            )),
            FunkType.IntType
        )

        context["__native_int_sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_nat_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.NatType,
                "exp" to FunkType.NatType
            )),
            FunkType.NatType
        )

        // ===== MATH TRIG NATIVES =====

        context["__native_float_sin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_cos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_tan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_asin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_acos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_atan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_atan2"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "y" to FunkType.FloatType,
                "x" to FunkType.FloatType
            )),
            FunkType.FloatType
        )

        // ===== RANDOM EFFECT NATIVES =====

        context["__native_seeded_random_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("seed" to FunkType.IntType)),
            FunkType.FloatType
        )

        context["__native_seeded_random_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "seed" to FunkType.IntType,
                "min" to FunkType.IntType,
                "max" to FunkType.IntType
            )),
            FunkType.IntType
        )

        context["__native_seeded_random_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "seed" to FunkType.IntType,
                "max" to FunkType.NatType
            )),
            FunkType.NatType
        )

        context["__native_update_seed"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "old_seed" to FunkType.IntType,
                "new_seed" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )

        // ===== LIST NATIVES =====

        val listElemTypeVar = com.funk.language.ast.TypeVar("a")
        val listElemType = FunkType.VarType(listElemTypeVar)
        val listType = FunkType.ListType(listElemType)

        context["__native_list_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            listType
        )

        context["__native_list_cons"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "head" to listElemType,
                "tail" to listType
            )),
            listType
        )

        context["__native_list_is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            FunkType.BooleanType
        )

        context["__native_list_head"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            listElemType
        )

        context["__native_list_tail"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            listType
        )

        context["__native_list_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            FunkType.IntType
        )

        context["__native_list_append"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "ys" to listType
            )),
            listType
        )

        context["__native_list_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "index" to FunkType.IntType
            )),
            listElemType
        )

        context["__native_list_take"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "n" to FunkType.IntType
            )),
            listType
        )

        context["__native_list_drop"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "n" to FunkType.IntType
            )),
            listType
        )

        val mapFuncTypeVar = com.funk.language.ast.TypeVar("b")
        val mapFuncReturnType = FunkType.VarType(mapFuncTypeVar)
        val mapFuncType = FunkType.FunctionType(listElemType, mapFuncReturnType)
        val mappedListType = FunkType.ListType(mapFuncReturnType)

        context["__native_list_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "f" to mapFuncType
            )),
            mappedListType
        )

        val predicateType = FunkType.FunctionType(listElemType, FunkType.BooleanType)

        context["__native_list_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "p" to predicateType
            )),
            listType
        )

        val foldTypeVar = com.funk.language.ast.TypeVar("acc")
        val foldAccType = FunkType.VarType(foldTypeVar)
        val foldFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "acc" to foldAccType,
                "elem" to listElemType
            )),
            foldAccType
        )

        context["__native_list_fold_left"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "init" to foldAccType,
                "f" to foldFuncType
            )),
            foldAccType
        )

        context["__native_list_fold_right"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "init" to foldAccType,
                "f" to foldFuncType
            )),
            foldAccType
        )

        val reduceFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "acc" to listElemType,
                "elem" to listElemType
            )),
            listElemType
        )

        context["__native_list_reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "f" to reduceFuncType
            )),
            listElemType
        )

        context["__native_list_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "p" to predicateType
            )),
            FunkType.BooleanType
        )

        context["__native_list_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "p" to predicateType
            )),
            FunkType.BooleanType
        )

        context["__native_list_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "x" to listElemType
            )),
            FunkType.BooleanType
        )

        context["__native_list_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            listType
        )

        context["__native_list_zip"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "ys" to listType
            )),
            FunkType.ListType(FunkType.RecordType(mapOf(
                "fst" to listElemType,
                "snd" to listElemType
            )))
        )

        context["__native_list_range"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "start" to FunkType.IntType,
                "end" to FunkType.IntType
            )),
            FunkType.ListType(FunkType.IntType)
        )

        context["__native_list_repeat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "x" to listElemType,
                "n" to FunkType.IntType
            )),
            listType
        )

        // Conversion functions
        context["__native_list_to_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            FunkType.ArrayType(listElemType)
        )

        context["__native_array_to_list"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("arr" to FunkType.ArrayType(listElemType))),
            listType
        )

        context["__native_list_from_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to FunkType.ArrayType(listElemType))),
            listType
        )

        context["__native_list_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xss" to FunkType.ListType(listType))),
            listType
        )

        context["__native_list_unzip"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to FunkType.ListType(FunkType.RecordType(mapOf(
                "fst" to listElemType,
                "snd" to listElemType
            ))))),
            FunkType.RecordType(mapOf(
                "fst" to listType,
                "snd" to listType
            ))
        )

        context["__native_list_intersperse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "sep" to listElemType
            )),
            listType
        )

        context["__native_list_sort"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            listType
        )

        val cmpFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to listElemType,
                "b" to listElemType
            )),
            FunkType.IntType
        )

        context["__native_list_sort_by"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "cmp" to cmpFuncType
            )),
            listType
        )

        // ===== ARRAYLIST NATIVES =====

        val arrayElemTypeVar = com.funk.language.ast.TypeVar("a")
        val arrayElemType = FunkType.VarType(arrayElemTypeVar)
        val arrayType = FunkType.ArrayType(arrayElemType)

        context["__native_arraylist_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            FunkType.IntType
        )

        context["__native_arraylist_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "i" to FunkType.IntType
            )),
            arrayElemType
        )

        context["__native_arraylist_set"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "i" to FunkType.IntType,
                "x" to arrayElemType
            )),
            arrayType
        )

        context["__native_arraylist_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "x" to arrayElemType
            )),
            arrayType
        )

        context["__native_arraylist_remove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "i" to FunkType.IntType
            )),
            arrayType
        )

        context["__native_arraylist_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "ys" to arrayType
            )),
            arrayType
        )

        context["__native_arraylist_is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            FunkType.BooleanType
        )

        val arrayMapFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to arrayElemType)),
            arrayElemType
        )

        context["__native_arraylist_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "f" to arrayMapFuncType
            )),
            arrayType
        )

        val arrayPredicateType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to arrayElemType)),
            FunkType.BooleanType
        )

        context["__native_arraylist_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "p" to arrayPredicateType
            )),
            arrayType
        )

        val arrayFoldFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "acc" to arrayElemType,
                "x" to arrayElemType
            )),
            arrayElemType
        )

        context["__native_arraylist_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "init" to arrayElemType,
                "f" to arrayFoldFuncType
            )),
            arrayElemType
        )

        val arrayReduceFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to arrayElemType,
                "b" to arrayElemType
            )),
            arrayElemType
        )

        context["__native_arraylist_reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "f" to arrayReduceFuncType
            )),
            arrayElemType
        )

        context["__native_arraylist_find"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "p" to arrayPredicateType
            )),
            FunkType.OptionType(arrayElemType)
        )

        context["__native_arraylist_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "x" to arrayElemType
            )),
            FunkType.BooleanType
        )

        context["__native_arraylist_index_of"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "x" to arrayElemType
            )),
            FunkType.OptionType(FunkType.IntType)
        )

        context["__native_arraylist_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            arrayType
        )

        context["__native_arraylist_sort"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            arrayType
        )

        val arrayCmpFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to arrayElemType,
                "b" to arrayElemType
            )),
            FunkType.IntType
        )

        context["__native_arraylist_sort_by"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "cmp" to arrayCmpFuncType
            )),
            arrayType
        )

        context["__native_arraylist_slice"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "start" to FunkType.IntType,
                "end" to FunkType.IntType
            )),
            arrayType
        )

        // ============================================================================
        // MAP COLLECTION OPERATIONS
        // ============================================================================

        val mapKeyTypeVar = com.funk.language.ast.TypeVar("k")
        val mapValTypeVar = com.funk.language.ast.TypeVar("v")
        val mapValTypeVarW = com.funk.language.ast.TypeVar("w")
        val mapAccTypeVar = com.funk.language.ast.TypeVar("acc")

        val mapType = FunkType.MapType(FunkType.VarType(mapKeyTypeVar), FunkType.VarType(mapValTypeVar))
        val mapTypeKW = FunkType.MapType(FunkType.VarType(mapKeyTypeVar), FunkType.VarType(mapValTypeVarW))

        context["__native_map_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            mapType
        )

        context["__native_map_insert"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar),
                "v" to FunkType.VarType(mapValTypeVar)
            )),
            mapType
        )

        context["__native_map_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar)
            )),
            FunkType.OptionType(FunkType.VarType(mapValTypeVar))
        )

        context["__native_map_remove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar)
            )),
            mapType
        )

        context["__native_map_contains_key"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar)
            )),
            FunkType.BooleanType
        )

        context["__native_map_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.IntType
        )

        context["__native_map_keys"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.ListType(FunkType.VarType(mapKeyTypeVar))
        )

        context["__native_map_values"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.ListType(FunkType.VarType(mapValTypeVar))
        )

        context["__native_map_entries"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.ListType(FunkType.RecordType(mapOf(
                "key" to FunkType.VarType(mapKeyTypeVar),
                "value" to FunkType.VarType(mapValTypeVar)
            )))
        )

        context["__native_map_map_values"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "f" to FunkType.FunctionType(
                    FunkType.VarType(mapValTypeVar),
                    FunkType.VarType(mapValTypeVarW)
                )
            )),
            mapTypeKW
        )

        context["__native_map_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "p" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "key" to FunkType.VarType(mapKeyTypeVar),
                        "value" to FunkType.VarType(mapValTypeVar)
                    )),
                    FunkType.BooleanType
                )
            )),
            mapType
        )

        context["__native_map_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "init" to FunkType.VarType(mapAccTypeVar),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "acc" to FunkType.VarType(mapAccTypeVar),
                        "entry" to FunkType.RecordType(mapOf(
                            "key" to FunkType.VarType(mapKeyTypeVar),
                            "value" to FunkType.VarType(mapValTypeVar)
                        ))
                    )),
                    FunkType.VarType(mapAccTypeVar)
                )
            )),
            FunkType.VarType(mapAccTypeVar)
        )

        context["__native_map_from_keys"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ks" to FunkType.ListType(FunkType.VarType(mapKeyTypeVar)),
                "f" to FunkType.FunctionType(
                    FunkType.VarType(mapKeyTypeVar),
                    FunkType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_merge"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType
            )),
            mapType
        )

        context["__native_map_merge_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType,
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "v1" to FunkType.VarType(mapValTypeVar),
                        "v2" to FunkType.VarType(mapValTypeVar)
                    )),
                    FunkType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_remove_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "ks" to FunkType.ListType(FunkType.VarType(mapKeyTypeVar))
            )),
            mapType
        )

        // ============================================================================
        // SET COLLECTION OPERATIONS
        // ============================================================================

        val setElemTypeVar = com.funk.language.ast.TypeVar("a")
        val setElemTypeVarB = com.funk.language.ast.TypeVar("b")
        val setAccTypeVar = com.funk.language.ast.TypeVar("acc")

        val setType = FunkType.SetType(FunkType.VarType(setElemTypeVar))
        val setTypeB = FunkType.SetType(FunkType.VarType(setElemTypeVarB))

        context["__native_set_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            setType
        )

        context["__native_set_insert"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "x" to FunkType.VarType(setElemTypeVar)
            )),
            setType
        )

        context["__native_set_remove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "x" to FunkType.VarType(setElemTypeVar)
            )),
            setType
        )

        context["__native_set_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "x" to FunkType.VarType(setElemTypeVar)
            )),
            FunkType.BooleanType
        )

        context["__native_set_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to setType)),
            FunkType.IntType
        )

        context["__native_set_union"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_intersection"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_difference"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_is_subset"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            FunkType.BooleanType
        )

        context["__native_set_to_list"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to setType)),
            FunkType.ListType(FunkType.VarType(setElemTypeVar))
        )

        context["__native_set_from_list"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to FunkType.ListType(FunkType.VarType(setElemTypeVar)))),
            setType
        )

        context["__native_set_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "p" to FunkType.FunctionType(
                    FunkType.VarType(setElemTypeVar),
                    FunkType.BooleanType
                )
            )),
            setType
        )

        context["__native_set_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "f" to FunkType.FunctionType(
                    FunkType.VarType(setElemTypeVar),
                    FunkType.VarType(setElemTypeVarB)
                )
            )),
            setTypeB
        )

        context["__native_set_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "init" to FunkType.VarType(mapAccTypeVar),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "acc" to FunkType.VarType(mapAccTypeVar),
                        "elem" to FunkType.VarType(setElemTypeVar)
                    )),
                    FunkType.VarType(mapAccTypeVar)
                )
            )),
            FunkType.VarType(mapAccTypeVar)
        )

        context["__native_set_insert_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "xs" to FunkType.ListType(FunkType.VarType(setElemTypeVar))
            )),
            setType
        )

        context["__native_set_remove_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "xs" to FunkType.ListType(FunkType.VarType(setElemTypeVar))
            )),
            setType
        )
    }
}