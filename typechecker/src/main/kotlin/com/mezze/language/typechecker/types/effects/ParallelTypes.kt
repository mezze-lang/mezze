package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Parallel (data parallelism) native functions.
 *
 * Provides types for:
 * - Collection operations: map, filter, for_each, reduce, fold
 * - Tuple operations: both, both3
 * - Search operations: find, any, all
 * - Partitioning: partition, group_by
 */
object ParallelTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerCoreOperations(context)
        registerConfigurableOperations(context)
        registerSequentialOperations(context)
        registerForkJoinOperations(context)
    }

    private fun registerCoreOperations(context: MutableMap<String, MezzeType>) {
        // __native_parallel_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        context["__native_parallel_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("b")))
        )

        // __native_parallel_for_each: {xs: ['a], f: {x: 'a} -> {}} -> {}
        context["__native_parallel_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.RecordType(emptyMap())
                )
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_parallel_filter: {xs: ['a], f: {x: 'a} -> Bool} -> ['a]
        context["__native_parallel_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
        )

        // __native_parallel_reduce: {xs: ['a], f: {l: 'a, r: 'a} -> 'a} -> Option 'a
        context["__native_parallel_reduce"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(TypeVar("a")),
                        "r" to MezzeType.VarType(TypeVar("a"))
                    )),
                    MezzeType.VarType(TypeVar("a"))
                )
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        // __native_parallel_fold: {xs: ['a], zero: 'b, map_fn: {x: 'a} -> 'b, combine: {l: 'b, r: 'b} -> 'b} -> 'b
        context["__native_parallel_fold"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "zero" to MezzeType.VarType(TypeVar("b")),
                "map_fn" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "combine" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(TypeVar("b")),
                        "r" to MezzeType.VarType(TypeVar("b"))
                    )),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.VarType(TypeVar("b"))
        )

        // __native_parallel_both: {l: {} -> 'a, r: {} -> 'b} -> {l: 'a, r: 'b}
        context["__native_parallel_both"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "r" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.VarType(TypeVar("a")),
                "r" to MezzeType.VarType(TypeVar("b"))
            ))
        )

        // __native_parallel_both3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c} -> {a: 'a, b: 'b, c: 'c}
        context["__native_parallel_both3"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "b" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "c" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("c"))
                )
            )),
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.VarType(TypeVar("a")),
                "b" to MezzeType.VarType(TypeVar("b")),
                "c" to MezzeType.VarType(TypeVar("c"))
            ))
        )

        // __native_parallel_find: {xs: ['a], f: {x: 'a} -> Bool} -> Option 'a
        context["__native_parallel_find"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        // __native_parallel_any: {xs: ['a], f: {x: 'a} -> Bool} -> Bool
        context["__native_parallel_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.BoolType
        )

        // __native_parallel_all: {xs: ['a], f: {x: 'a} -> Bool} -> Bool
        context["__native_parallel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.BoolType
        )

        // __native_parallel_partition: {xs: ['a], f: {x: 'a} -> Bool} -> {matching: ['a], not_matching: ['a]}
        context["__native_parallel_partition"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.RecordType(mapOf(
                "pass" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "fail" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
            ))
        )

        // __native_parallel_group_by: {xs: ['a], f: {x: 'a} -> 'k} -> [{'k, ['a]}]
        context["__native_parallel_group_by"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("k"))
                )
            )),
            MezzeType.SeqType(MezzeType.RecordType(mapOf(
                "key" to MezzeType.VarType(TypeVar("k")),
                "values" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
            )))
        )
    }

    private fun registerConfigurableOperations(context: MutableMap<String, MezzeType>) {
        // Configurable versions with parallelism and threshold parameters
        // __native_parallel_map_cfg: {xs: ['a], f: {x: 'a} -> 'b, parallelism: Int, threshold: Int} -> ['b]
        context["__native_parallel_map_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("b")))
        )

        context["__native_parallel_for_each_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.RecordType(emptyMap())
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        context["__native_parallel_filter_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
        )

        context["__native_parallel_reduce_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(TypeVar("a")),
                        "r" to MezzeType.VarType(TypeVar("a"))
                    )),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        context["__native_parallel_fold_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "zero" to MezzeType.VarType(TypeVar("b")),
                "map_fn" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "combine" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(TypeVar("b")),
                        "r" to MezzeType.VarType(TypeVar("b"))
                    )),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("b"))
        )

        context["__native_parallel_find_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        context["__native_parallel_any_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.BoolType
        )

        context["__native_parallel_all_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.BoolType
        )

        context["__native_parallel_partition_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.RecordType(mapOf(
                "pass" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "fail" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
            ))
        )

        context["__native_parallel_group_by_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("k"))
                ),
                "parallelism" to MezzeType.IntType,
                "chunk_size" to MezzeType.IntType
            )),
            MezzeType.SeqType(MezzeType.RecordType(mapOf(
                "key" to MezzeType.VarType(TypeVar("k")),
                "values" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
            )))
        )
    }

    private fun registerSequentialOperations(context: MutableMap<String, MezzeType>) {
        // Sequential versions (same signatures as core, different prefix)
        context["__native_sequential_map"] = context["__native_parallel_map"]!!
        context["__native_sequential_for_each"] = context["__native_parallel_for_each"]!!
        context["__native_sequential_filter"] = context["__native_parallel_filter"]!!
        context["__native_sequential_reduce"] = context["__native_parallel_reduce"]!!
        context["__native_sequential_fold"] = context["__native_parallel_fold"]!!
        context["__native_sequential_both"] = context["__native_parallel_both"]!!
        context["__native_sequential_both3"] = context["__native_parallel_both3"]!!
        context["__native_sequential_find"] = context["__native_parallel_find"]!!
        context["__native_sequential_any"] = context["__native_parallel_any"]!!
        context["__native_sequential_all"] = context["__native_parallel_all"]!!
        context["__native_sequential_partition"] = context["__native_parallel_partition"]!!
        context["__native_sequential_group_by"] = context["__native_parallel_group_by"]!!
    }

    private fun registerForkJoinOperations(context: MutableMap<String, MezzeType>) {
        // ForkJoin operations with threshold and parallelism parameters
        // __native_forkjoin_map: {xs: ['a], f: {x: 'a} -> 'b, threshold: Int, parallelism: Int} -> ['b]
        context["__native_forkjoin_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("b")))
        )

        context["__native_forkjoin_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.RecordType(emptyMap())
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        context["__native_forkjoin_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
        )

        context["__native_forkjoin_reduce"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(TypeVar("a")),
                        "r" to MezzeType.VarType(TypeVar("a"))
                    )),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        context["__native_forkjoin_fold"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "zero" to MezzeType.VarType(TypeVar("b")),
                "map_fn" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "combine" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(TypeVar("b")),
                        "r" to MezzeType.VarType(TypeVar("b"))
                    )),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("b"))
        )

        context["__native_forkjoin_both"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "r" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.VarType(TypeVar("a")),
                "r" to MezzeType.VarType(TypeVar("b"))
            ))
        )

        context["__native_forkjoin_both3"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "b" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "c" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("c"))
                ),
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.VarType(TypeVar("a")),
                "b" to MezzeType.VarType(TypeVar("b")),
                "c" to MezzeType.VarType(TypeVar("c"))
            ))
        )

        context["__native_forkjoin_find"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        context["__native_forkjoin_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.BoolType
        )

        context["__native_forkjoin_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.BoolType
        )

        context["__native_forkjoin_partition"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.RecordType(mapOf(
                "pass" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "fail" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
            ))
        )

        context["__native_forkjoin_group_by"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("k"))
                ),
                "threshold" to MezzeType.IntType,
                "parallelism" to MezzeType.IntType
            )),
            MezzeType.SeqType(MezzeType.RecordType(mapOf(
                "key" to MezzeType.VarType(TypeVar("k")),
                "values" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
            )))
        )
    }
}
