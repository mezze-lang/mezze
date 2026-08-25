package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Unary transform stage: MAP or FILTER over a source descriptor. Kept as a
 * single class rather than two subclasses so pattern-matching in the fold
 * sink can switch on a small {@code byte kind} instead of doing multiple
 * instanceof checks in sequence.
 *
 * <p>The {@code fn} field is typed as {@code Object} because MezzeFunction
 * lives in the interpreter module and datastructures can't see it (same
 * split as MezzeSeq's Java HOFs — the strongly-typed callable is bound at
 * the native-handler layer, one level up). Callers cast on the way out.
 */
public final class UnaryOpIterNode extends IterNode {
    public static final byte KIND_MAP = 0;
    public static final byte KIND_FILTER = 1;

    @CompilationFinal
    public final byte kind;
    @CompilationFinal
    public final IterNode src;
    @CompilationFinal
    public final Object fn;

    public UnaryOpIterNode(byte kind, IterNode src, Object fn) {
        this.kind = kind;
        this.src = src;
        this.fn = fn;
    }

    public boolean isMap() { return kind == KIND_MAP; }
    public boolean isFilter() { return kind == KIND_FILTER; }
}
