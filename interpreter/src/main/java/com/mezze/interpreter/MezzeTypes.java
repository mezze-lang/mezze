package com.mezze.interpreter;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.mezze.interpreter.values.MezzeNull;
import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * Mezze type system for Truffle DSL.
 *
 * Types are ordered by frequency - primitives first for fast-path specializations.
 * The Truffle DSL uses this ordering to generate efficient type checks.
 */
@TypeSystem({
    // Primitives FIRST - these are the fast path
    long.class,      // Int - most common
    double.class,    // Float
    boolean.class,   // Bool

    // Reference types
    String.class,         // String
    MezzeRecord.class,    // Records
    MezzeVariant.class,   // Variants (Option, Result, etc.)
    MezzeFunction.class,  // Functions
    MezzeNull.class       // Null (for uninitialized)

    // Note: Object.class is NOT included - Truffle doesn't allow it
    // All unrecognized types fall through to Object automatically
})
public abstract class MezzeTypes {

    /**
     * Implicit cast from long to double.
     * Allows Int values to be used where Float is expected.
     */
    @ImplicitCast
    public static double castLongToDouble(long value) {
        return (double) value;
    }

    /**
     * Check if a value is Mezze null (uninitialized slot).
     */
    public static boolean isNull(Object value) {
        return value == null || value instanceof MezzeNull;
    }
}
