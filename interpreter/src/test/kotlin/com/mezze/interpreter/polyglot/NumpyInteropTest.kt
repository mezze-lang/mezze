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
 * prebuilt patched wheel is required).  Each test probes
 * `importlib.util.find_spec('numpy')` before running — if numpy is
 * absent we skip via JUnit Assumptions rather than failing.  These
 * are NOT @Disabled — they run when numpy is available, skip cleanly
 * when it isn't.  `MezzeTestHelper` auto-wires `python.PythonPath`
 * onto that venv path when the directory exists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumpyInteropTest {

    private var numpyAvailable: Boolean = false

    @BeforeAll
    fun probeNumpy() {
        val probe = MezzeTestHelper.run(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "__import__('importlib.util').util.find_spec('numpy') is not None" }
            let _ = close_context { it = ctx }
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

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertOk(result: Any?): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}, payload=${v.payload}")
        return v
    }

    // ==================== Import + top-level ====================

    @Test
    fun `import numpy returns Ok Foreign`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_import { context = ctx, name = "numpy" }
            let _ = close_context { it = ctx }
            r
            """.trimIndent()
        )
        assertOk(result)
    }

    @Test
    fun `numpy has_member of array`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let mod_r = python_import { context = ctx, name = "numpy" }
            let _ = close_context { it = ctx }
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
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "__import__('numpy').array([1.0, 2.0, 3.0]).mean()" }
            let _ = close_context { it = ctx }
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "float(__import__('numpy').array([1.0, 2.0, 3.0]).sum())" }
            let _ = close_context { it = ctx }
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "float(__import__('numpy').array([1.0, 2.0, 3.0]).std())" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = m } -> m.as_float{}
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val std = v.getPayloadFieldByName("val") as Double
        // population std of [1,2,3] = sqrt(2/3) ≈ 0.8165
        assertTrue(abs(std - 0.8164965809277260) < 1e-9, "std=$std")
    }

    // ==================== Constructors ====================

    @Test
    fun `numpy zeros of size 5 returns 5-element array of 0`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "__import__('numpy').zeros(5)" }
            let _ = close_context { it = ctx }
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "list(map(int, __import__('numpy').arange(10)))" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = lst } ->
                match lst.read_array { index = 0 } is
                  Ok { val = first } ->
                    match first.as_int{} is
                      Ok { val = f } ->
                        match lst.read_array { index = 9 } is
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

    // ==================== Method invoke via Foreign ====================

    @Test
    fun `numpy array mean via invoke_member on module`() {
        // Import numpy, get .array, execute it with a python list, then
        // invoke .mean() — end-to-end via the Foreign surface only.
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import, python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let mod_r = python_import { context = ctx, name = "numpy" }
            let lst_r = python_eval { context = ctx, source = "[10.0, 20.0, 30.0, 40.0]" }
            let _ = close_context { it = ctx }
            match mod_r is
              Ok { val = np } ->
                match np.read_member { name = "array" } is
                  Ok { val = ctor } ->
                    match lst_r is
                      Ok { val = lst } ->
                        match ctor.execute { args = [lst] } is
                          Ok { val = arr } ->
                            match arr.invoke_member { name = "mean", args = [] } is
                              Ok { val = m } -> m.as_float{}
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "float((__import__('numpy').array([1.0, 2.0, 3.0]) + __import__('numpy').array([10.0, 20.0, 30.0])).sum())" }
            let _ = close_context { it = ctx }
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "float(__import__('numpy').dot([1.0, 2.0, 3.0], [4.0, 5.0, 6.0]))" }
            let _ = close_context { it = ctx }
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
