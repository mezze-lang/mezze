package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedLoopConditionProfile;

/**
 * Sum {@code start + (start+1) + … + (end-1) + init} via a scalar counted
 * loop over primitive longs — no seq materialization, no BDSL slot
 * traffic, no per-iter dispatch. Mirrors {@link SeqSumLongScalarNode}'s
 * shape for the range-fused fold-add case.
 *
 * This is the ADD-op specialization of the range-fused fold. Emitted by
 * {@code BytecodeCompiler.tryEmitFusedFold}'s range branch when the
 * folder is {@code {acc,x} -> acc + x} — collapses a Block/While with
 * multiple BDSL locals into one Java {@code for} loop.
 */
@NodeInfo(shortName = "sum-range-long")
@OperationProxy.Proxyable(allowUncached = true)
@GenerateUncached
public abstract class SumRangeLongNode extends Node {

    public abstract long execute(long start, long end, long init);

    @Specialization
    public static long doSum(
            long start, long end, long init,
            @Bind Node node,
            @Cached InlinedLoopConditionProfile loopProfile) {
        long acc = init;
        long n = end - start;
        if (n < 0) n = 0;
        loopProfile.profileCounted(node, n);
        for (long i = start; loopProfile.inject(node, i < end); i++) {
            acc += i;
        }
        return acc;
    }
}
