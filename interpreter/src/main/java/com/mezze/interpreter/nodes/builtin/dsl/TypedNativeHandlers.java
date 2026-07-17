package com.mezze.interpreter.nodes.builtin.dsl;

import java.util.HashMap;

/**
 * Typed-signature registry for hot native ops that BDSL can dispatch
 * without boxing. Each functional interface is a specialization keyed
 * by (arg-kinds, return-kind); a native op registers into whichever
 * table matches its signature.
 *
 * <p>The BDSL {@code CallNativeXX_Y} operations look up the typed
 * handler by name and invoke it with primitive-typed args. Truffle's
 * boxing elimination + JIT then inlines the handler body without any
 * Object[] alloc, without any {@link PositionalArgs} wrapper, and
 * without a {@link NativeHandler}.invoke interface dispatch.
 *
 * <p>Fallback: if a native op isn't registered typed, BDSL falls back
 * to the untyped {@link NativeHandlers} path with the Object[] +
 * PositionalArgs allocation cost. This is the same cost the pre-typed
 * BDSL had — no regression when the op isn't listed here.
 */
public final class TypedNativeHandlers {

    private TypedNativeHandlers() {}

    /** (long, long) -> long. */
    @FunctionalInterface
    public interface LLL {
        long invoke(long a, long b);
    }

    /** (long) -> long. */
    @FunctionalInterface
    public interface LL {
        long invoke(long a);
    }

    /** (long, long) -> boolean. Used for int comparison ops. */
    @FunctionalInterface
    public interface LLB {
        boolean invoke(long a, long b);
    }

    /** (double, double) -> double. */
    @FunctionalInterface
    public interface DDD {
        double invoke(double a, double b);
    }

    /** (double) -> double. */
    @FunctionalInterface
    public interface DD {
        double invoke(double a);
    }

    /** (double, double) -> boolean. Used for float comparisons. */
    @FunctionalInterface
    public interface DDB {
        boolean invoke(double a, double b);
    }

    /** (double) -> boolean. Used for float predicates (is_nan, is_infinite). */
    @FunctionalInterface
    public interface DB {
        boolean invoke(double a);
    }

    /** (long) -> double. Used for int→float conversions. */
    @FunctionalInterface
    public interface LD {
        double invoke(long a);
    }

    /** (double) -> long. Used for float→int conversions (Math.round). */
    @FunctionalInterface
    public interface DL {
        long invoke(double a);
    }

    /** (boolean) -> boolean. */
    @FunctionalInterface
    public interface BB {
        boolean invoke(boolean a);
    }

    /** (boolean, boolean) -> boolean. */
    @FunctionalInterface
    public interface BBB {
        boolean invoke(boolean a, boolean b);
    }

    private static final HashMap<String, LLL> lll = new HashMap<>();
    private static final HashMap<String, LL> ll = new HashMap<>();
    private static final HashMap<String, LLB> llb = new HashMap<>();
    private static final HashMap<String, DDD> ddd = new HashMap<>();
    private static final HashMap<String, DD> dd = new HashMap<>();
    private static final HashMap<String, DDB> ddb = new HashMap<>();
    private static final HashMap<String, DB> db = new HashMap<>();
    private static final HashMap<String, LD> ld = new HashMap<>();
    private static final HashMap<String, DL> dl = new HashMap<>();
    private static final HashMap<String, BB> bb = new HashMap<>();
    private static final HashMap<String, BBB> bbb = new HashMap<>();

    public static void registerLLL(String name, LLL fn) { lll.put(name, fn); }
    public static void registerLL(String name, LL fn) { ll.put(name, fn); }
    public static void registerLLB(String name, LLB fn) { llb.put(name, fn); }
    public static void registerDDD(String name, DDD fn) { ddd.put(name, fn); }
    public static void registerDD(String name, DD fn) { dd.put(name, fn); }
    public static void registerDDB(String name, DDB fn) { ddb.put(name, fn); }
    public static void registerDB(String name, DB fn) { db.put(name, fn); }
    public static void registerLD(String name, LD fn) { ld.put(name, fn); }
    public static void registerDL(String name, DL fn) { dl.put(name, fn); }
    public static void registerBB(String name, BB fn) { bb.put(name, fn); }
    public static void registerBBB(String name, BBB fn) { bbb.put(name, fn); }

    public static LLL lookupLLL(String name) { return lll.get(name); }
    public static LL lookupLL(String name) { return ll.get(name); }
    public static LLB lookupLLB(String name) { return llb.get(name); }
    public static DDD lookupDDD(String name) { return ddd.get(name); }
    public static DD lookupDD(String name) { return dd.get(name); }
    public static DDB lookupDDB(String name) { return ddb.get(name); }
    public static DB lookupDB(String name) { return db.get(name); }
    public static LD lookupLD(String name) { return ld.get(name); }
    public static DL lookupDL(String name) { return dl.get(name); }
    public static BB lookupBB(String name) { return bb.get(name); }
    public static BBB lookupBBB(String name) { return bbb.get(name); }
}
