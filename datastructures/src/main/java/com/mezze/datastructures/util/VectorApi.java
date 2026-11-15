package com.mezze.datastructures.util;

/**
 * IS THE VECTOR API USABLE? One probe, and a class that names no
 * {@code jdk.incubator.vector} type — which is the whole point.
 *
 * <p>Reading a flag LINKS the class that holds it, and linking VERIFIES its
 * methods, so a vector type anywhere in this file would be resolved before the
 * flag could be consulted. That is not hypothetical: {@code FlpSimd} held both
 * the probe and the kernels, and a jar run without the module died at the gate
 * meant to protect it —
 * {@code NoClassDefFoundError: jdk/incubator/vector/Vector} on a program as
 * small as {@code range.map{}.fold{}} (known-bugs BUG 43). KEEP THIS FILE FREE
 * OF VECTOR IMPORTS.
 *
 * <p>Also false on SVM: its JIT does not intrinsify the Vector API, so lanes run
 * as scalar code with Vector object overhead — strictly slower than the plain
 * loop. {@code SeqNatives.sum} says the same thing from measurement (Apple M2,
 * 10M longs: SIMD 53ms, scalar 7ms).
 */
public final class VectorApi {

    /** True when {@code jdk.incubator.vector} is loadable and we are not on SVM. */
    public static final boolean AVAILABLE = probe();

    private static boolean probe() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return false;
        try {
            Class.forName("jdk.incubator.vector.LongVector");
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    private VectorApi() {}
}
