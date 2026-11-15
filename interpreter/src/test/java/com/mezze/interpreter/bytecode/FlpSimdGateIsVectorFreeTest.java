package com.mezze.interpreter.bytecode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The SIMD availability gate must name no Vector API type — asserted against the
 * COMPILED CLASS, because that is the thing the JVM links.
 *
 * <p>Reading {@link FlpSimd#AVAILABLE} links {@code FlpSimd}, and linking verifies
 * its methods. So a {@code jdk.incubator.vector} type anywhere in a signature
 * there is resolved BEFORE the guard can be consulted, and a runtime without the
 * module dies at the gate that exists to protect it:
 *
 * <pre>
 * $ java -jar app.jar                    # no --add-modules
 * Error: java.lang.NoClassDefFoundError: jdk/incubator/vector/Vector
 * </pre>
 *
 * MEASURED both ways on a `range.map{}.fold{}` program: that error before the
 * split, `20000100000` after it.
 *
 * <p>WHY THE CLASS FILE AND NOT A BEHAVIOUR TEST: the suite runs WITH the module
 * on the path, so every behavioural check passes either way — the defect is
 * invisible to a test that can use the API. Only a jar launched without
 * {@code --add-modules} shows it, and that is an integration run. The constant
 * pool answers the same question in milliseconds: a class that never mentions the
 * package cannot resolve it.
 *
 * <p>The internal form is slash-separated, so the probe's own
 * {@code Class.forName("jdk.incubator.vector.LongVector")} string literal — which
 * is DOT-separated and must stay — cannot satisfy this search.
 */
class FlpSimdGateIsVectorFreeTest {

    private static String constantPoolText(Class<?> c) throws IOException {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("no class file for " + c.getName() + " at " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void theGateClassNeverMentionsTheVectorPackage() throws IOException {
        String bytes = constantPoolText(FlpSimd.class);
        assertFalse(
            bytes.contains("jdk/incubator/vector"),
            "FlpSimd names a jdk.incubator.vector type, so reading FlpSimd.AVAILABLE will "
                + "link that type and a runtime without the module dies instead of taking the "
                + "scalar loop. Move the member to FlpSimdKernels — the gate must stay free of "
                + "the API it gates.");
    }

    /**
     * The SHARED probe is the one class every gate reads, so it carries the same
     * obligation — and more strictly, because `FlpSimd` now delegates to it and
     * `SeqNatives` gates its SIMD-node calls on it.
     */
    @Test
    void theSharedProbeNeverMentionsTheVectorPackage() throws IOException {
        String bytes = constantPoolText(com.mezze.datastructures.util.VectorApi.class);
        assertFalse(
            bytes.contains("jdk/incubator/vector"),
            "VectorApi names a jdk.incubator.vector type. Reading VectorApi.AVAILABLE links it, "
                + "so that type is resolved before any gate can consult the flag — and every gate "
                + "in the tree reads this one flag. The probe must stay free of the API it probes.");
    }

    @Test
    void theKernelsClassIsWhereTheVectorTypesLive() throws IOException {
        String bytes = constantPoolText(FlpSimdKernels.class);
        assertTrue(
            bytes.contains("jdk/incubator/vector"),
            "THE CONTROL. If the kernels stop mentioning the Vector API then the search string "
                + "above has gone stale (a package rename, say) and the first assertion is "
                + "passing for the wrong reason — it would pass against an empty file.");
    }
}
