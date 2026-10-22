package com.mezze.interpreter.comptime

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.comptime.Comptime
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * `name!( … )`, end to end: a macro is an ordinary function in a module of its
 * own; the module that calls it is compiled with the macro's output in the
 * call's place, and runs.
 *
 * Each test is a project on disk — a `macros` module and an `app` that uses it —
 * because a macro comes from ANOTHER module, resolved the way imports are.
 */
class MacroExpansionTest {

    private val macros = """
        use std::macro::{ MacroInput, Expansion, parse_expr, expand_to, reject }
        use std::syntax::{ Expr, StrExpr }

        ## `double!(e)` is `e * 2`.
        pub let double: { input: MacroInput } -> Expansion = { input } ->
          match parse_expr { input = input } is
            Ok { val } -> expand_to { e = `(~val * 2) }
            Err { error } -> reject { e = `(0), message = error.message, span = error.span }

        ## `text!( … )` is its input, as a string: input need not be Mezze at all.
        pub let text: { input: MacroInput } -> Expansion = { input } ->
          expand_to { e = StrExpr { parts = [TextPart { text = input.text }], span = input.span, facts = None } }

        ## `refuse!( … )` reports its whole input as wrong.
        pub let refuse: { input: MacroInput } -> Expansion = { input } ->
          reject { e = `(0), message = "refused: ${'$'}(input.text)", span = input.span }

        ## Raises while expanding.
        pub let broken: { input: MacroInput } -> Expansion = { input } ->
          expand_to { e = crash { msg = "the macro broke" } }

        ## A function, but not a macro.
        pub let plain: { x: Int } -> Int = { x } -> x

        ## Expands to another macro's call.
        pub let quadruple: { input: MacroInput } -> Expansion = { input } ->
          match parse_expr { input = input } is
            Ok { val } -> expand_to { e = `(~val * 4) }
            Err { error } -> reject { e = `(0), message = error.message, span = error.span }
    """.trimIndent()

    private lateinit var root: Path

    @BeforeEach
    fun project() {
        ModuleResolver.clearCache()
        root = Files.createTempDirectory("macro-test")
        Files.writeString(root.resolve("Mezze.toml"), "[package]\nname = \"m\"\nversion = \"0.1.0\"\n")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/macros.mz"), macros)
        Comptime.install(TruffleComptimeEvaluator())
    }

    private fun check(app: String): TypeChecker.TypeCheckResult {
        Files.writeString(root.resolve("src/app.mz"), app)
        val parsed = MezzeParser().parseParsedModule(app, root.resolve("src/app.mz").toString())
        return TypeChecker().checkParsedModule(parsed, root.resolve("src"), "app")
    }

    private fun errors(app: String): List<TypeChecker.TypeCheckError> =
        (check(app) as? TypeChecker.TypeCheckResult.Failure)?.errors
            ?: error("expected the check to fail")

    private fun run(app: String): Any? {
        val checked = check(app) as? TypeChecker.TypeCheckResult.Success
            ?: error("check failed: ${(check(app) as TypeChecker.TypeCheckResult.Failure).errors.map { it.message }}")
        val engine = Engine.newBuilder().allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false").option("engine.Compilation", "false").build()
        val context = Context.newBuilder(MezzeLanguage.ID).engine(engine).allowAllAccess(true).allowExperimentalOptions(true).build()
        try {
            MezzeLanguage.clearLastResult()
            val fns = com.mezze.language.typechecker.IrClosure.reachable(checked.loweredFunctions.orEmpty())
            MezzeLanguage.setFunctionsForDirectExecution(fns, checked.loweredPerformers, root)
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<app>").buildLiteral())
            return MezzeLanguage.getLastExecutionResult()
        } finally {
            try { context.close(true) } catch (ignored: Exception) {}
            try { engine.close() } catch (ignored: Exception) {}
        }
    }

    @Test
    @Timeout(240)
    fun `a macro reads its input as Mezze and its expansion runs in the call's place`() {
        val out = run("""
            use macros::{ double }

            let n = 21
            let answer = double!(n + 0)
            answer
        """.trimIndent())
        assertEquals(42L, out)
    }

    @Test
    @Timeout(240)
    fun `a macro's input need not be Mezze`() {
        val out = run("""
            use macros::{ text }

            text!(<b>it's "quoted" (and nested)</b>)
        """.trimIndent())
        assertEquals("<b>it's \"quoted\" (and nested)</b>", out)
    }

    @Test
    @Timeout(240)
    fun `a qualified call reaches the macro through its module`() {
        val out = run("""
            use macros

            macros::double!(5)
        """.trimIndent())
        assertEquals(10L, out)
    }

    @Test
    @Timeout(240)
    fun `a mistake the macro reports points into its input`() {
        val errs = errors("""
            use macros::{ refuse }

            let x =
              refuse!(nope)
            x
        """.trimIndent())
        val e = errs.single()
        assertEquals(ErrorCode.V1004_MACRO_INPUT_ERROR, e.code)
        assertEquals("refused: nope", e.message)
        // `nope` is on line 4, starting at column 11: `  refuse!(` is ten characters.
        assertEquals(4, e.loc!!.startLine)
        assertEquals(11, e.loc!!.startCol)
    }

    @Test
    @Timeout(240)
    fun `input that does not parse is reported where it is wrong`() {
        val e = errors("""
            use macros::{ double }

            double!(1 +)
        """.trimIndent()).single()
        assertEquals(ErrorCode.V1004_MACRO_INPUT_ERROR, e.code)
        assertEquals(3, e.loc!!.startLine)
    }

    @Test
    @Timeout(240)
    fun `a macro nobody imported is not found`() {
        val e = errors("double!(1)").single()
        assertEquals(ErrorCode.V1001_MACRO_NOT_FOUND, e.code)
    }

    @Test
    @Timeout(240)
    fun `a function that is not a macro is refused`() {
        val e = errors("""
            use macros::{ plain }

            plain!(1)
        """.trimIndent()).single()
        assertEquals(ErrorCode.V1002_NOT_A_MACRO, e.code)
    }

    @Test
    @Timeout(240)
    fun `a macro that raises is reported at the call`() {
        val e = errors("""
            use macros::{ broken }

            broken!(1)
        """.trimIndent()).single()
        assertEquals(ErrorCode.V1003_MACRO_FAILED, e.code)
        assertTrue("the macro broke" in e.message, e.message)
        assertEquals(3, e.loc!!.startLine)
    }

    @Test
    @Timeout(240)
    fun `an unchanged call is expanded once`() {
        val app = """
            use macros::{ double }

            double!(7)
        """.trimIndent()
        assertEquals(14L, run(app))
        val before = TruffleComptimeEvaluator.invocations.get()
        ModuleResolver.clearCache()
        assertEquals(14L, run(app))
        assertEquals(before, TruffleComptimeEvaluator.invocations.get(), "the second compile reuses the expansion")
    }

    @Test
    @Timeout(240)
    fun `a changed macro expands again`() {
        val app = """
            use macros::{ double }

            double!(7)
        """.trimIndent()
        assertEquals(14L, run(app))
        Files.writeString(root.resolve("src/macros.mz"), macros.replace("`(~val * 2)", "`(~val * 3)"))
        ModuleResolver.clearCache()
        assertEquals(21L, run(app))
    }
}
