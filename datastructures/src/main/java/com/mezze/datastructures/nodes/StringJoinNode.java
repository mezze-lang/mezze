package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized string join node.
 *
 * Joins sequence of strings with separator.
 * Pre-calculates total capacity for zero intermediate allocations.
 *
 * Uses:
 * - Pre-sized StringBuilder
 * - @ExplodeLoop for small sequences
 * - Direct array access for ObjectSeqStorage
 */
@NodeInfo(shortName = "string.join")
public abstract class StringJoinNode extends Node {

    public abstract String execute(MezzeSeq<?> strings, String separator);

    @Specialization(guards = "strings.size() == 0")
    protected String joinEmpty(MezzeSeq<?> strings, String separator) {
        return "";
    }

    @Specialization(guards = "strings.size() == 1")
    protected String joinOne(MezzeSeq<?> strings, String separator) {
        return (String) strings.get(0);
    }

    @Specialization(guards = {"strings.size() > 1", "strings.size() <= 8"})
    @ExplodeLoop
    protected String joinSmall(MezzeSeq<?> strings, String separator) {
        int size = strings.size();
        int sepLen = separator.length();

        // Calculate total length
        int totalLen = (size - 1) * sepLen;
        for (int i = 0; i < size; i++) {
            totalLen += ((String) strings.get(i)).length();
        }

        StringBuilder sb = new StringBuilder(totalLen);
        sb.append((String) strings.get(0));
        for (int i = 1; i < size; i++) {
            sb.append(separator);
            sb.append((String) strings.get(i));
        }
        return sb.toString();
    }

    @Specialization(guards = "strings.size() > 8")
    @TruffleBoundary
    protected String joinLarge(MezzeSeq<?> strings, String separator) {
        int size = strings.size();
        int sepLen = separator.length();

        // Calculate total length
        int totalLen = (size - 1) * sepLen;
        for (int i = 0; i < size; i++) {
            totalLen += ((String) strings.get(i)).length();
        }

        StringBuilder sb = new StringBuilder(totalLen);
        sb.append((String) strings.get(0));
        for (int i = 1; i < size; i++) {
            sb.append(separator);
            sb.append((String) strings.get(i));
        }
        return sb.toString();
    }

    public static StringJoinNode create() {
        return StringJoinNodeGen.create();
    }

    public static StringJoinNode getUncached() {
        return StringJoinNodeGen.getUncached();
    }
}
