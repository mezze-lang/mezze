package com.mezze.interpreter.nodes.builtin;

/**
 * Field-name arrays shared by native builtins that build small records.
 *
 * <p>Centralising these prevents the silent-divergence trap where the
 * same conceptual shape gets two different constants
 * ({@code ABC_FIELDS} vs {@code A_B_C_FIELDS}) and the mismatch only
 * shows up when a record field lookup misses at runtime. Lookups in
 * {@code MezzeRecord} match by reference where possible, so reusing the
 * same array also lets the runtime hit its identity fast path.
 */
public final class BuiltinFieldNames {

    private BuiltinFieldNames() {}

    /** Single-field record: {@code {x}}. Callbacks of shape {@code {x} -> _}. */
    public static final String[] X = new String[]{"x"};

    /** Record produced by binary join/both ops: {@code {l, r}}. */
    public static final String[] LEFT_RIGHT = new String[]{"l", "r"};

    /** Record produced by ternary join/both3 ops: {@code {a, b, c}}. */
    public static final String[] ABC = new String[]{"a", "b", "c"};

    /** Record produced by partition ops: {@code {pass, fail}}. */
    public static final String[] PASS_FAIL = new String[]{"pass", "fail"};

    /** Single-field record passed to atom/agent transformers: {@code {current}}. */
    public static final String[] CURRENT = new String[]{"current"};
}
