package com.mezze.interpreter.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Mutable string wrapper for safe local mutation.
 *
 * This is the runtime representation of MutString - a mutable string builder.
 * Useful for building strings incrementally.
 */
public final class MutableString {
    private StringBuilder value;

    public MutableString(String initial) {
        this.value = new StringBuilder(initial);
    }

    public MutableString(int capacity) {
        this.value = new StringBuilder(capacity);
    }

    /** Factory behind {@link TruffleBoundary} for use from JIT-compiled code. */
    @TruffleBoundary
    public static MutableString createBoundary(String initial) {
        return new MutableString(initial);
    }

    @TruffleBoundary
    public String get() {
        return value.toString();
    }

    @TruffleBoundary
    public void set(String newValue) {
        this.value = new StringBuilder(newValue);
    }

    @TruffleBoundary
    public int length() {
        return value.length();
    }

    @TruffleBoundary
    public boolean isEmpty() {
        return value.length() == 0;
    }

    @TruffleBoundary
    public void append(String s) {
        value.append(s);
    }

    @TruffleBoundary
    public void appendChar(char c) {
        value.append(c);
    }

    @TruffleBoundary
    public void prepend(String s) {
        value.insert(0, s);
    }

    @TruffleBoundary
    public void insert(int index, String s) {
        if (index >= 0 && index <= value.length()) {
            value.insert(index, s);
        }
    }

    @TruffleBoundary
    public void delete(int start, int end) {
        if (start >= 0 && end <= value.length() && start <= end) {
            value.delete(start, end);
        }
    }

    @TruffleBoundary
    public void clear() {
        value.setLength(0);
    }

    @TruffleBoundary
    public void reverse() {
        value.reverse();
    }

    @TruffleBoundary
    public char charAt(int index) {
        if (index >= 0 && index < value.length()) {
            return value.charAt(index);
        }
        return '\0';
    }

    @TruffleBoundary
    public void setCharAt(int index, char c) {
        if (index >= 0 && index < value.length()) {
            value.setCharAt(index, c);
        }
    }

    @TruffleBoundary
    public String substring(int start, int end) {
        if (start >= 0 && end <= value.length() && start <= end) {
            return value.substring(start, end);
        }
        return "";
    }

    @TruffleBoundary
    public void replace(int start, int end, String replacement) {
        if (start >= 0 && end <= value.length() && start <= end) {
            value.replace(start, end, replacement);
        }
    }
}
