package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.abs

/**
 * numpy end-to-end demo — Phase 2 acceptance criterion.
 *
 * The whole point: prove that a Mezze program can drive numpy the
 * same way Python does, and get correct numeric answers back through
 * the InteropLibrary surface — no numpy-specific glue in the runtime.
 *
 * numpy is expected in a GraalPy venv under
 * `interpreter/build/graalpy-venv/venv/lib/python3.12/site-packages/`
 * (see `examples/polyglot_numpy/README.md` for setup instructions —
 * GraalPy 25.0.3 currently CANNOT build numpy from source, so a
 * prebuilt patched wheel is required). Each test probes
 * `importlib.util.find_spec('numpy')` before running — if numpy is
 * absent we skip via JUnit Assumptions rather than failing. These
 * are NOT @Disabled — they run when numpy is available, skip cleanly
 * when it isn't. `MezzeTestHelper` auto-wires `python.PythonPath`
 * onto that venv path when the directory exists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumpyInteropTest {

    private var numpyAvailable: Boolean = false

    @BeforeAll
    fun probeNumpy() {
        val probe = MezzeTestHelper.runWithPythonIsolate(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "__import__('importlib.util').util.find_spec('numpy') is not None" }
              match r is
                Ok { val = f } -> f.as_bool{}
                Err { error = _ } -> Ok { val = False }
            """.trimIndent()
        )
        numpyAvailable = if (probe is MezzeVariant && probe.isOk) {
            probe.getPayloadFieldByName("val") as? Boolean ?: false
        } else false
        if (!numpyAvailable) {
            println("[NumpyInteropTest] numpy not available — tests will be skipped. " +
                "See examples/polyglot_numpy/README.md for venv setup.")
        }
    }

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        assumeTrue(numpyAvailable, "numpy not installed in GraalPy venv")
    }

    // Each numpy test loads a fresh GraalPy context via
    // `runWithPythonIsolate` — GraalPy's `python.IsolateNativeModules`
    // is enabled so `_multiarray_umath.so` can be loaded from more
    // than one Context in the same JVM. Uses a dedicated Engine (not
    // the shared one) so the isolate's classloader can't leak into
    // Mezze-bytecode reflection lookups in other tests.
    private fun eval(source: String): Any = MezzeTestHelper.runWithPythonIsolate(source)

    private fun assertOk(result: Any?): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}, payload=${v.payload}")
        return v
    }

    // ==================== Import + top-level ====================

    @Test
    fun `import numpy returns Ok PyForeign`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in Python.import { module = "numpy" }
            """.trimIndent()
        )
        assertOk(result)
    }

    @Test
    fun `numpy has_member array`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let mod_r = Python.import { module = "numpy" }
              match mod_r is
                Ok { val = np } -> np.has_member { name = "array" }
                Err { error = _ } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    // ==================== Array construction + reductions ====================

    @Test
    fun `numpy array mean of 1 2 3 is 2`() {
        // numpy `.mean()` returns a numpy.float64 scalar, which
        // InteropLibrary.asDouble() does NOT accept — wrap in Python
        // float(...) so a native Python float crosses the boundary.
        // Same pattern the sum/std tests below already use.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "float(__import__('numpy').array([1.0, 2.0, 3.0]).mean())" }
              match r is
                Ok { val = m } -> m.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2.0, v.getPayloadFieldByName("val") as Double, 1e-9)
    }

    @Test
    fun `numpy array sum of 1 2 3 is 6`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "float(__import__('numpy').array([1.0, 2.0, 3.0]).sum())" }
              match r is
                Ok { val = m } -> m.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(6.0, v.getPayloadFieldByName("val") as Double, 1e-9)
    }

    @Test
    fun `numpy array std of 1 2 3 approximately 0_8165`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "float(__import__('numpy').array([1.0, 2.0, 3.0]).std())" }
              match r is
                Ok { val = m } -> m.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val std = v.getPayloadFieldByName("val") as Double
        // population std of [1,2,3] = sqrt(2/3) ~ 0.8165
        assertTrue(abs(std - 0.8164965809277260) < 1e-9, "std=$std")
    }

    // ==================== Constructors ====================

    @Test
    fun `numpy zeros of size 5 returns 5-element array of 0`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "__import__('numpy').zeros(5)" }
              match r is
                Ok { val = arr } ->
                  match arr.array_size{} is
                    Ok { val = n } -> n
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertEquals(5L, result)
    }

    @Test
    fun `numpy arange 10 first and last elements`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "list(map(int, __import__('numpy').arange(10)))" }
              match r is
                Ok { val = lst } ->
                  match lst.array_at { index = 0 } is
                    Ok { val = first } ->
                      match first.as_int{} is
                        Ok { val = f } ->
                          match lst.array_at { index = 9 } is
                            Ok { val = last } -> last.as_int{}
                            Err { error } -> Err { error = error }
                        Err { error } -> Err { error = error }
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(9L, v.getPayloadFieldByName("val"))
    }

    // ==================== Method invoke via PyForeign ====================

    @Test
    fun `numpy array mean via method on module`() {
        // Import numpy, get .array via attr, call it with a python
        // list, then invoke .mean() — end-to-end via the PyForeign
        // surface only. The `.mean()` result is a numpy.float64
        // scalar; chain `.item()` on the Foreign surface to get a
        // native Python float back before `.as_float{}`.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let mod_r = Python.import { module = "numpy" }
              let lst_r = Python.eval { source = "[10.0, 20.0, 30.0, 40.0]" }
              match mod_r is
                Ok { val = np } ->
                  match np.attr { name = "array" } is
                    Ok { val = ctor } ->
                      match lst_r is
                        Ok { val = lst } ->
                          match ctor.call { args = [lst] } is
                            Ok { val = arr } ->
                              match arr.method { name = "mean", args = [] } is
                                Ok { val = m } ->
                                  match m.method { name = "item", args = [] } is
                                    Ok { val = p } -> p.as_float{}
                                    Err { error } -> Err { error = error }
                                Err { error } -> Err { error = error }
                            Err { error } -> Err { error = error }
                        Err { error } -> Err { error = error }
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(25.0, v.getPayloadFieldByName("val") as Double, 1e-9)
    }

    // ==================== Element-wise ====================

    @Test
    fun `numpy elementwise add`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "float((__import__('numpy').array([1.0, 2.0, 3.0]) + __import__('numpy').array([10.0, 20.0, 30.0])).sum())" }
              match r is
                Ok { val = m } -> m.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(66.0, v.getPayloadFieldByName("val") as Double, 1e-9)
    }

    @Test
    fun `numpy dot product`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "float(__import__('numpy').dot([1.0, 2.0, 3.0], [4.0, 5.0, 6.0]))" }
              match r is
                Ok { val = m } -> m.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        // 1*4 + 2*5 + 3*6 = 32
        assertEquals(32.0, v.getPayloadFieldByName("val") as Double, 1e-9)
    }
}
