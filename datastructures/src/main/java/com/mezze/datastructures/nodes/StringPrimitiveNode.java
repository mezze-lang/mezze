package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Optimized string primitive operations.
 *
 * All operations compile to single instruction or small inline sequences.
 */
public final class StringPrimitiveNode {

    @NodeInfo(shortName = "string.length")
    public static abstract class LengthNode extends Node {
        public abstract int execute(String s);

        @Specialization
        protected int length(String s) {
            return s.length();
        }

        public static LengthNode create() {
            return StringPrimitiveNodeFactory.LengthNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.isEmpty")
    public static abstract class IsEmptyNode extends Node {
        public abstract boolean execute(String s);

        @Specialization
        protected boolean isEmpty(String s) {
            return s.isEmpty();
        }

        public static IsEmptyNode create() {
            return StringPrimitiveNodeFactory.IsEmptyNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.charAt")
    public static abstract class CharAtNode extends Node {
        public abstract char execute(String s, int index);

        @Specialization
        protected char charAt(String s, int index) {
            return s.charAt(index);
        }

        public static CharAtNode create() {
            return StringPrimitiveNodeFactory.CharAtNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.codePointAt")
    public static abstract class CodePointAtNode extends Node {
        public abstract int execute(String s, int index);

        @Specialization
        protected int codePointAt(String s, int index) {
            return s.codePointAt(index);
        }

        public static CodePointAtNode create() {
            return StringPrimitiveNodeFactory.CodePointAtNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.substring")
    public static abstract class SubstringNode extends Node {
        public abstract String execute(String s, int start, int end);

        @Specialization
        @TruffleBoundary
        protected String substring(String s, int start, int end) {
            return s.substring(start, end);
        }

        public static SubstringNode create() {
            return StringPrimitiveNodeFactory.SubstringNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.indexOf")
    public static abstract class IndexOfNode extends Node {
        public abstract int execute(String s, String target);

        @Specialization
        @TruffleBoundary
        protected int indexOf(String s, String target) {
            return s.indexOf(target);
        }

        public static IndexOfNode create() {
            return StringPrimitiveNodeFactory.IndexOfNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.indexOfChar")
    public static abstract class IndexOfCharNode extends Node {
        public abstract int execute(String s, char target);

        @Specialization
        @TruffleBoundary
        protected int indexOfChar(String s, char target) {
            return s.indexOf(target);
        }

        public static IndexOfCharNode create() {
            return StringPrimitiveNodeFactory.IndexOfCharNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.lastIndexOf")
    public static abstract class LastIndexOfNode extends Node {
        public abstract int execute(String s, String target);

        @Specialization
        @TruffleBoundary
        protected int lastIndexOf(String s, String target) {
            return s.lastIndexOf(target);
        }

        public static LastIndexOfNode create() {
            return StringPrimitiveNodeFactory.LastIndexOfNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.startsWith")
    public static abstract class StartsWithNode extends Node {
        public abstract boolean execute(String s, String prefix);

        @Specialization
        @TruffleBoundary
        protected boolean startsWith(String s, String prefix) {
            return s.startsWith(prefix);
        }

        public static StartsWithNode create() {
            return StringPrimitiveNodeFactory.StartsWithNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.endsWith")
    public static abstract class EndsWithNode extends Node {
        public abstract boolean execute(String s, String suffix);

        @Specialization
        @TruffleBoundary
        protected boolean endsWith(String s, String suffix) {
            return s.endsWith(suffix);
        }

        public static EndsWithNode create() {
            return StringPrimitiveNodeFactory.EndsWithNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.contains")
    public static abstract class ContainsNode extends Node {
        public abstract boolean execute(String s, String target);

        @Specialization
        @TruffleBoundary
        protected boolean contains(String s, String target) {
            return s.contains(target);
        }

        public static ContainsNode create() {
            return StringPrimitiveNodeFactory.ContainsNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.equals")
    public static abstract class EqualsNode extends Node {
        public abstract boolean execute(String s, String other);

        @Specialization
        protected boolean equals(String s, String other) {
            return s.equals(other);
        }

        public static EqualsNode create() {
            return StringPrimitiveNodeFactory.EqualsNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.equalsIgnoreCase")
    public static abstract class EqualsIgnoreCaseNode extends Node {
        public abstract boolean execute(String s, String other);

        @Specialization
        @TruffleBoundary
        protected boolean equalsIgnoreCase(String s, String other) {
            return s.equalsIgnoreCase(other);
        }

        public static EqualsIgnoreCaseNode create() {
            return StringPrimitiveNodeFactory.EqualsIgnoreCaseNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.compareTo")
    public static abstract class CompareToNode extends Node {
        public abstract int execute(String s, String other);

        @Specialization
        @TruffleBoundary
        protected int compareTo(String s, String other) {
            return s.compareTo(other);
        }

        public static CompareToNode create() {
            return StringPrimitiveNodeFactory.CompareToNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.hashCode")
    public static abstract class HashCodeNode extends Node {
        public abstract int execute(String s);

        @Specialization
        protected int hashCode(String s) {
            return s.hashCode();
        }

        public static HashCodeNode create() {
            return StringPrimitiveNodeFactory.HashCodeNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.toLowerCase")
    public static abstract class ToLowerCaseNode extends Node {
        public abstract String execute(String s);

        @Specialization
        @TruffleBoundary
        protected String toLowerCase(String s) {
            return s.toLowerCase();
        }

        public static ToLowerCaseNode create() {
            return StringPrimitiveNodeFactory.ToLowerCaseNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.toUpperCase")
    public static abstract class ToUpperCaseNode extends Node {
        public abstract String execute(String s);

        @Specialization
        @TruffleBoundary
        protected String toUpperCase(String s) {
            return s.toUpperCase();
        }

        public static ToUpperCaseNode create() {
            return StringPrimitiveNodeFactory.ToUpperCaseNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.trim")
    public static abstract class TrimNode extends Node {
        public abstract String execute(String s);

        @Specialization
        @TruffleBoundary
        protected String trim(String s) {
            return s.trim();
        }

        public static TrimNode create() {
            return StringPrimitiveNodeFactory.TrimNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.strip")
    public static abstract class StripNode extends Node {
        public abstract String execute(String s);

        @Specialization
        @TruffleBoundary
        protected String strip(String s) {
            return s.strip();
        }

        public static StripNode create() {
            return StringPrimitiveNodeFactory.StripNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.replace")
    public static abstract class ReplaceNode extends Node {
        public abstract String execute(String s, String target, String replacement);

        @Specialization
        @TruffleBoundary
        protected String replace(String s, String target, String replacement) {
            return s.replace(target, replacement);
        }

        public static ReplaceNode create() {
            return StringPrimitiveNodeFactory.ReplaceNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.concat")
    public static abstract class ConcatNode extends Node {
        public abstract String execute(String s, String other);

        @Specialization
        protected String concat(String s, String other) {
            return s.concat(other);
        }

        public static ConcatNode create() {
            return StringPrimitiveNodeFactory.ConcatNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.repeat")
    public static abstract class RepeatNode extends Node {
        public abstract String execute(String s, int count);

        @Specialization
        @TruffleBoundary
        protected String repeat(String s, int count) {
            return s.repeat(count);
        }

        public static RepeatNode create() {
            return StringPrimitiveNodeFactory.RepeatNodeGen.create();
        }
    }

    @NodeInfo(shortName = "string.isBlank")
    public static abstract class IsBlankNode extends Node {
        public abstract boolean execute(String s);

        @Specialization
        @TruffleBoundary
        protected boolean isBlank(String s) {
            return s.isBlank();
        }

        public static IsBlankNode create() {
            return StringPrimitiveNodeFactory.IsBlankNodeGen.create();
        }
    }

    private StringPrimitiveNode() {
        // Utility class
    }
}
