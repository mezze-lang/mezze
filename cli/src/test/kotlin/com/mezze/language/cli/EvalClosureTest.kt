package com.mezze.language.cli

import com.mezze.language.cli.commands.EvalClosure
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.PrintType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Compiling one expression against a module.
 *
 * The claim being tested is not "it compiles" but "it produces the exact pair the
 * runtime's EVAL accepts, with a shape that describes the result". So every test
 * here decodes the metadata the same way the runtime does, rather than inspecting
 * intermediate state — a closure that looks right and decodes to the wrong shape
 * would render a value incorrectly and say nothing.
 *
 * The expression's scope comes from appending a binding to the module's own source,
 * which is why several of these check that the module's declarations and imports
 * really are visible: if they were not, the approach would be wrong rather than
 * merely incomplete.
 */
class EvalClosureTest {

    private fun project(dir: Path, moduleSource: String): Pair<File, File> {
        val root = dir.toFile()
        val src = File(root, "src").apply { mkdirs() }
        val module = File(src, "app.mz").apply { writeText(moduleSource) }
        return root to module
    }

    private fun compile(dir: Path, moduleSource: String, expression: String): EvalClosure.Result {
        val (root, module) = project(dir, moduleSource)
        return EvalClosure.compile(root, module, expression)
    }

    private fun compiled(dir: Path, moduleSource: String, expression: String): EvalClosure.Result.Compiled =
        when (val r = compile(dir, moduleSource, expression)) {
            is EvalClosure.Result.Compiled -> r
            is EvalClosure.Result.Failed -> error("compile failed: ${r.message}")
        }

    /** Decode the sidecar exactly as the runtime does. */
    private fun meta(c: EvalClosure.Result.Compiled): StoreSerialization.DeserializedEmbeddedAppMeta =
        StoreSerialization.deserializeEmbeddedAppMeta(c.metaBytes)

    private val emptyModule = "let main: { x: Int } -> Int = { x } -> x\n"

    // ---- the pair the runtime expects --------------------------------------

    @Test
    fun `an expression compiles to a closure whose metadata names its entry`(@TempDir dir: Path) {
        val c = compiled(dir, emptyModule, "1 + 2")
        val m = meta(c)

        // Entry identity is a content hash, never a name — the name is display only.
        assertTrue(m.entryHash.isNotEmpty(), "no entry hash, so the runtime cannot select the entry")
        assertEquals(EvalClosure.BINDING, m.entryName)
        assertTrue(
            StoreSerialization.deserializeFunctionIRList(c.fnsBytes).any { it.hash.hex == m.entryHash },
            "the named entry hash is not in the closure",
        )
    }

    @Test
    fun `the metadata carries the result shape, which is what EVAL refuses without`(@TempDir dir: Path) {
        val c = compiled(dir, emptyModule, "1 + 2")
        assertEquals(PrintType.Scalar(PrintType.ScalarKind.Int), meta(c).resultPrintType)
    }

    @Test
    fun `a record expression gets a record shape with its fields`(@TempDir dir: Path) {
        val c = compiled(dir, emptyModule, "{ a = 1, b = 2 }")
        val shape = meta(c).resultPrintType as PrintType.Record
        assertEquals(listOf("a", "b"), shape.fields.map { it.name })
    }

    @Test
    fun `a collection expression gets a collection shape`(@TempDir dir: Path) {
        val c = compiled(dir, emptyModule, "[1, 2, 3]")
        val shape = meta(c).resultPrintType
        assertTrue(shape is PrintType.MzSeq, "expected a seq shape, got $shape")
        assertEquals(PrintType.Scalar(PrintType.ScalarKind.Int), (shape as PrintType.MzSeq).inner)
    }

    @Test
    fun `a string expression gets a Str shape, not a Char one`(@TempDir dir: Path) {
        // The distinction a runtime value cannot make: a one-character Str and a
        // Char are the same object at runtime.
        val c = compiled(dir, emptyModule, "\"x\"")
        assertEquals(PrintType.Scalar(PrintType.ScalarKind.Str), meta(c).resultPrintType)
    }

    // ---- the module's scope really is in scope ------------------------------

    @Test
    fun `the expression can call a function declared in the module`(@TempDir dir: Path) {
        // If this failed, appending a binding to the module source would be the
        // wrong approach rather than an incomplete one.
        val module = "let double: { x: Int } -> Int = { x } -> x * 2\n"
        val c = compiled(dir, module, "double { x = 21 }")
        assertEquals(PrintType.Scalar(PrintType.ScalarKind.Int), meta(c).resultPrintType)
    }

    @Test
    fun `the expression sees a type declared in the module`(@TempDir dir: Path) {
        val module = """
            newtype Money = Int
            let main: { x: Int } -> Int = { x } -> x
        """.trimIndent()
        val c = compiled(dir, module, "Money.(5)")
        val shape = meta(c).resultPrintType
        assertTrue(shape is PrintType.Newtype, "expected a newtype shape, got $shape")
        assertEquals("Money", (shape as PrintType.Newtype).name)
        // And the shape resolves through the newtype, which is exactly what a
        // runtime value cannot do for itself: Money.(5) IS 5 at runtime.
        assertEquals(PrintType.Scalar(PrintType.ScalarKind.Int), shape.inner)
    }

    @Test
    fun `the expression sees a variant declared in the module`(@TempDir dir: Path) {
        val module = """
            type Colour = | Red, Green |
            let main: { x: Int } -> Int = { x } -> x
        """.trimIndent()
        val c = compiled(dir, module, "Red")
        val shape = meta(c).resultPrintType
        assertTrue(shape is PrintType.Variant, "expected a variant shape, got $shape")
        assertEquals(
            listOf("Red", "Green"),
            (shape as PrintType.Variant).arms.map { it.constructor },
        )
    }

    @Test
    fun `the expression sees what the module imported`(@TempDir dir: Path) {
        val module = """
            use std::collections::seq
            let main: { x: Int } -> Int = { x } -> x
        """.trimIndent()
        val c = compiled(dir, module, "[1, 2].size {}")
        assertNotNull(meta(c).resultPrintType)
    }

    // ---- the module keeps its identity --------------------------------------

    @Test
    fun `changing the expression re-hashes only the expression and the synthetic entry`(@TempDir dir: Path) {
        // Why eval is cheap on a warm runtime: FunctionIRs are content-addressed,
        // so the code the USER wrote keeps its hash and the runtime's caches still
        // hit for all of it.
        //
        // Two functions do change, not one: the expression's own binding, and the
        // synthetic `__entry__` wrapper, whose body is the module's top-level lets
        // and therefore changes whenever any of them does. Named explicitly rather
        // than bounded by a count, so a third one appearing is a failure rather
        // than a loosened threshold.
        val module = "let double: { x: Int } -> Int = { x } -> x * 2\n"
        val a = StoreSerialization.deserializeFunctionIRList(compiled(dir, module, "1").fnsBytes)
        val b = StoreSerialization.deserializeFunctionIRList(compiled(dir, module, "2").fnsBytes)

        val byHashA = a.associate { it.hash.hex to it.name }
        val byHashB = b.associate { it.hash.hex to it.name }
        assertEquals(
            setOf(EvalClosure.BINDING, "__entry__"),
            (byHashA.keys - byHashB.keys).map { byHashA.getValue(it) }.toSet(),
            "a different set of functions was re-hashed than expected",
        )

        val double = a.single { it.name == "double" }
        assertEquals(
            double.hash.hex,
            b.single { it.name == "double" }.hash.hex,
            "the user's own function was re-hashed, so a warm runtime would recompile it",
        )
    }

    @Test
    fun `two different expressions produce two different entries`(@TempDir dir: Path) {
        // The other half: the expression itself must NOT be cached as the same
        // thing, or the second request would answer with the first one's value.
        val one = meta(compiled(dir, emptyModule, "1")).entryHash
        val two = meta(compiled(dir, emptyModule, "2")).entryHash
        assertTrue(one != two, "both expressions hashed to $one")
    }

    // ---- refusals ----------------------------------------------------------

    @Test
    fun `an expression that does not typecheck is refused with a compiler diagnostic`(@TempDir dir: Path) {
        // A type error at a prompt never becomes a protocol message: the client
        // compiles, so it reports this itself.
        val r = compile(dir, emptyModule, "1 + \"not a number\"")
        val failed = r as? EvalClosure.Result.Failed ?: error("expected a refusal, got $r")
        assertTrue(failed.message.isNotBlank(), "a refusal with no message tells the user nothing")
    }

    @Test
    fun `an unparseable expression is refused`(@TempDir dir: Path) {
        val r = compile(dir, emptyModule, "1 +")
        assertTrue(r is EvalClosure.Result.Failed, "expected a refusal, got $r")
    }

    @Test
    fun `an unknown name is refused`(@TempDir dir: Path) {
        val r = compile(dir, emptyModule, "no_such_function { x = 1 }")
        assertTrue(r is EvalClosure.Result.Failed, "expected a refusal, got $r")
    }

    @Test
    fun `an empty expression is refused`(@TempDir dir: Path) {
        val r = compile(dir, emptyModule, "   ")
        val failed = r as? EvalClosure.Result.Failed ?: error("expected a refusal, got $r")
        assertTrue("nothing to evaluate" in failed.message, failed.message)
    }

    @Test
    fun `a module that cannot be read is refused by name`(@TempDir dir: Path) {
        val r = EvalClosure.compile(dir.toFile(), File(dir.toFile(), "missing.mz"), "1")
        val failed = r as? EvalClosure.Result.Failed ?: error("expected a refusal, got $r")
        assertTrue("missing.mz" in failed.message, failed.message)
    }

    @Test
    fun `a module with a parse error of its own is refused, not silently ignored`(@TempDir dir: Path) {
        // The error belongs to the file, not the expression, and must not be
        // reported as though the user's expression caused it.
        val r = compile(dir, "let broken = = =\n", "1")
        assertTrue(r is EvalClosure.Result.Failed, "expected a refusal, got $r")
    }

    // ---- what a caller shows -----------------------------------------------

    @Test
    fun `the result type is reported for a caller that shows it`(@TempDir dir: Path) {
        val c = compiled(dir, emptyModule, "1 + 2")
        assertTrue(c.resultType.isNotBlank(), "no rendered result type")
    }

    @Test
    fun `writing the closure produces two files a request can name`(@TempDir dir: Path) {
        val c = compiled(dir, emptyModule, "1 + 2")
        val (fns, meta) = EvalClosure.writeTo(File(dir.toFile(), "run"), c)
        assertTrue(fns.isFile && fns.length() > 0, "fns not written")
        assertTrue(meta.isFile && meta.length() > 0, "meta not written")
        // Round-trips through disk with its shape intact, which is the hop the
        // runtime actually performs.
        assertEquals(
            c.resultPrintType,
            StoreSerialization.deserializeEmbeddedAppMeta(meta.readBytes()).resultPrintType,
        )
    }

    @Test
    fun `an ordinary build still carries no result shape`(@TempDir dir: Path) {
        // The field is new; every non-eval closure must be unaffected.
        val bytes = StoreSerialization.serializeEmbeddedAppMeta(
            StoreSerialization.EmbeddedAppMeta(
                appName = "app", entryHash = "ab".repeat(32), entryName = "main",
                performerRecords = emptyList(),
            )
        )
        assertNull(StoreSerialization.deserializeEmbeddedAppMeta(bytes).resultPrintType)
    }
}
