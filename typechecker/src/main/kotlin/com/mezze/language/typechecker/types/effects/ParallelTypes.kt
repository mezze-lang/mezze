package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.*

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

    private fun registerCoreOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_parallel_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(seq(b))
        }

        fn("__native_parallel_for_each") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), unit))
            returns(unit)
        }

        fn("__native_parallel_filter") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(seq(a))
        }

        fn("__native_parallel_reduce") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("l" to a, "r" to a), a))
            returns(option(a))
        }

        fn("__native_parallel_fold") {
            val a = tvar("a"); val b = tvar("b")
            param(
                "xs" to seq(a),
                "zero" to b,
                "map_fn" to MezzeType.FunctionType(rec("x" to a), b),
                "combine" to MezzeType.FunctionType(rec("l" to b, "r" to b), b)
            )
            returns(b)
        }

        fn("__native_parallel_both") {
            val a = tvar("a"); val b = tvar("b")
            param(
                "l" to MezzeType.FunctionType(unit, a),
                "r" to MezzeType.FunctionType(unit, b)
            )
            returns(rec("l" to a, "r" to b))
        }

        fn("__native_parallel_both3") {
            val a = tvar("a"); val b = tvar("b"); val c = tvar("c")
            param(
                "a" to MezzeType.FunctionType(unit, a),
                "b" to MezzeType.FunctionType(unit, b),
                "c" to MezzeType.FunctionType(unit, c)
            )
            returns(rec("a" to a, "b" to b, "c" to c))
        }

        fn("__native_parallel_find") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(option(a))
        }

        fn("__native_parallel_any") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }

        fn("__native_parallel_all") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }

        fn("__native_parallel_partition") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(rec("pass" to seq(a), "fail" to seq(a)))
        }

        fn("__native_parallel_group_by") {
            val a = tvar("a"); val k = tvar("k")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), k))
            returns(seq(rec("key" to k, "values" to seq(a))))
        }
    }

    private fun registerConfigurableOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_parallel_map_cfg") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b), "parallelism" to int, "chunk_size" to int)
            returns(seq(b))
        }

        fn("__native_parallel_for_each_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), unit), "parallelism" to int, "chunk_size" to int)
            returns(unit)
        }

        fn("__native_parallel_filter_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "parallelism" to int, "chunk_size" to int)
            returns(seq(a))
        }

        fn("__native_parallel_reduce_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("l" to a, "r" to a), a), "parallelism" to int, "chunk_size" to int)
            returns(option(a))
        }

        fn("__native_parallel_fold_cfg") {
            val a = tvar("a"); val b = tvar("b")
            param(
                "xs" to seq(a),
                "zero" to b,
                "map_fn" to MezzeType.FunctionType(rec("x" to a), b),
                "combine" to MezzeType.FunctionType(rec("l" to b, "r" to b), b),
                "parallelism" to int,
                "chunk_size" to int
            )
            returns(b)
        }

        fn("__native_parallel_find_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "parallelism" to int, "chunk_size" to int)
            returns(option(a))
        }

        fn("__native_parallel_any_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "parallelism" to int, "chunk_size" to int)
            returns(bool)
        }

        fn("__native_parallel_all_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "parallelism" to int, "chunk_size" to int)
            returns(bool)
        }

        fn("__native_parallel_partition_cfg") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "parallelism" to int, "chunk_size" to int)
            returns(rec("pass" to seq(a), "fail" to seq(a)))
        }

        fn("__native_parallel_group_by_cfg") {
            val a = tvar("a"); val k = tvar("k")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), k), "parallelism" to int, "chunk_size" to int)
            returns(seq(rec("key" to k, "values" to seq(a))))
        }
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

    private fun registerForkJoinOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_forkjoin_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b), "threshold" to int, "parallelism" to int)
            returns(seq(b))
        }

        fn("__native_forkjoin_for_each") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), unit), "threshold" to int, "parallelism" to int)
            returns(unit)
        }

        fn("__native_forkjoin_filter") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "threshold" to int, "parallelism" to int)
            returns(seq(a))
        }

        fn("__native_forkjoin_reduce") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("l" to a, "r" to a), a), "threshold" to int, "parallelism" to int)
            returns(option(a))
        }

        fn("__native_forkjoin_fold") {
            val a = tvar("a"); val b = tvar("b")
            param(
                "xs" to seq(a),
                "zero" to b,
                "map_fn" to MezzeType.FunctionType(rec("x" to a), b),
                "combine" to MezzeType.FunctionType(rec("l" to b, "r" to b), b),
                "threshold" to int,
                "parallelism" to int
            )
            returns(b)
        }

        fn("__native_forkjoin_both") {
            val a = tvar("a"); val b = tvar("b")
            param(
                "l" to MezzeType.FunctionType(unit, a),
                "r" to MezzeType.FunctionType(unit, b),
                "parallelism" to int
            )
            returns(rec("l" to a, "r" to b))
        }

        fn("__native_forkjoin_both3") {
            val a = tvar("a"); val b = tvar("b"); val c = tvar("c")
            param(
                "a" to MezzeType.FunctionType(unit, a),
                "b" to MezzeType.FunctionType(unit, b),
                "c" to MezzeType.FunctionType(unit, c),
                "parallelism" to int
            )
            returns(rec("a" to a, "b" to b, "c" to c))
        }

        fn("__native_forkjoin_find") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "threshold" to int, "parallelism" to int)
            returns(option(a))
        }

        fn("__native_forkjoin_any") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "threshold" to int, "parallelism" to int)
            returns(bool)
        }

        fn("__native_forkjoin_all") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "threshold" to int, "parallelism" to int)
            returns(bool)
        }

        fn("__native_forkjoin_partition") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), bool), "threshold" to int, "parallelism" to int)
            returns(rec("pass" to seq(a), "fail" to seq(a)))
        }

        fn("__native_forkjoin_group_by") {
            val a = tvar("a"); val k = tvar("k")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), k), "threshold" to int, "parallelism" to int)
            returns(seq(rec("key" to k, "values" to seq(a))))
        }
    }
}
