package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for Seq (sequence) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/seq.mz
 *
 * Seq is the primary sequence type in Mezze, backed by optimized MezzeSeq.
 * Array literals [] create Seq instances at runtime.
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use. If we shared type variables across functions,
 * unifying one would affect all others.
 */
object SeqTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSeqNatives(context)
    }

    private fun registerSeqNatives(context: MutableMap<String, MezzeType>) {
        // __native_seq_empty: {} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                seqType
            )
        }

        // __native_seq_size: {xs: ['a]} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                MezzeType.IntType
            )
        }

        // __native_seq_is_empty: {xs: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                MezzeType.BoolType
            )
        }

        // __native_seq_get: {xs: ['a], i: Int} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType
                )),
                optionType(elemType)
            )
        }

        // __native_seq_set: {xs: ['a], i: Int, x: 'a} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType,
                    "x" to elemType
                )),
                optionType(seqType)
            )
        }

        // __native_seq_add: {xs: ['a], x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_add"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                seqType
            )
        }

        // __native_seq_push: {xs: ['a], x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_push"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                seqType
            )
        }

        // __native_seq_push_front: {xs: ['a], x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_push_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                seqType
            )
        }

        // __native_seq_remove: {xs: ['a], i: Int} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType
                )),
                optionType(seqType)
            )
        }

        // __native_seq_concat: {xs: ['a], ys: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_concat"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "ys" to seqType
                )),
                seqType
            )
        }

        // __native_seq_head: {xs: ['a]} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_head"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                optionType(elemType)
            )
        }

        // __native_seq_first: {xs: ['a]} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_first"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                optionType(elemType)
            )
        }

        // __native_seq_last: {xs: ['a]} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_last"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                optionType(elemType)
            )
        }

        // __native_seq_tail: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_tail"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_init: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_init"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_slice: {xs: ['a], start: Int, end: Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_slice"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType
                )),
                seqType
            )
        }

        // __native_seq_take: {xs: ['a], n: Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_take"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "n" to MezzeType.IntType
                )),
                seqType
            )
        }

        // __native_seq_drop: {xs: ['a], n: Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_drop"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "n" to MezzeType.IntType
                )),
                seqType
            )
        }

        // __native_seq_take_while: {xs: ['a], p: {x: 'a} -> Bool} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_take_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                seqType
            )
        }

        // __native_seq_drop_while: {xs: ['a], p: {x: 'a} -> Bool} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_drop_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                seqType
            )
        }

        // __native_seq_contains: {xs: ['a], x: 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_index_of: {xs: ['a], x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_index_of"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // __native_seq_find: {xs: ['a], p: {x: 'a} -> Bool} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_find"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                optionType(elemType)
            )
        }

        // __native_seq_find_index: {xs: ['a], p: {x: 'a} -> Bool} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_find_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // __native_seq_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        run {
            val inputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val inputSeqType = MezzeType.SeqType(inputElemType)
            val outputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val outputSeqType = MezzeType.SeqType(outputElemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to inputElemType)),
                outputElemType
            )
            context["__native_seq_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to inputSeqType,
                    "f" to funcType
                )),
                outputSeqType
            )
        }

        // __native_seq_filter: {xs: ['a], p: {x: 'a} -> Bool} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_filter"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                seqType
            )
        }

        // __native_seq_flat_map: {xs: ['a], f: {x: 'a} -> ['b]} -> ['b]
        run {
            val inputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val inputSeqType = MezzeType.SeqType(inputElemType)
            val outputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val outputSeqType = MezzeType.SeqType(outputElemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to inputElemType)),
                outputSeqType
            )
            context["__native_seq_flat_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to inputSeqType,
                    "f" to funcType
                )),
                outputSeqType
            )
        }

        // __native_seq_fold: {xs: ['a], init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_seq_fold"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "init" to accType,
                    "f" to funcType
                )),
                accType
            )
        }

        // __native_seq_reduce: {xs: ['a], f: {acc: 'a, x: 'a} -> 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to elemType,
                    "x" to elemType
                )),
                elemType
            )
            context["__native_seq_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                optionType(elemType)
            )
        }

        // __native_seq_any: {xs: ['a], p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_all: {xs: ['a], p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_none: {xs: ['a], p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_none"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_count: {xs: ['a], p: {x: 'a} -> Bool} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_seq_count"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                MezzeType.IntType
            )
        }

        // __native_seq_reverse: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_sort: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_sort"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_sort_by: {xs: ['a], cmp: {a: 'a, b: 'a} -> Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val cmpFuncType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "a" to elemType,
                    "b" to elemType
                )),
                MezzeType.IntType
            )
            context["__native_seq_sort_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "cmp" to cmpFuncType
                )),
                seqType
            )
        }

        // __native_seq_unique: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_unique"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_distinct: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_distinct"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_flatten: {xs: [['a]]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            context["__native_seq_flatten"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to outerSeqType)),
                innerSeqType
            )
        }

        // __native_seq_partition: {xs: ['a], p: {x: 'a} -> Bool} -> {pass: ['a], fail: ['a]}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            val resultType = MezzeType.RecordType(mapOf(
                "pass" to seqType,
                "fail" to seqType
            ))
            context["__native_seq_partition"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                resultType
            )
        }

        // __native_seq_zip: {xs: ['a], ys: ['b]} -> [{fst: 'a, snd: 'b}]
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqTypeA = MezzeType.SeqType(elemTypeA)
            val seqTypeB = MezzeType.SeqType(elemTypeB)
            val pairType = MezzeType.RecordType(mapOf("fst" to elemTypeA, "snd" to elemTypeB))
            val resultType = MezzeType.SeqType(pairType)
            context["__native_seq_zip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqTypeA, "ys" to seqTypeB)),
                resultType
            )
        }

        // __native_seq_zip_with_index: {xs: ['a]} -> [{value: 'a, index: Int}]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val pairType = MezzeType.RecordType(mapOf("value" to elemType, "index" to MezzeType.IntType))
            val resultType = MezzeType.SeqType(pairType)
            context["__native_seq_zip_with_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                resultType
            )
        }

        // __native_seq_unzip: {xs: [{fst: 'a, snd: 'b}]} -> {fsts: ['a], snds: ['b]}
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val pairType = MezzeType.RecordType(mapOf("fst" to elemTypeA, "snd" to elemTypeB))
            val inputType = MezzeType.SeqType(pairType)
            val resultType = MezzeType.RecordType(mapOf(
                "fsts" to MezzeType.SeqType(elemTypeA),
                "snds" to MezzeType.SeqType(elemTypeB)
            ))
            context["__native_seq_unzip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to inputType)),
                resultType
            )
        }

        // __native_seq_intersperse: {xs: ['a], sep: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_intersperse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "sep" to elemType
                )),
                seqType
            )
        }

        // __native_seq_chunk: {xs: ['a], size: Int} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            context["__native_seq_chunk"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to innerSeqType,
                    "size" to MezzeType.IntType
                )),
                outerSeqType
            )
        }

        // __native_seq_sliding: {xs: ['a], size: Int} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            context["__native_seq_sliding"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to innerSeqType,
                    "size" to MezzeType.IntType
                )),
                outerSeqType
            )
        }

        // __native_seq_range: {start: Int, end: Int} -> [Int]
        run {
            val intSeqType = MezzeType.SeqType(MezzeType.IntType)
            context["__native_seq_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType
                )),
                intSeqType
            )
        }

        // __native_seq_range_step: {start: Int, end: Int, step: Int} -> [Int]
        run {
            val intSeqType = MezzeType.SeqType(MezzeType.IntType)
            context["__native_seq_range_step"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType,
                    "step" to MezzeType.IntType
                )),
                intSeqType
            )
        }

        // __native_seq_equals: {l: ['a], r: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "l" to seqType,
                    "r" to seqType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_compare: {xs: ['a], ys: ['a]} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "ys" to seqType
                )),
                MezzeType.IntType
            )
        }

        // ==================== Min/Max/Sum/Product ====================

        // __native_seq_min: {xs: ['a]} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_min"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                optionType(elemType)
            )
        }

        // __native_seq_max: {xs: ['a]} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_max"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                optionType(elemType)
            )
        }

        // __native_seq_min_by: {xs: ['a], f: {x: 'a} -> 'b} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_seq_min_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                optionType(elemType)
            )
        }

        // __native_seq_max_by: {xs: ['a], f: {x: 'a} -> 'b} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_seq_max_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                optionType(elemType)
            )
        }

        // __native_seq_sum: {xs: [Int]} -> Int
        run {
            val intSeqType = MezzeType.SeqType(MezzeType.IntType)
            context["__native_seq_sum"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to intSeqType)),
                MezzeType.IntType
            )
        }

        // __native_seq_sum_float: {xs: [Float]} -> Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            context["__native_seq_sum_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                MezzeType.FloatType
            )
        }

        // __native_seq_product: {xs: [Int]} -> Int
        run {
            val intSeqType = MezzeType.SeqType(MezzeType.IntType)
            context["__native_seq_product"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to intSeqType)),
                MezzeType.IntType
            )
        }

        // __native_seq_product_float: {xs: [Float]} -> Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            context["__native_seq_product_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                MezzeType.FloatType
            )
        }

        // ==================== Group By ====================

        // __native_seq_group_by: {xs: ['a], f: {x: 'a} -> 'b} -> [{key: 'b, values: ['a]}]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            val groupType = MezzeType.RecordType(mapOf(
                "key" to keyType,
                "values" to seqType
            ))
            val resultType = MezzeType.SeqType(groupType)
            context["__native_seq_group_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                resultType
            )
        }

        // ==================== Enumerate/ForEach/Scan ====================

        // __native_seq_enumerate: {xs: ['a]} -> [{value: 'a, index: Int}]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val pairType = MezzeType.RecordType(mapOf("value" to elemType, "index" to MezzeType.IntType))
            val resultType = MezzeType.SeqType(pairType)
            context["__native_seq_enumerate"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                resultType
            )
        }

        // __native_seq_for_each: {xs: ['a], f: {x: 'a} -> 'b} -> {}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val retType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                retType
            )
            context["__native_seq_for_each"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_seq_scan: {xs: ['a], init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> ['b]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val resultSeqType = MezzeType.SeqType(accType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_seq_scan"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "init" to accType,
                    "f" to funcType
                )),
                resultSeqType
            )
        }

        // ==================== Split Operations ====================

        // __native_seq_split_at: {xs: ['a], i: Int} -> {l: ['a], r: ['a]}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val resultType = MezzeType.RecordType(mapOf(
                "l" to seqType,
                "r" to seqType
            ))
            context["__native_seq_split_at"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType
                )),
                resultType
            )
        }

        // __native_seq_span: {xs: ['a], p: {x: 'a} -> Bool} -> {l: ['a], r: ['a]}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            val resultType = MezzeType.RecordType(mapOf(
                "l" to seqType,
                "r" to seqType
            ))
            context["__native_seq_span"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "p" to predicateType
                )),
                resultType
            )
        }

        // ==================== Dedup ====================

        // __native_seq_dedup: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_dedup"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                seqType
            )
        }

        // __native_seq_dedup_by: {xs: ['a], f: {x: 'a} -> 'b} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_seq_dedup_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                seqType
            )
        }

        // ==================== Singleton/Nth/Fill ====================

        // __native_seq_singleton: {x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                seqType
            )
        }

        // __native_seq_nth: {xs: ['a], i: Int} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_nth"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType
                )),
                optionType(elemType)
            )
        }

        // __native_seq_fill: {n: Int, x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_fill"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "n" to MezzeType.IntType,
                    "x" to elemType
                )),
                seqType
            )
        }

        // __native_seq_fill_with: {n: Int, f: {i: Int} -> 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("i" to MezzeType.IntType)),
                elemType
            )
            context["__native_seq_fill_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "n" to MezzeType.IntType,
                    "f" to funcType
                )),
                seqType
            )
        }

        // ==================== Group Consecutive ====================

        // __native_seq_group_consecutive: {xs: ['a]} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            context["__native_seq_group_consecutive"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to innerSeqType)),
                outerSeqType
            )
        }

        // __native_seq_group_consecutive_by: {xs: ['a], f: {x: 'a} -> 'b} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_seq_group_consecutive_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to innerSeqType,
                    "f" to funcType
                )),
                outerSeqType
            )
        }

        // ==================== Windows/Chunks/Rotate ====================

        // __native_seq_windows: {xs: ['a], size: Int} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            context["__native_seq_windows"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to innerSeqType,
                    "size" to MezzeType.IntType
                )),
                outerSeqType
            )
        }

        // __native_seq_chunks_exact: {xs: ['a], size: Int} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerSeqType = MezzeType.SeqType(elemType)
            val outerSeqType = MezzeType.SeqType(innerSeqType)
            context["__native_seq_chunks_exact"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to innerSeqType,
                    "size" to MezzeType.IntType
                )),
                outerSeqType
            )
        }

        // __native_seq_rotate_left: {xs: ['a], n: Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_rotate_left"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "n" to MezzeType.IntType
                )),
                seqType
            )
        }

        // __native_seq_rotate_right: {xs: ['a], n: Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_rotate_right"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "n" to MezzeType.IntType
                )),
                seqType
            )
        }

        // ==================== Binary Search ====================

        // __native_seq_binary_search: {xs: ['a], x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_binary_search"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // __native_seq_binary_search_by: {xs: ['a], x: 'a, cmp: {a: 'a, b: 'a} -> Int} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val cmpFuncType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "a" to elemType,
                    "b" to elemType
                )),
                MezzeType.IntType
            )
            context["__native_seq_binary_search_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType,
                    "cmp" to cmpFuncType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // ==================== Sort By Key ====================

        // __native_seq_sort_by_key: {xs: ['a], f: {x: 'a} -> 'b} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_seq_sort_by_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to funcType
                )),
                seqType
            )
        }

        // ==================== New Seq Operations ====================

        // __native_seq_last_index_of: {xs: ['a], x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_last_index_of"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // __native_seq_replace: {xs: ['a], old: 'a, new_val: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_replace"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "old" to elemType,
                    "new_val" to elemType
                )),
                seqType
            )
        }

        // __native_seq_replace_all: {xs: ['a], old: 'a, new_val: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_replace_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "old" to elemType,
                    "new_val" to elemType
                )),
                seqType
            )
        }

        // __native_seq_insert_at: {xs: ['a], i: Int, x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_insert_at"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType,
                    "x" to elemType
                )),
                seqType
            )
        }

        // __native_seq_swap: {xs: ['a], i: Int, j: Int} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_swap"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType,
                    "j" to MezzeType.IntType
                )),
                optionType(seqType)
            )
        }

        // __native_seq_starts_with: {xs: ['a], prefix: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_starts_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "prefix" to seqType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_ends_with: {xs: ['a], suffix: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_ends_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "suffix" to seqType
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_join: {xs: [String], sep: String} -> String
        run {
            val stringSeqType = MezzeType.SeqType(MezzeType.StringType)
            context["__native_seq_join"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to stringSeqType,
                    "sep" to MezzeType.StringType
                )),
                MezzeType.StringType
            )
        }

        // ==================== Advanced Zip Operations ====================

        // __native_seq_zip_with: {xs: ['a], ys: ['b], f: {a: 'a, b: 'b} -> 'c} -> ['c]
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val elemTypeC = MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            val seqTypeA = MezzeType.SeqType(elemTypeA)
            val seqTypeB = MezzeType.SeqType(elemTypeB)
            val fnType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("a" to elemTypeA, "b" to elemTypeB)),
                elemTypeC
            )
            context["__native_seq_zip_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqTypeA,
                    "ys" to seqTypeB,
                    "f" to fnType
                )),
                MezzeType.SeqType(elemTypeC)
            )
        }

        // __native_seq_zip3: {xs: ['a], ys: ['b], zs: ['c]} -> [{fst: 'a, snd: 'b, thd: 'c}]
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val elemTypeC = MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            val tripleType = MezzeType.RecordType(mapOf("fst" to elemTypeA, "snd" to elemTypeB, "thd" to elemTypeC))
            context["__native_seq_zip3"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to MezzeType.SeqType(elemTypeA),
                    "ys" to MezzeType.SeqType(elemTypeB),
                    "zs" to MezzeType.SeqType(elemTypeC)
                )),
                MezzeType.SeqType(tripleType)
            )
        }

        // __native_seq_zip4: {xs: ['a], ys: ['b], zs: ['c], ws: ['d]} -> [{fst: 'a, snd: 'b, thd: 'c, fth: 'd}]
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val elemTypeC = MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            val elemTypeD = MezzeType.VarType(com.mezze.language.ast.TypeVar("d"))
            val quadType = MezzeType.RecordType(mapOf(
                "fst" to elemTypeA, "snd" to elemTypeB, "thd" to elemTypeC, "fth" to elemTypeD
            ))
            context["__native_seq_zip4"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to MezzeType.SeqType(elemTypeA),
                    "ys" to MezzeType.SeqType(elemTypeB),
                    "zs" to MezzeType.SeqType(elemTypeC),
                    "ws" to MezzeType.SeqType(elemTypeD)
                )),
                MezzeType.SeqType(quadType)
            )
        }

        // __native_seq_cartesian_product: {xs: ['a], ys: ['b]} -> [{fst: 'a, snd: 'b}]
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val pairType = MezzeType.RecordType(mapOf("fst" to elemTypeA, "snd" to elemTypeB))
            context["__native_seq_cartesian_product"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to MezzeType.SeqType(elemTypeA),
                    "ys" to MezzeType.SeqType(elemTypeB)
                )),
                MezzeType.SeqType(pairType)
            )
        }

        // ==================== Combinatorics ====================

        // __native_seq_permutations: {xs: ['a]} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_permutations"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                MezzeType.SeqType(seqType)
            )
        }

        // __native_seq_combinations: {xs: ['a], k: Int} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_combinations"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "k" to MezzeType.IntType
                )),
                MezzeType.SeqType(seqType)
            )
        }

        // __native_seq_subsequences: {xs: ['a]} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_subsequences"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                MezzeType.SeqType(seqType)
            )
        }

        // ==================== Statistical Operations ====================

        // __native_seq_average: {xs: [Int | Float]} -> Option Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            val optionFloat = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.FloatType)),
                "None" to null
            ))
            context["__native_seq_average"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                optionFloat
            )
        }

        // __native_seq_variance: {xs: [Float]} -> Option Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            val optionFloat = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.FloatType)),
                "None" to null
            ))
            context["__native_seq_variance"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                optionFloat
            )
        }

        // __native_seq_stddev: {xs: [Float]} -> Option Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            val optionFloat = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.FloatType)),
                "None" to null
            ))
            context["__native_seq_stddev"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                optionFloat
            )
        }

        // __native_seq_median: {xs: [Float]} -> Option Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            val optionFloat = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.FloatType)),
                "None" to null
            ))
            context["__native_seq_median"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                optionFloat
            )
        }

        // ==================== Sorting Checks ====================

        // __native_seq_is_sorted: {xs: ['a]} -> Bool (where 'a: Ord)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_is_sorted"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                MezzeType.BoolType
            )
        }

        // __native_seq_is_sorted_by: {xs: ['a], f: {x: 'a} -> 'b} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val seqType = MezzeType.SeqType(elemType)
            val keyFn = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_seq_is_sorted_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "f" to keyFn
                )),
                MezzeType.BoolType
            )
        }

        // __native_seq_is_sorted_descending: {xs: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_is_sorted_descending"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                MezzeType.BoolType
            )
        }

        // ==================== Comparison Operations ====================

        // __native_seq_compare: {xs: ['a], ys: ['a]} -> Int
        // Returns -1 for less than, 0 for equal, 1 for greater than
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "ys" to seqType
                )),
                MezzeType.IntType
            )
        }

        // ==================== Advanced Binary Search ====================

        // __native_seq_binary_search_insert_point: {xs: ['a], x: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_binary_search_insert_point"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                MezzeType.IntType
            )
        }

        // __native_seq_lower_bound: {xs: ['a], x: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_lower_bound"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                MezzeType.IntType
            )
        }

        // __native_seq_upper_bound: {xs: ['a], x: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_upper_bound"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                MezzeType.IntType
            )
        }

        // __native_seq_equal_range: {xs: ['a], x: 'a} -> {start: Int, end: Int}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val rangeType = MezzeType.RecordType(mapOf(
                "start" to MezzeType.IntType,
                "end" to MezzeType.IntType
            ))
            context["__native_seq_equal_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                rangeType
            )
        }

        // ==================== Counting ====================

        // __native_seq_count_element: {xs: ['a], x: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_count_element"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "x" to elemType
                )),
                MezzeType.IntType
            )
        }

        // ==================== Update Operations ====================

        // __native_seq_update: {xs: ['a], i: Int, f: {x: 'a} -> 'a} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val updateFn = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                elemType
            )
            val optionSeq = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to seqType)),
                "None" to null
            ))
            context["__native_seq_update"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType,
                    "f" to updateFn
                )),
                optionSeq
            )
        }

        // __native_seq_remove_at: {xs: ['a], i: Int} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val optionSeq = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to seqType)),
                "None" to null
            ))
            context["__native_seq_remove_at"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "i" to MezzeType.IntType
                )),
                optionSeq
            )
        }

        // ==================== Split Operations ====================

        // __native_seq_split: {xs: ['a], sep: 'a} -> [['a]]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_split"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to seqType,
                    "sep" to elemType
                )),
                MezzeType.SeqType(seqType)
            )
        }

        // ==================== Float-specific Operations ====================

        // __native_seq_min_float: {xs: [Float]} -> Option Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            val optionFloat = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.FloatType)),
                "None" to null
            ))
            context["__native_seq_min_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                optionFloat
            )
        }

        // __native_seq_max_float: {xs: [Float]} -> Option Float
        run {
            val floatSeqType = MezzeType.SeqType(MezzeType.FloatType)
            val optionFloat = MezzeType.VariantType(mapOf(
                "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.FloatType)),
                "None" to null
            ))
            context["__native_seq_max_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatSeqType)),
                optionFloat
            )
        }

        // ==================== Cross-Collection Conversions ====================

        // __native_seq_to_list: {xs: ['a]} -> List['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val listType = MezzeType.ListType(elemType)
            context["__native_seq_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                listType
            )
        }

        // __native_seq_to_set: {xs: ['a]} -> Set['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val setType = MezzeType.SetType(elemType)
            context["__native_seq_to_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                setType
            )
        }

        // __native_seq_to_deque: {xs: ['a]} -> Deque['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_seq_to_deque"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                dequeType
            )
        }

        // __native_seq_to_sorted_set: {xs: ['a]} -> SortedSet['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val seqType = MezzeType.SeqType(elemType)
            val sortedSetType = MezzeType.SortedSetType(elemType)
            context["__native_seq_to_sorted_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                sortedSetType
            )
        }

        // __native_seq_to_map: {xs: [{k: 'k, v: 'v}]} -> Map['k, 'v]
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val pairType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val seqType = MezzeType.SeqType(pairType)
            val mapType = MezzeType.MapType(keyType, valType)
            context["__native_seq_to_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                mapType
            )
        }

        // __native_seq_to_sorted_map: {xs: [{k: 'k, v: 'v}]} -> SortedMap['k, 'v]
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val pairType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val seqType = MezzeType.SeqType(pairType)
            val sortedMapType = MezzeType.SortedMapType(keyType, valType)
            context["__native_seq_to_sorted_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to seqType)),
                sortedMapType
            )
        }

        // __native_seq_from_list: {xs: List['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_from_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                seqType
            )
        }

        // __native_seq_from_set: {s: Set['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val setType = MezzeType.SetType(elemType)
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_from_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to setType)),
                seqType
            )
        }

        // __native_seq_from_deque: {d: Deque['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_from_deque"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                seqType
            )
        }

        // __native_seq_from_sorted_set: {s: SortedSet['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val sortedSetType = MezzeType.SortedSetType(elemType)
            val seqType = MezzeType.SeqType(elemType)
            context["__native_seq_from_sorted_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to sortedSetType)),
                seqType
            )
        }

        // __native_seq_from_map: {m: Map['k, 'v]} -> [{k: 'k, v: 'v}]
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val mapType = MezzeType.MapType(keyType, valType)
            val pairType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val seqType = MezzeType.SeqType(pairType)
            context["__native_seq_from_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to mapType)),
                seqType
            )
        }

        // __native_seq_from_sorted_map: {m: SortedMap['k, 'v]} -> [{k: 'k, v: 'v}]
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val sortedMapType = MezzeType.SortedMapType(keyType, valType)
            val pairType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val seqType = MezzeType.SeqType(pairType)
            context["__native_seq_from_sorted_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to sortedMapType)),
                seqType
            )
        }
    }
}
