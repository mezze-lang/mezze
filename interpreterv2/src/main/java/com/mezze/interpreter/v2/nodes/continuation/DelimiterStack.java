package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeContinuation;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local delimiter stack for continuation scoping.
 *
 * Each thread has its own stack of active delimiters.
 * When a delimiter is activated, it's pushed onto the stack.
 * When execution exits the scope, it's popped.
 *
 * This enables proper scoping of effect handlers - inner handlers
 * take precedence over outer handlers.
 */
public final class DelimiterStack {

    /**
     * Entry in the delimiter stack, containing both the delimiter
     * and any captured continuation at that point.
     */
    public static final class DelimiterEntry {
        public final MezzeCont delimiter;
        public MezzeContinuation capturedContinuation;
        public Object[] capturedLocals;

        DelimiterEntry(MezzeCont delimiter) {
            this.delimiter = delimiter;
            this.capturedContinuation = null;
            this.capturedLocals = null;
        }
    }

    // Thread-local stack of delimiters
    private static final ThreadLocal<Deque<DelimiterEntry>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    // Thread-local flag for nested fork detection
    private static final ThreadLocal<Boolean> INSIDE_FORK =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Push a delimiter onto the thread-local stack.
     */
    @TruffleBoundary
    public static void push(MezzeCont delimiter) {
        STACK.get().push(new DelimiterEntry(delimiter));
    }

    /**
     * Pop the top delimiter from the stack.
     */
    @TruffleBoundary
    public static DelimiterEntry pop() {
        Deque<DelimiterEntry> stack = STACK.get();
        if (stack.isEmpty()) {
            return null;
        }
        return stack.pop();
    }

    /**
     * Get the current (topmost) delimiter.
     */
    @TruffleBoundary
    public static DelimiterEntry current() {
        Deque<DelimiterEntry> stack = STACK.get();
        if (stack.isEmpty()) {
            return null;
        }
        return stack.peek();
    }

    /**
     * Find a delimiter entry by ID.
     */
    @TruffleBoundary
    public static DelimiterEntry find(long delimiterId) {
        for (DelimiterEntry entry : STACK.get()) {
            if (entry.delimiter.getId() == delimiterId) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Get the stack depth.
     */
    @TruffleBoundary
    public static int depth() {
        return STACK.get().size();
    }

    /**
     * Check if currently inside a forked continuation.
     */
    @TruffleBoundary
    public static boolean isInsideFork() {
        return INSIDE_FORK.get();
    }

    /**
     * Set the inside-fork flag.
     */
    @TruffleBoundary
    public static void setInsideFork(boolean value) {
        INSIDE_FORK.set(value);
    }

    /**
     * Clear the entire stack (for cleanup/testing).
     */
    @TruffleBoundary
    public static void clear() {
        STACK.get().clear();
        INSIDE_FORK.set(false);
    }
}
