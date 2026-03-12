package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Optimized string split node.
 *
 * Splits string on literal delimiter without regex overhead.
 * Uses indexOf instead of Pattern.split for simple delimiters.
 *
 * Uses:
 * - Simple indexOf for single-char delimiters
 * - Pre-allocated ArrayList with estimated size
 * - No regex compilation for literal splits
 */
@NodeInfo(shortName = "string.split")
public abstract class StringSplitNode extends Node {

    public abstract MezzeSeq<?> execute(String s, String delimiter);

    @Specialization(guards = "s.isEmpty()")
    protected MezzeSeq<?> splitEmpty(String s, String delimiter) {
        return MezzeSeq.fromArray(new Object[] { "" });
    }

    @Specialization(guards = "delimiter.isEmpty()")
    @TruffleBoundary
    protected MezzeSeq<?> splitByEmpty(String s, String delimiter) {
        // Split into individual characters
        int len = s.length();
        Object[] chars = new Object[len];
        for (int i = 0; i < len; i++) {
            chars[i] = String.valueOf(s.charAt(i));
        }
        return MezzeSeq.fromArray(chars);
    }

    @Specialization(guards = {"!s.isEmpty()", "delimiter.length() == 1"})
    @TruffleBoundary
    protected MezzeSeq<?> splitBySingleChar(String s, String delimiter) {
        char delim = delimiter.charAt(0);
        int len = s.length();

        // Estimate capacity based on string length
        List<String> parts = new ArrayList<>(Math.min(16, len / 4 + 1));

        int start = 0;
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) == delim) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));

        return MezzeSeq.fromArray(parts.toArray());
    }

    @Specialization(guards = {"!s.isEmpty()", "!delimiter.isEmpty()", "delimiter.length() > 1"})
    @TruffleBoundary
    protected MezzeSeq<?> splitByMultiChar(String s, String delimiter) {
        int delimLen = delimiter.length();
        int len = s.length();

        // Estimate capacity
        List<String> parts = new ArrayList<>(Math.min(16, len / delimLen + 1));

        int start = 0;
        int idx;
        while ((idx = s.indexOf(delimiter, start)) != -1) {
            parts.add(s.substring(start, idx));
            start = idx + delimLen;
        }
        parts.add(s.substring(start));

        return MezzeSeq.fromArray(parts.toArray());
    }

    public static StringSplitNode create() {
        return StringSplitNodeGen.create();
    }

    public static StringSplitNode getUncached() {
        return StringSplitNodeGen.getUncached();
    }
}
