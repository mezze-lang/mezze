package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for the Generator effect.
 *
 * The Generator effect provides yield/resume pattern for creating lazy sequences.
 * Built on top of the Continuation effect.
 */
object GeneratorTypes {

    /**
     * Helper function to create Option type
     */
    private fun optionType(elementType: FunkType): FunkType {
        return FunkType.TypeApplication("Option", listOf(elementType))
    }

    /**
     * Helper function to create List type
     */
    private fun listType(elementType: FunkType): FunkType {
        return FunkType.TypeApplication("List", listOf(elementType))
    }

    /**
     * Register all generator effect native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_generator_yield: { value: 'a } -> {}
        // Yields a value and suspends execution
        context["__native_generator_yield"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "value" to FunkType.VarType(TypeVar("a"))
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_generator_next: { gen: 'g } -> { value: Option 'a, gen: 'g }
        // Gets the next value from a generator
        context["__native_generator_next"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "gen" to FunkType.VarType(TypeVar("g"))
            )),
            FunkType.RecordType(mapOf(
                "value" to optionType(FunkType.VarType(TypeVar("a"))),
                "gen" to FunkType.VarType(TypeVar("g2"))
            ))
        )

        // __native_generator_to_list: { gen: 'g } -> List 'a
        // Consumes entire generator into a list
        context["__native_generator_to_list"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "gen" to FunkType.VarType(TypeVar("g"))
            )),
            listType(FunkType.VarType(TypeVar("a")))
        )

        // __native_generator_take: { gen: 'g, n: Int } -> { values: List 'a, gen: 'g }
        // Takes first n values from a generator
        context["__native_generator_take"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "gen" to FunkType.VarType(TypeVar("g")),
                "n" to FunkType.IntType
            )),
            FunkType.RecordType(mapOf(
                "values" to listType(FunkType.VarType(TypeVar("a"))),
                "gen" to FunkType.VarType(TypeVar("g2"))
            ))
        )
    }
}
