package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized batch string concatenation node.
 *
 * Standard string + creates intermediate strings. This node
 * uses StringBuilder with pre-calculated capacity for zero
 * intermediate allocations.
 *
 * Uses:
 * - Pre-sized StringBuilder based on total length
 * - @ExplodeLoop for small counts
 * - No intermediate string allocations
 */
@NodeInfo(shortName = "string.concat.batch")
public abstract class StringConcatBatchNode extends Node {

    public abstract String execute(String[] strings);

    @Specialization(guards = "strings.length == 0")
    protected String concatEmpty(String[] strings) {
        return "";
    }

    @Specialization(guards = "strings.length == 1")
    protected String concatOne(String[] strings) {
        return strings[0];
    }

    @Specialization(guards = "strings.length == 2")
    protected String concatTwo(String[] strings) {
        return strings[0] + strings[1];
    }

    @Specialization(guards = {"strings.length > 2", "strings.length <= 8"})
    @ExplodeLoop
    protected String concatSmall(String[] strings) {
        int totalLen = 0;
        for (int i = 0; i < strings.length; i++) {
            totalLen += strings[i].length();
        }

        StringBuilder sb = new StringBuilder(totalLen);
        for (int i = 0; i < strings.length; i++) {
            sb.append(strings[i]);
        }
        return sb.toString();
    }

    @Specialization(guards = "strings.length > 8")
    @TruffleBoundary
    protected String concatLarge(String[] strings) {
        int totalLen = 0;
        for (String s : strings) {
            totalLen += s.length();
        }

        StringBuilder sb = new StringBuilder(totalLen);
        for (String s : strings) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static StringConcatBatchNode create() {
        return StringConcatBatchNodeGen.create();
    }

    public static StringConcatBatchNode getUncached() {
        return StringConcatBatchNodeGen.getUncached();
    }
}
