package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

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

    fun registerAll(context: MutableMap<String, FunkType>) {
        registerCoreOperations(context)
        registerConfigurableOperations(context)
        registerSequentialOperations(context)
        registerForkJoinOperations(context)
    }

    private fun registerCoreOperations(context: MutableMap<String, FunkType>) {
        // __native_parallel_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        context["__native_parallel_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                )
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("b")))
        )

        // __native_parallel_for_each: {xs: ['a], f: {x: 'a} -> {}} -> {}
        context["__native_parallel_for_each"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.RecordType(emptyMap())
                )
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_parallel_filter: {xs: ['a], f: {x: 'a} -> Bool} -> ['a]
        context["__native_parallel_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        // __native_parallel_reduce: {xs: ['a], f: {left: 'a, right: 'a} -> 'a} -> Option 'a
        context["__native_parallel_reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "left" to FunkType.VarType(TypeVar("a")),
                        "right" to FunkType.VarType(TypeVar("a"))
                    )),
                    FunkType.VarType(TypeVar("a"))
                )
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        )

        // __native_parallel_fold: {xs: ['a], zero: 'b, map_fn: {x: 'a} -> 'b, combine: {left: 'b, right: 'b} -> 'b} -> 'b
        context["__native_parallel_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "zero" to FunkType.VarType(TypeVar("b")),
                "map_fn" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                ),
                "combine" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "left" to FunkType.VarType(TypeVar("b")),
                        "right" to FunkType.VarType(TypeVar("b"))
                    )),
                    FunkType.VarType(TypeVar("b"))
                )
            )),
            FunkType.VarType(TypeVar("b"))
        )

        // __native_parallel_both: {left: {} -> 'a, right: {} -> 'b} -> {left: 'a, right: 'b}
        context["__native_parallel_both"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "right" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                )
            )),
            FunkType.RecordType(mapOf(
                "left" to FunkType.VarType(TypeVar("a")),
                "right" to FunkType.VarType(TypeVar("b"))
            ))
        )

        // __native_parallel_both3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c} -> {a: 'a, b: 'b, c: 'c}
        context["__native_parallel_both3"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "b" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                ),
                "c" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("c"))
                )
            )),
            FunkType.RecordType(mapOf(
                "a" to FunkType.VarType(TypeVar("a")),
                "b" to FunkType.VarType(TypeVar("b")),
                "c" to FunkType.VarType(TypeVar("c"))
            ))
        )

        // __native_parallel_find: {xs: ['a], f: {x: 'a} -> Bool} -> Option 'a
        context["__native_parallel_find"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        )

        // __native_parallel_any: {xs: ['a], f: {x: 'a} -> Bool} -> Bool
        context["__native_parallel_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.BooleanType
        )

        // __native_parallel_all: {xs: ['a], f: {x: 'a} -> Bool} -> Bool
        context["__native_parallel_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.BooleanType
        )

        // __native_parallel_partition: {xs: ['a], f: {x: 'a} -> Bool} -> {matching: ['a], not_matching: ['a]}
        context["__native_parallel_partition"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.RecordType(mapOf(
                "matching" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "not_matching" to FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
            ))
        )

        // __native_parallel_group_by: {xs: ['a], f: {x: 'a} -> 'k} -> [{'k, ['a]}]
        context["__native_parallel_group_by"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("k"))
                )
            )),
            FunkType.ArrayType(FunkType.RecordType(mapOf(
                "key" to FunkType.VarType(TypeVar("k")),
                "values" to FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
            )))
        )
    }

    private fun registerConfigurableOperations(context: MutableMap<String, FunkType>) {
        // Configurable versions with parallelism and threshold parameters
        // __native_parallel_map_cfg: {xs: ['a], f: {x: 'a} -> 'b, parallelism: Int, threshold: Int} -> ['b]
        context["__native_parallel_map_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("b")))
        )

        context["__native_parallel_for_each_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.RecordType(emptyMap())
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )

        context["__native_parallel_filter_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        context["__native_parallel_reduce_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "left" to FunkType.VarType(TypeVar("a")),
                        "right" to FunkType.VarType(TypeVar("a"))
                    )),
                    FunkType.VarType(TypeVar("a"))
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        )

        context["__native_parallel_fold_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "zero" to FunkType.VarType(TypeVar("b")),
                "map_fn" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                ),
                "combine" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "left" to FunkType.VarType(TypeVar("b")),
                        "right" to FunkType.VarType(TypeVar("b"))
                    )),
                    FunkType.VarType(TypeVar("b"))
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("b"))
        )

        context["__native_parallel_find_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        )

        context["__native_parallel_any_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        context["__native_parallel_all_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        context["__native_parallel_partition_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.RecordType(mapOf(
                "matching" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "not_matching" to FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
            ))
        )

        context["__native_parallel_group_by_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("k"))
                ),
                "parallelism" to FunkType.IntType,
                "chunk_size" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.RecordType(mapOf(
                "key" to FunkType.VarType(TypeVar("k")),
                "values" to FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
            )))
        )
    }

    private fun registerSequentialOperations(context: MutableMap<String, FunkType>) {
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

    private fun registerForkJoinOperations(context: MutableMap<String, FunkType>) {
        // ForkJoin operations with threshold and parallelism parameters
        // __native_forkjoin_map: {xs: ['a], f: {x: 'a} -> 'b, threshold: Int, parallelism: Int} -> ['b]
        context["__native_forkjoin_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("b")))
        )

        context["__native_forkjoin_for_each"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.RecordType(emptyMap())
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )

        context["__native_forkjoin_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        context["__native_forkjoin_reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "left" to FunkType.VarType(TypeVar("a")),
                        "right" to FunkType.VarType(TypeVar("a"))
                    )),
                    FunkType.VarType(TypeVar("a"))
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        )

        context["__native_forkjoin_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "zero" to FunkType.VarType(TypeVar("b")),
                "map_fn" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                ),
                "combine" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "left" to FunkType.VarType(TypeVar("b")),
                        "right" to FunkType.VarType(TypeVar("b"))
                    )),
                    FunkType.VarType(TypeVar("b"))
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("b"))
        )

        context["__native_forkjoin_both"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "right" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                ),
                "parallelism" to FunkType.IntType
            )),
            FunkType.RecordType(mapOf(
                "left" to FunkType.VarType(TypeVar("a")),
                "right" to FunkType.VarType(TypeVar("b"))
            ))
        )

        context["__native_forkjoin_both3"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "b" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                ),
                "c" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("c"))
                ),
                "parallelism" to FunkType.IntType
            )),
            FunkType.RecordType(mapOf(
                "a" to FunkType.VarType(TypeVar("a")),
                "b" to FunkType.VarType(TypeVar("b")),
                "c" to FunkType.VarType(TypeVar("c"))
            ))
        )

        context["__native_forkjoin_find"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        )

        context["__native_forkjoin_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        context["__native_forkjoin_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        context["__native_forkjoin_partition"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.RecordType(mapOf(
                "matching" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "not_matching" to FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
            ))
        )

        context["__native_forkjoin_group_by"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("k"))
                ),
                "threshold" to FunkType.IntType,
                "parallelism" to FunkType.IntType
            )),
            FunkType.ArrayType(FunkType.RecordType(mapOf(
                "key" to FunkType.VarType(TypeVar("k")),
                "values" to FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
            )))
        )
    }
}
