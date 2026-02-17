package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for SortedSet operations.
 *
 * Corresponds to stdlib file:
 * - std/collections/sorted_set.mz
 *
 * SortedSet is backed by Vavr TreeSet for O(log n) operations with elements in sorted order.
 */
object SortedSetTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSortedSetNatives(context)
    }

    private fun registerSortedSetNatives(context: MutableMap<String, MezzeType>) {
        // __native_sorted_set_empty: {} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_singleton: {x: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elementType)),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_add: {s: SortedSet 'a, x: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_add"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "x" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_remove: {s: SortedSet 'a, x: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "x" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_contains: {s: SortedSet 'a, x: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "x" to elementType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_sorted_set_size: {s: SortedSet 'a} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.IntType
            )
        }

        // __native_sorted_set_is_empty: {s: SortedSet 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.BooleanType
            )
        }

        // __native_sorted_set_union: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_union"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_intersect: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_intersect"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_difference: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_difference"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_min: {s: SortedSet 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_min"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                optionType(elementType)
            )
        }

        // __native_sorted_set_max: {s: SortedSet 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_max"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                optionType(elementType)
            )
        }

        // __native_sorted_set_to_list: {s: SortedSet 'a} -> List 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.ListType(elementType)
            )
        }

        // __native_sorted_set_from_list: {list: List 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("list" to MezzeType.ListType(elementType))),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_from_set: {s: Set 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SetType(elementType))),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_to_set: {s: SortedSet 'a} -> Set 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.SetType(elementType)
            )
        }

        // __native_sorted_set_equals: {s1: SortedSet 'a, s2: SortedSet 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.BooleanType
            )
        }
    }
}
