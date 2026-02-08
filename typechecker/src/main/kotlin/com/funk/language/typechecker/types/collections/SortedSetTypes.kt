package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for SortedSet operations.
 *
 * Corresponds to stdlib file:
 * - std/collections/sorted_set.funk
 *
 * SortedSet is backed by Vavr TreeSet for O(log n) operations with elements in sorted order.
 */
object SortedSetTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerSortedSetNatives(context)
    }

    private fun registerSortedSetNatives(context: MutableMap<String, FunkType>) {
        // __native_sorted_set_empty: {} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_empty"] = FunkType.FunctionType(
                FunkType.RecordType(emptyMap()),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_singleton: {x: 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_singleton"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elementType)),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_add: {s: SortedSet 'a, x: 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_add"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s" to FunkType.SortedSetType(elementType),
                    "x" to elementType
                )),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_remove: {s: SortedSet 'a, x: 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_remove"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s" to FunkType.SortedSetType(elementType),
                    "x" to elementType
                )),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_contains: {s: SortedSet 'a, x: 'a} -> Bool
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_contains"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s" to FunkType.SortedSetType(elementType),
                    "x" to elementType
                )),
                FunkType.BooleanType
            )
        }

        // __native_sorted_set_size: {s: SortedSet 'a} -> Int
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_size"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SortedSetType(elementType))),
                FunkType.IntType
            )
        }

        // __native_sorted_set_is_empty: {s: SortedSet 'a} -> Bool
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_is_empty"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SortedSetType(elementType))),
                FunkType.BooleanType
            )
        }

        // __native_sorted_set_union: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_union"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s1" to FunkType.SortedSetType(elementType),
                    "s2" to FunkType.SortedSetType(elementType)
                )),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_intersect: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_intersect"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s1" to FunkType.SortedSetType(elementType),
                    "s2" to FunkType.SortedSetType(elementType)
                )),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_difference: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_difference"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s1" to FunkType.SortedSetType(elementType),
                    "s2" to FunkType.SortedSetType(elementType)
                )),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_min: {s: SortedSet 'a} -> Option 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_min"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SortedSetType(elementType))),
                optionType(elementType)
            )
        }

        // __native_sorted_set_max: {s: SortedSet 'a} -> Option 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_max"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SortedSetType(elementType))),
                optionType(elementType)
            )
        }

        // __native_sorted_set_to_list: {s: SortedSet 'a} -> List 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SortedSetType(elementType))),
                FunkType.ListType(elementType)
            )
        }

        // __native_sorted_set_from_list: {list: List 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("list" to FunkType.ListType(elementType))),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_from_set: {s: Set 'a} -> SortedSet 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_set"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SetType(elementType))),
                FunkType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_to_set: {s: SortedSet 'a} -> Set 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_set"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("s" to FunkType.SortedSetType(elementType))),
                FunkType.SetType(elementType)
            )
        }

        // __native_sorted_set_equals: {s1: SortedSet 'a, s2: SortedSet 'a} -> Bool
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            context["__native_sorted_set_equals"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "s1" to FunkType.SortedSetType(elementType),
                    "s2" to FunkType.SortedSetType(elementType)
                )),
                FunkType.BooleanType
            )
        }
    }
}
