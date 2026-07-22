package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper

/**
 * Differential-testing harness for Path C: runs a Mezze program through
 * the default pipeline and through the CPS-lowered pipeline and asserts
 * identical output.
 *
 * Cf. [BdslDifferentialHarness]. This harness is smaller because CPS
 * lowering is a pure IR-in / IR-out transform gated at a single seam
 * (TypedASTCompiler runs CpsLowering on every FunctionIR when
 * MEZZE_CPS=1). We toggle the gate via a Kotlin-level override rather
 * than trying to mutate the process env.
 *
 * ## Contract
 *
 * Until Phase 1.4 lands a real CPS rewrite, the transform is identity —
 * default vs CPS-on results are trivially equal. Once the transform
 * starts rewriting, this harness catches divergence immediately.
 *
 * ## Lifetime
 *
 * Test-only. Deleted at Phase 3.2 alongside the Loom continuation code
 * once CPS is the only path.
 */
object CpsDifferentialHarness {

    fun assertEquivalent(source: String, expected: Any?) {
        val default = MezzeTestHelper.run(source)
        assertEqualsValue("default pipeline", expected, default)

        val cps = withCps { MezzeTestHelper.run(source) }
        assertEqualsValue("CPS pipeline", expected, cps)
        assertEqualsValue("default vs CPS", default, cps)
    }

    /**
     * Force MEZZE_CPS lowering on for the body's duration by setting a
     * JVM system property that TypedASTCompiler will also honour. The
     * pipeline check in TypedASTCompiler prefers the property when set.
     */
    private inline fun <T> withCps(body: () -> T): T {
        val prev = System.getProperty("mezze.cps")
        System.setProperty("mezze.cps", "1")
        try { return body() } finally {
            if (prev == null) System.clearProperty("mezze.cps") else System.setProperty("mezze.cps", prev)
        }
    }

    private fun assertEqualsValue(context: String, expected: Any?, actual: Any?) {
        val e = normalize(expected)
        val a = normalize(actual)
        if (e != a) {
            throw AssertionError(
                "$context: expected <$expected> (${expected?.javaClass?.simpleName}), " +
                "got <$actual> (${actual?.javaClass?.simpleName})"
            )
        }
    }

    private fun normalize(v: Any?): Any? = when (v) {
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        else -> v
    }
}
