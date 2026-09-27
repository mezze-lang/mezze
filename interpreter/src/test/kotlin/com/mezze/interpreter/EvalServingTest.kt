package com.mezze.interpreter

import com.mezze.interpreter.nodes.builtin.effects.InspectTree
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.interpreter.standalone.RuntimeServer
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.PrintType
import com.mezze.wire.Eval
import com.mezze.wire.EvalError
import com.mezze.wire.EvalResult
import com.mezze.wire.MessageType
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireValue
import com.mezze.wire.toText
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Serving EVAL: the runtime turns a compiled expression into a value tree.
 *
 * Two halves, tested separately because they fail for unrelated reasons.
 *
 * The REFUSALS ([RuntimeServer.prepareEval]) are decided from the request and two
 * files — no Context, no guest thread — so they are checked directly. Each one
 * matters because the alternative to refusing is answering: a closure with no
 * result shape would render from the runtime value alone and silently lose the
 * distinctions the shape exists to carry.
 *
 * The MECHANISM is checked end to end through the same three calls the server
 * makes — register pre-lowered IR, eval, read the captured result — against real
 * Mezze compiled by the typechecker, because the claim being tested is that those
 * three produce the right tree, not that they were written down in the right
 * order.
 */
class EvalServingTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        MezzeLanguage.clearLastResult()
    }

    private val intT = PrintType.Scalar(PrintType.ScalarKind.Int)

    // ---- refusals ----------------------------------------------------------

    private fun evalFor(dir: Path, fns: ByteArray?, meta: ByteArray?): Eval {
        val fnsFile = File(dir.toFile(), "fns.cbor")
        val metaFile = File(dir.toFile(), "meta.cbor")
        fns?.let { fnsFile.writeBytes(it) }
        meta?.let { metaFile.writeBytes(it) }
        return Eval(requestId = 1, fnsCborPath = fnsFile.path, metaCborPath = metaFile.path)
    }

    private fun metaBytes(
        entryHash: String = "ab".repeat(32),
        printType: PrintType? = PrintType.Scalar(PrintType.ScalarKind.Int),
    ): ByteArray = StoreSerialization.serializeEmbeddedAppMeta(
        StoreSerialization.EmbeddedAppMeta(
            appName = "eval",
            entryHash = entryHash,
            entryName = null,
            performerRecords = emptyList(),
            resultPrintType = printType,
        )
    )

    private fun refusal(prep: RuntimeServer.EvalPrep): RuntimeServer.EvalPrep.Refused =
        prep as? RuntimeServer.EvalPrep.Refused ?: error("expected a refusal, got $prep")

    @Test
    fun `a missing closure is refused by name`(@TempDir dir: Path) {
        val r = refusal(RuntimeServer.prepareEval(evalFor(dir, fns = null, meta = null)))
        assertEquals(EvalError.Kind.NOT_FOUND, r.kind)
        assertTrue("fns.cbor" in r.message, "the error should name the file: ${r.message}")
    }

    @Test
    fun `a missing metadata sidecar is refused by name`(@TempDir dir: Path) {
        val fns = StoreSerialization.serializeFunctionIRList(emptyList())
        val r = refusal(RuntimeServer.prepareEval(evalFor(dir, fns = fns, meta = null)))
        assertEquals(EvalError.Kind.NOT_FOUND, r.kind)
        assertTrue("meta.cbor" in r.message, r.message)
    }

    @Test
    fun `undecodable CBOR is refused as a decode failure, not as a crash`(@TempDir dir: Path) {
        val r = refusal(RuntimeServer.prepareEval(
            evalFor(dir, fns = byteArrayOf(1, 2, 3, 4), meta = metaBytes())
        ))
        assertEquals(EvalError.Kind.DECODE_FAILED, r.kind)
    }

    @Test
    fun `a closure with no functions is refused`(@TempDir dir: Path) {
        val r = refusal(RuntimeServer.prepareEval(evalFor(
            dir,
            fns = StoreSerialization.serializeFunctionIRList(emptyList()),
            meta = metaBytes(),
        )))
        assertEquals(EvalError.Kind.NO_ENTRY, r.kind)
    }

    @Test
    fun `a closure with no result shape is refused rather than rendered blind`(@TempDir dir: Path) {
        // The important refusal. Rendering from the runtime value alone would
        // work — and would quietly print a Nat as an Int and a newtype as its
        // payload, with nothing to tell the caller it happened.
        val functions = compile("let v: Int = 1")
        val r = refusal(RuntimeServer.prepareEval(evalFor(
            dir,
            fns = StoreSerialization.serializeFunctionIRList(functions),
            meta = metaBytes(entryHash = functions.last().hash.hex, printType = null),
        )))
        assertEquals(EvalError.Kind.UNRENDERABLE, r.kind)
        assertTrue("resultPrintType" in r.message, r.message)
    }

    @Test
    fun `a well-formed request is accepted with its shape`(@TempDir dir: Path) {
        // The boundary: every refusal above is only meaningful if a good request
        // gets through.
        val functions = compile("let v: Int = 1")
        val prep = RuntimeServer.prepareEval(evalFor(
            dir,
            fns = StoreSerialization.serializeFunctionIRList(functions),
            meta = metaBytes(entryHash = functions.last().hash.hex),
        ))
        val ready = prep as? RuntimeServer.EvalPrep.Ready ?: error("expected Ready, got $prep")
        assertEquals(intT, ready.printType)
        assertTrue(ready.functions.isNotEmpty())
    }

    // ---- the metadata channel ----------------------------------------------

    @Test
    fun `the result shape survives the metadata round trip`() {
        // It travels in the sidecar rather than the wire message, so this is the
        // hop that has to work — and PrintType is a nested sealed hierarchy, which
        // is the part most likely to encode in only one direction.
        val shape: PrintType = PrintType.Record(listOf(
            PrintType.Field("x", PrintType.Newtype("Money", intT)),
            PrintType.Field("y", PrintType.MzMap(intT, PrintType.Scalar(PrintType.ScalarKind.Str))),
        ))
        val decoded = StoreSerialization.deserializeEmbeddedAppMeta(metaBytes(printType = shape))
        assertEquals(shape, decoded.resultPrintType)
    }

    @Test
    fun `an ordinary app carries no result shape`() {
        // Null for everything that is not an eval closure, so adding the field
        // changed nothing for the LOAD path.
        val decoded = StoreSerialization.deserializeEmbeddedAppMeta(metaBytes(printType = null))
        assertNull(decoded.resultPrintType)
    }

    // ---- the reply ---------------------------------------------------------

    @Test
    fun `the reply carries the tree, the request id and a duration`() {
        val frame = RuntimeServer.evalResultFrame(
            requestId = 42, result = 7L, printType = intT,
            maxDepth = WireProtocol.DEFAULT_EVAL_MAX_DEPTH,
            maxItems = WireProtocol.DEFAULT_EVAL_MAX_ITEMS,
            elapsedMicros = 1234,
        )
        assertEquals(MessageType.EVAL_RESULT, frame.type)
        val result = frame.decode<EvalResult>()
        assertEquals(42, result.requestId)
        assertEquals(1234L, result.elapsedMicros)
        assertNull(result.error)
        assertEquals(WireValue.Scalar("int", "7"), result.value)
    }

    @Test
    fun `the reply honours the request's limits`() {
        // Not the defaults: a caller asking for depth 1 must get depth 1, or an
        // LSP inline hint pulls a whole object graph over the wire.
        val shape = PrintType.Record(listOf(PrintType.Field(
            "inner", PrintType.Record(listOf(PrintType.Field("x", intT))),
        )))
        val value = com.mezze.interpreter.values.MezzeRecord.fromFields(
            arrayOf("inner"),
            arrayOf(com.mezze.interpreter.values.MezzeRecord.fromFields(arrayOf("x"), arrayOf(1L))),
        )
        val result = RuntimeServer.evalResultFrame(1, value, shape, 1, 100, 0).decode<EvalResult>()
        assertEquals("{ inner = … }", result.value!!.toText())
    }

    // ---- end to end through the server's own mechanism ---------------------

    /** Typecheck and lower, as the CLI would before shipping the closure. */
    private fun compile(source: String): List<FunctionIR> {
        val result = MezzeTestHelper.typeCheck(source)
        val functions = result.loweredFunctions
        assertTrue(functions != null && functions.isNotEmpty(), "nothing lowered for: $source")
        return functions!!
    }

    /**
     * The three calls [RuntimeServer.handleEval] makes around the Context:
     * register pre-lowered IR, eval, read what the top-level result capture held.
     *
     * Reproduced here rather than driving handleEval itself because handleEval
     * needs a ServerState, which needs a live server. What is being checked is
     * that these three produce the right value — the frame around them is checked
     * by the reply tests above.
     */
    private fun runClosure(source: String): Any? {
        val functions = compile(source)
        val root: Path = Paths.get(System.getProperty("mezze.project.root", "."))
        val engine = Engine.newBuilder()
            // Interpreter-only, matching MezzeTestHelper: these are correctness
            // tests, and JIT compilation only adds warmup. Both options are
            // experimental, hence the flag.
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowExperimentalOptions(true)
            .build()
        return try {
            MezzeLanguage.setFunctionsForDirectExecution(
                functions, emptyList(), root, functions.last().hash.hex,
            )
            MezzeLanguage.clearLastResult()
            context.eval(
                Source.newBuilder(MezzeLanguage.ID, "# eval", "__eval__.mz").buildLiteral()
            )
            MezzeLanguage.getLastExecutionResult()
        } finally {
            try { context.close(true) } catch (_: Exception) {}
            try { engine.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun `a compiled expression evaluates and renders as a tree`() {
        val result = runClosure("let v: Int = 6 * 7")
        assertEquals(
            WireValue.Scalar("int", "42"),
            InspectTree.build(result, intT, true, 6, 100),
            "the captured result was $result",
        )
    }

    @Test
    fun `a compiled record expression renders with its field structure`() {
        // The case that shows the tree is worth having: a string would have
        // thrown the field names away.
        val result = runClosure("let v: { x: Int, y: Int } = { x = 1, y = 2 }")
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT), PrintType.Field("y", intT)))
        val tree = InspectTree.build(result, shape, true, 6, 100) as WireValue.Record

        assertEquals(listOf("x", "y"), tree.fields.map { it.name })
        assertEquals("{ x = 1, y = 2 }", tree.toText())
    }

    @Test
    fun `a compiled collection expression renders every element`() {
        val result = runClosure("let v: [Int] = [1, 2, 3]")
        val tree = InspectTree.build(result, PrintType.MzSeq(intT), true, 6, 100)
        assertEquals("[1, 2, 3]", tree.toText())
    }

    @Test
    fun `the capture slot outlives an evaluation, which is why handleEval clears it first`() {
        // Demonstrates the HAZARD rather than asserting that a getter works. The
        // slot is process-global and nothing resets it on the way out, so an
        // expression that captured nothing would answer with the previous
        // expression's value — a wrong answer that looks entirely right.
        // handleEval calls clearLastResult before eval for exactly this reason;
        // that ordering cannot be asserted here because handleEval needs a live
        // ServerState.
        assertEquals(1L, runClosure("let v: Int = 1"))
        assertEquals(
            1L, MezzeLanguage.getLastExecutionResult(),
            "the slot emptied itself, so the clear in handleEval would be unnecessary — " +
                "if this ever fails, simplify handleEval rather than updating this test",
        )
    }

    @Test
    fun `eval is advertised as a capability`() {
        // A capability is a promise; this is the check that the server makes it.
        val advertised = RuntimeServer::class.java.getDeclaredField("ADVERTISED_CAPABILITIES")
            .apply { isAccessible = true }
            .get(RuntimeServer)
        @Suppress("UNCHECKED_CAST")
        assertTrue(
            WireProtocol.Capability.EVAL in (advertised as List<String>),
            "EVAL is served but not advertised, so no client can discover it",
        )
    }

    @Test
    fun `the tree and to_dbg agree on a real compiled value`() {
        // The agreement invariant, on a value that came out of the actual
        // pipeline rather than one built by hand in a test.
        val result = runClosure("let v: [Int] = [1, 2, 3]")
        val shape = PrintType.MzSeq(intT)
        assertEquals(
            com.mezze.interpreter.nodes.builtin.effects.InspectNatives.render(result, shape, true),
            InspectTree.build(result, shape, true, 64, 1000).toText(),
        )
        assertNotNull(result)
    }
}
