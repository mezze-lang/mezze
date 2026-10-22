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
        use std::macro::{ MacroInput, Expansion, parse_expr, parse_decls, expand_to, declare, reject, tokens }
        use std::syntax::{ Expr, StrExpr, SyntaxDecl, Decl, TypeDefSyntax, DeclsCode }
        use std::collections::seq

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

        ## `with_count!(type T = { … })` is the type, and `field_count`, the number of its fields.
        pub let with_count: { input: MacroInput } -> Expansion = { input } ->
          match parse_decls { input = input } is
            Ok { val } -> do
              let n = seq::size { xs = seq::flat_map { xs = val, f = { x } -> fields { d = x } } }
              let count = IntExpr { value = n, span = input.span, facts = None }
              declare { decls = seq::concat { xs = val, ys = `(let field_count = ~count) } }
            Err { error } -> { code = DeclsCode { decls = [] }, errors = [{ message = error.message, span = error.span, hints = [] }] }

        let fields: { d: SyntaxDecl } -> [Str] = { d } -> match d.decl is
          TypeSyntax { def, ... } -> match def is
            RecordDefSyntax { fields, ... } -> seq::map { xs = fields, f = { x } -> x.name }
            _ -> []
          _ -> []

        ## Used by expansions, from the macro's own module.
        pub let helper: { n: Int } -> Int = { n } -> n * 10
        let hidden: { n: Int } -> Int = { n } -> n

        ## `tenfold!(e)` is `helper { n = e }`: `helper` is this module's.
        pub let tenfold: { input: MacroInput } -> Expansion = { input } ->
          match parse_expr { input = input } is
            Ok { val } -> expand_to { e = `(helper { n = ~val }) }
            Err { error } -> reject { e = `(0), message = error.message, span = error.span }

        ## `pair_size!(e)` counts `[e, e]` through this module's `seq`.
        pub let pair_size: { input: MacroInput } -> Expansion = { input } ->
          match parse_expr { input = input } is
            Ok { val } -> expand_to { e = `(seq::size { xs = [~val, ~val] }) }
            Err { error } -> reject { e = `(0), message = error.message, span = error.span }

        ## Expands to a call of a function the caller cannot reach.
        pub let leaky: { input: MacroInput } -> Expansion = { input } ->
          match parse_expr { input = input } is
            Ok { val } -> expand_to { e = `(hidden { n = ~val }) }
            Err { error } -> reject { e = `(0), message = error.message, span = error.span }

        ## Binds `helper` itself: that one is local, not this module's.
        pub let local_helper: { input: MacroInput } -> Expansion = { input } ->
          match parse_expr { input = input } is
            Ok { val } -> expand_to { e = `(do
              let helper = 1
              helper + ~val - ~val) }
            Err { error } -> reject { e = `(0), message = error.message, span = error.span }

        ## Expands to a mistake in a call of this module's `seq`.
        pub let bad_map: { input: MacroInput } -> Expansion = { input } ->
          expand_to { e = `(seq::map { xs = [1], f = { y } -> y }) }

        ## `count_names!( … )` is how many identifiers its input has.
        pub let count_names: { input: MacroInput } -> Expansion = { input } -> do
          let names = seq::filter { xs = tokens { input = input }, p = { x } -> x.kind == "identifier" }
          expand_to { e = IntExpr { value = seq::size { xs = names }, span = input.span, facts = None } }

        ## Never finishes.
        pub let spin: { input: MacroInput } -> Expansion = { input } -> forever { n = 0 }
        let forever: { n: Int } -> Expansion = { n } -> forever { n = n + 1 }

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

            let r = text!(<b>it's "quoted" (and nested)</b>)
            r
        """.trimIndent())
        assertEquals("<b>it's \"quoted\" (and nested)</b>", out)
    }

    @Test
    @Timeout(240)
    fun `a qualified call reaches the macro through its module`() {
        val out = run("""
            use macros

            let r = macros::double!(5)
            r
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

            let r = double!(1 +)
            r
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
    fun `a macro standing as a declaration declares what it returns`() {
        val out = run("""
            use macros::{ with_count }

            with_count!(type Point = { x: Int, y: Int, z: Int })

            let p: Point = { x = 1, y = 2, z = 3 }
            p.y + field_count
        """.trimIndent())
        assertEquals(5L, out)
    }

    @Test
    @Timeout(240)
    fun `a declaration macro that returns an expression is refused`() {
        val e = errors("""
            use macros::{ double }

            double!(1)
            let main = {} -> 1
        """.trimIndent())
        assertTrue(e.any { it.code == ErrorCode.V1003_MACRO_FAILED && "stands as a declaration" in it.message }, e.map { it.message }.toString())
    }

    @Test
    @Timeout(240)
    fun `names a macro writes mean what they mean where it is written`() {
        // Neither `helper` nor `seq` is imported here.
        val out = run("""
            use macros::{ tenfold, pair_size }

            let a = tenfold!(4)
            let b = pair_size!(1)
            a + b
        """.trimIndent())
        assertEquals(42L, out)
    }

    @Test
    @Timeout(240)
    fun `a caller's own name does not capture the macro's`() {
        val out = run("""
            use macros::{ tenfold }

            let helper: { n: Int } -> Int = { n } -> n + 1000
            let a = tenfold!(4)
            a
        """.trimIndent())
        assertEquals(40L, out)
    }

    @Test
    @Timeout(240)
    fun `a name the expansion binds itself stays local`() {
        val out = run("""
            use macros::{ local_helper }

            let a = local_helper!(9)
            a
        """.trimIndent())
        assertEquals(1L, out)
    }

    @Test
    @Timeout(240)
    fun `an expansion using a private name says so`() {
        val e = errors("""
            use macros::{ leaky }

            let a = leaky!(1)
            a
        """.trimIndent()).single()
        assertEquals(ErrorCode.V1003_MACRO_FAILED, e.code)
        assertTrue("`hidden`" in e.message && "private" in e.message, e.message)
    }

    @Test
    @Timeout(240)
    fun `a diagnostic in an expansion names things as the macro wrote them`() {
        val e = errors("""
            use macros::{ bad_map }

            let a = bad_map!()
            a
        """.trimIndent())
        val text = e.joinToString("\n") { it.message + " " + it.hints.joinToString(" ") }
        assertTrue("std::collections::seq::map" in text, text)
        assertTrue('~' !in text, text)
    }

    /**
     * A file in a syntax of its own — `key = value` lines — read by a frontend
     * written in Mezze, chosen by `Mezze.toml`, and imported like any module.
     */
    @Test
    @Timeout(240)
    fun `a file frontend turns a file in its own syntax into a module`() {
        Files.writeString(root.resolve("Mezze.toml"), "[package]\nname = \"m\"\nversion = \"0.1.0\"\n\n[frontends]\n\"*.conf.mz\" = \"conf::parse\"\n")
        Files.writeString(root.resolve("src/conf.mz"), """
            use std::macro::{ MacroInput, Expansion, declare }
            use std::syntax::{ Span, SyntaxDecl, Expr, Pattern }
            use std::collections::seq
            use std::str

            pub let parse: { input: MacroInput } -> Expansion = { input } ->
              declare { decls = seq::flat_map { xs = str::lines { s = input.text }, f = { x } -> entry { line = x, at = input.span } } }

            let entry: { line: Str, at: Span } -> [SyntaxDecl] = { line, at } ->
              match str::split { s = line, delimiter = "=" } is
                [k, v] -> do
                  let name = VarPat { name = str::trim { s = k }, span = at }
                  let value = StrExpr { parts = [TextPart { text = str::trim { s = v } }], span = at, facts = None }
                  `(pub let ~name = ~value)
                _ -> []
        """.trimIndent())
        Files.writeString(root.resolve("src/settings.conf.mz"), "port = 8080\nhost = example.org\n")
        val out = run("""
            use settings::{ port, host }

            let both = "${'$'}(host):${'$'}(port)"
            both
        """.trimIndent())
        assertEquals("example.org:8080", out)
    }

    @Test
    @Timeout(240)
    fun `a macro reads its input as Mezze tokens`() {
        val out = run("""
            use macros::{ count_names }

            let n = count_names!(a + b * 3 - c ! "d")
            n
        """.trimIndent())
        assertEquals(3L, out)
    }

    @Test
    @Timeout(240)
    fun `a macro that never finishes is stopped at its budget`() {
        System.setProperty(TruffleComptimeEvaluator.BUDGET_PROPERTY, "1500")
        try {
            val e = errors("""
                use macros::{ spin }

                spin!(1)
            """.trimIndent()).single()
            assertEquals(ErrorCode.V1003_MACRO_FAILED, e.code)
            assertTrue("did not finish" in e.message, e.message)
        } finally {
            System.clearProperty(TruffleComptimeEvaluator.BUDGET_PROPERTY)
        }
    }

    @Test
    @Timeout(240)
    fun `an unchanged call is expanded once`() {
        val app = """
            use macros::{ double }

            let r = double!(7)
            r
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

            let r = double!(7)
            r
        """.trimIndent()
        assertEquals(14L, run(app))
        Files.writeString(root.resolve("src/macros.mz"), macros.replace("`(~val * 2)", "`(~val * 3)"))
        ModuleResolver.clearCache()
        assertEquals(21L, run(app))
    }
}
