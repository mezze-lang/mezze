package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedLoopConditionProfile;

/**
 * Sum a MezzeSeq of longs into a primitive long via a scalar counted
 * loop over LongSeqStorage.getArray(). No boxing, no MezzeRecord alloc,
 * no per-iteration @TruffleBoundary. Peer of {@link SeqReduceLongSIMDNode}
 * minus the SIMD path — per SeqNatives.sum:746-752 the scalar loop wins
 * on SVM/AOT because native-image has no LongVector intrinsic.
 */
@NodeInfo(shortName = "seq-sum-long-scalar")
@OperationProxy.Proxyable(allowUncached = true)
@GenerateUncached
public abstract class SeqSumLongScalarNode extends Node {

    public abstract long execute(MezzeSeq<?> seq);

    @Specialization(guards = "isLongStorage(seq)")
    public static long doLongStorage(
            MezzeSeq<?> seq,
            @Bind Node node,
            @Cached InlinedLoopConditionProfile loopProfile) {
        LongSeqStorage ls = (LongSeqStorage) seq.getStorage();
        long[] data = ls.getArray();
        int n = ls.size();
        long acc = 0;
        loopProfile.profileCounted(node, n);
        for (int i = 0; loopProfile.inject(node, i < n); i++) {
            acc += data[i];
        }
        return acc;
    }

    @Specialization(guards = "!isLongStorage(seq)")
    @TruffleBoundary
    public static long doBoxed(MezzeSeq<?> seq) {
        int n = seq.size();
        long acc = 0;
        for (int i = 0; i < n; i++) {
            Object e = seq.get(i);
            if (e instanceof Long l) acc += l;
            else if (e instanceof Integer x) acc += x;
        }
        return acc;
    }

    public static boolean isLongStorage(MezzeSeq<?> seq) {
        return seq.getStorage() instanceof LongSeqStorage;
    }
}
