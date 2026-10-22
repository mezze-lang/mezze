package com.mezze.interpreter.comptime

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.codebase.CodebaseHostAdapter
import com.mezze.language.codebase.CodebaseQueries
import com.mezze.language.codebase.indexSource
import com.mezze.language.comptime.Comptime
import com.mezze.language.comptime.CodebaseHost
import com.mezze.language.comptime.ComptimeReduction
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * `std::codebase`, end to end: a program asks the compiler about its own code
 * from a `comptime` block, the answer comes from the store through the real
 * host, and the program runs with that answer already in it.
 *
 * Every answer crosses three representations — `CodebaseQueries` in Kotlin, the
 * `HostValue` the adapter encodes, the Mezze types in `std/codebase.mz` — and a
 * mismatch between the last two is invisible until a Mezze program pattern
 * matches on it. These tests are those programs.
 */
class CodebaseEffectTest {

    private val declarations = """
        ## The app under test.
        use std::comptime::{ Comptime, ComptimeEval }
        use std::codebase::{ Codebase, ComptimeEvalCodebase, EvalCodebase, FixtureCodebase, DeclRef, Definition, RecordDef, TypeShape, ByName, query, empty_fixture }
        use std::syntax::{ TFn, TInt, LetDecl, LetSyntax, LambdaExpr, MemberExpr, NamedParams }
        use std::collections::seq
        use std::effects::log::{ println, Log }
        use std::effects::noop::{ noop }

        ## Something to total.
        type Ticket = { n: Int, label: Str }

        ## Says it.
        pub let shout: { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }
        let twice: { msg: Str } -> {} <Log> = { msg } -> do
          shout { msg = msg }
          shout { msg = msg }
        pub let size: { t: Ticket } -> Int = { t } -> t.n
    """.trimIndent()

    /** Fold with a host over [source]'s own index, then run the folded program. */
    private fun run(
        source: String,
        root: java.nio.file.Path = Paths.get("."),
        host: (InMemoryMezzeStore) -> CodebaseHost? = { CodebaseHostAdapter(CodebaseQueries(it)) },
    ): Any? {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        val store = InMemoryMezzeStore()
        indexSource(store, source, module = "app", root = root)

        com.mezze.language.typechecker.ModuleResolver.clearCache()
        val parsed = MezzeParser().parseParsedModule(source, "app.mz")
        val checked = TypeChecker().typeCheck(parsed, root, true, null)
        Comptime.install(TruffleComptimeEvaluator())
        val folded = ComptimeReduction.reduceOrThrow(checked.loweredFunctions, checked.performerRecords, host(store))

        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine).allowAllAccess(true).allowExperimentalOptions(true).build()
        try {
            MezzeLanguage.clearLastResult()
            MezzeLanguage.setFunctionsForDirectExecution(folded, checked.performerRecords, Paths.get("."))
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<codebase>").buildLiteral())
            return MezzeLanguage.getLastExecutionResult()
        } finally {
            try { context.close(true) } catch (ignored: Exception) {}
            try { engine.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * [asked] as the body of a function, under `ComptimeEvalCodebase` — and nothing
     * else: the `perform` of a compile-time handler IS the compile-time site, so
     * no `Comptime.comptime` goes around it.
     */
    private fun program(asked: String): String {
        val body = asked.trimIndent().lines().joinToString("\n  ")
        return "$declarations\n\nlet answer = {} -> perform ComptimeEvalCodebase in $body\nanswer {}"
    }

    /**
     * Metadata is open: any key, any shape. A program asks for a declaration's
     * `#{ … }` as JSON and decodes it into a type it declares itself — the same
     * decoder it would use for any JSON. Nothing about `route` or `auth` is known
     * to the compiler.
     */
    @Test
    @Timeout(240)
    fun `a declaration's metadata decodes into the program's own type`() {
        val out = run("""
            use std::codebase::{ Codebase, ComptimeEvalCodebase }
            use std::codec::json::{ Json, FromJson, json_default, parse_str }
            use std::codec::core::{ DecodeError }
            use std::collections::seq

            type Auth = { role: Str }
            type Route = { route: Str, methods: [Str], auth: Auth }

            #{ route: "/tickets", methods: ["GET", "POST"], auth: { role: "admin" } }
            pub let tickets = {} -> 1

            let as_route: { raw: Str } -> Str = { raw } -> match parse_str { s = raw } is
              Ok { val = j } -> do
                let jt: Json = j
                let decoded: Result Route DecodeError = Route.from_json { it = jt }
                match decoded is
                  Ok { val = r } -> "$(r.route) for $(r.auth.role), $(seq::size { xs = r.methods }.to_str {}) methods"
                  Err { error } -> "decode failed: $(error.message)"
              Err { error = _ } -> "not JSON: $(raw)"

            let answer = {} -> perform ComptimeEvalCodebase in match Codebase.resolve { module = "app", name = "tickets" } is
              Some { val } -> match Codebase.info { decl = val } is
                Some { val = info } -> match info.metadata is
                  Some { val = raw } -> as_route { raw = raw }
                  None -> "no metadata"
                None -> "no info"
              None -> "unresolved"
            answer {}
        """.trimIndent())
        assertEquals("/tickets for admin, 2 methods", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a program lists its own module's declarations`() {
        val out = run(program("""seq::map { xs = Codebase.decls { module = "app" }, f = { x } -> x.name }"""))
        assertEquals("[Ticket, answer, shout, size, twice]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a name resolves to a declaration and its docs come back`() {
        val out = run(program("""
            match Codebase.resolve { module = "app", name = "shout" } is
              Some { val } -> match Codebase.info { decl = val } is
                Some { val = info } -> match info.doc is
                  Some { val = doc } -> doc
                  None -> "no doc"
                None -> "no info"
              None -> "unresolved"
        """.trimIndent()))
        assertEquals("Says it.", out.toString().trim())
    }

    @Test
    @Timeout(240)
    fun `a type's definition comes back as data a program can match`() {
        val out = run(program("""
            match Codebase.resolve { module = "app", name = "Ticket" } is
              Some { val } -> match Codebase.definition { decl = val } is
                Some { val = RecordDef { fields, ... } } -> seq::map { xs = fields, f = { x } -> x.name }
                _ -> ["not a record"]
              None -> ["unresolved"]
        """.trimIndent()))
        assertEquals("[n, label]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a signature is a type a program can take apart`() {
        val out = run(program("""
            match Codebase.resolve { module = "app", name = "size" } is
              Some { val } -> match Codebase.signature { decl = val } is
                Some { val = TFn { result = TInt, ... } } -> "returns Int"
                Some { val } -> "some other type"
                None -> "no signature"
              None -> "unresolved"
        """.trimIndent()))
        assertEquals("returns Int", out.toString())
    }

    @Test
    @Timeout(240)
    fun `callers follow the source, not the inlined code`() {
        val out = run(program("""
            match Codebase.resolve { module = "app", name = "shout" } is
              Some { val } -> seq::map { xs = Codebase.callers { decl = val }, f = { x } -> x.name }
              None -> ["unresolved"]
        """.trimIndent()))
        assertEquals("[twice]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `with no codebase to ask, the question fails instead of answering nothing`() {
        val e = assertThrows<ComptimeReduction.ComptimeFailure> {
            run(program("""Codebase.decls { module = "app" }"""), host = { null })
        }
        assertTrue(e.message.orEmpty().contains("no codebase"), "got: ${e.message}")
    }

    // ── no wrapper: a `perform` of a compile-time handler IS the question ────

    private fun bare(program: String) = "$declarations\n\n${program.trimIndent()}"

    @Test
    @Timeout(240)
    fun `a perform of ComptimeEvalCodebase folds by itself inside a function`() {
        val out = run(bare("""
            let answer = {} -> perform ComptimeEvalCodebase in seq::map { xs = Codebase.decls { module = "app" }, f = { x } -> x.name }
            answer {}
        """))
        assertEquals("[Ticket, answer, shout, size, twice]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `ComptimeEval around EvalCodebase is the same question, written out`() {
        val out = run(bare("""
            let answer = {} -> perform ComptimeEval in perform EvalCodebase in seq::map { xs = Codebase.decls { module = "app" }, f = { x } -> x.name }
            answer {}
        """))
        assertEquals("[Ticket, answer, shout, size, twice]", out.toString())
    }

    private fun rejected(program: String): String = try {
        run(bare(program))
        throw AssertionError("expected a compile error, but this compiled:\n$program")
    } catch (e: AssertionError) {
        throw e
    } catch (e: Throwable) {
        e.message.orEmpty()
    }

    @Test
    @Timeout(240)
    fun `a question at the top level is refused like any perform there`() {
        // No exemption for compile-time questions: a top-level `perform` produces a
        // value at module load, and the top level is for definitions. Ask inside a
        // function.
        val message = rejected("""
            let names = perform ComptimeEvalCodebase in seq::map { xs = Codebase.decls { module = "app" }, f = { x } -> x.name }
            names
        """)
        assertTrue(message.contains("module-load time"), "got: $message")
    }

    @Test
    @Timeout(240)
    fun `a question that reads a run-time value is a compile error naming it`() {
        val message = rejected("""
            let ask = { m: Str } -> perform ComptimeEvalCodebase in Codebase.decls { module = m }
            ask { m = "app" }
        """)
        assertTrue(message.contains("depends on `m`"), "got: $message")
    }

    @Test
    @Timeout(240)
    fun `EvalCodebase leaves Comptime for one consent outside, and is still answered at build`() {
        // The question's row keeps `<Comptime>`, as a `comptime` block's does, so
        // `ask` is a compile-time function its caller consents to once.
        val out = run(bare("""
            let ask = {} -> perform EvalCodebase in seq::map { xs = Codebase.decls { module = "app" }, f = { x } -> x.name }
            perform ComptimeEval in ask {}
        """))
        assertEquals("[Ticket, ask, shout, size, twice]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a codebase question composes with other comptime work under one consent`() {
        val out = run(bare("""
            let both = {} -> perform ComptimeEval in do
              let names = perform EvalCodebase in seq::map { xs = Codebase.decls { module = "app" }, f = { x } -> x.name }
              let answer = Comptime.comptime { eval = {} -> do
                noop {}
                40 + 2
              }
              seq::size { xs = names } * 100 + answer
            both {}
        """))
        assertEquals(542L, out)
    }

    @Test
    @Timeout(240)
    fun `a question beside run-time work, only the question runs at build`() {
        // The smallest closed expression around the question is the question: the
        // run-time work beside it stays where it is.
        val out = run(bare("""
            let ask = {} -> perform ComptimeEvalCodebase in do
              noop {}
              seq::size { xs = Codebase.decls { module = "app" } }
            ask {}
        """))
        assertEquals(5L, out)
    }

    // ── handlers that wrap a question are compile-time too ───────────────────

    @Test
    @Timeout(240)
    fun `a handler declared beside its use, wrapping a question, folds where it is performed`() {
        val out = run(bare("""
            effect Names where { names: { module: Str } -> [Str] <Names> }
            impl Names for CodebaseNames where {
              names = { module } -> seq::map { xs = perform ComptimeEvalCodebase in Codebase.decls { module = module }, f = { x } -> x.name }
            }
            let answer = {} -> perform CodebaseNames in Names.names { module = "app" }
            answer {}
        """))
        assertEquals("[CodebaseNames, Names, Ticket, answer, shout, size, twice]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a handler from another module, wrapping a question, folds where it is performed`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        java.io.File(dir.toFile(), "src").mkdirs()
        java.io.File(dir.toFile(), "src/names.mz").writeText("""
            use std::codebase::{ Codebase, ComptimeEvalCodebase }
            use std::collections::seq

            effect Names where { names: { module: Str } -> [Str] <Names> }
            impl Names for CodebaseNames where {
              names = { module } -> seq::map { xs = perform ComptimeEvalCodebase in Codebase.decls { module = module }, f = { x } -> x.name }
            }
        """.trimIndent())
        val out = run(bare("""
            use names::{ Names, CodebaseNames }
            let answer = {} -> perform CodebaseNames in Names.names { module = "app" }
            answer {}
        """), root = dir)
        assertEquals("[Ticket, answer, shout, size, twice]", out.toString())
    }

    // ── ref, abilities_of, search, and a fixture ─────────────────────────────

    @Test
    @Timeout(240)
    fun `a function value names its declaration`() {
        val out = run(bare("""
            let answer = {} -> perform ComptimeEvalCodebase in match Codebase.ref { of = shout } is
              Some { val } -> val.name
              None -> "not a declaration"
            answer {}
        """))
        assertEquals("shout", out.toString())
    }

    @Test
    @Timeout(240)
    fun `the abilities of a type, from its declaration`() {
        val out = run(bare("""
            ability Total for 'a where { total: { it: 'a } -> Int }
            impl Total for Ticket where { total = { it } -> it.n }
            let answer = {} -> perform ComptimeEvalCodebase in match Codebase.resolve { module = "app", name = "Ticket" } is
              Some { val } -> seq::map { xs = Codebase.abilities_of { of = val }, f = { x } -> match x.ability_decl is
                Some { val = a } -> a.name
                None -> "?" }
              None -> ["unresolved"]
            answer {}
        """))
        assertEquals("[Total]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a search is built with the query methods`() {
        val out = run(bare("""
            let answer = {} -> perform ComptimeEvalCodebase in seq::map {
              xs = Codebase.search { q = query {}.with_kind { kind = LetDecl }.in_module { prefix = "app" }.with_name_prefix { prefix = "s" }.order_by { order = ByName } },
              f = { x } -> x.name }
            answer {}
        """))
        assertEquals("[shout, size]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `FixtureCodebase answers from data, at run time, with no compiler`() {
        // Not a compile-time handler: its operations perform no `Comptime`, so this
        // runs as ordinary code — which is the point of a fixture.
        val out = run(bare("""
            let fake = {
              ...empty_fixture,
              decls = [
                { decl = { hash = "h1", name = "alpha", module = "lib", kind = LetDecl }, is_public = True, doc = None,
                  span = { file = None, start_line = 1, start_col = 1, end_line = 1, end_col = 1 }, metadata = None, origin = None },
                { decl = { hash = "h2", name = "beta", module = "lib", kind = LetDecl }, is_public = False, doc = None,
                  span = { file = None, start_line = 2, start_col = 1, end_line = 2, end_col = 1 }, metadata = None, origin = None },
              ],
            }
            let answer = {} -> perform FixtureCodebase { data = fake } in seq::map {
              xs = Codebase.search { q = query {}.only_public {} }, f = { x } -> x.name }
            answer {}
        """))
        assertEquals("[alpha]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a declaration's syntax is a tree a program can walk`() {
        val out = run(bare("""
            let answer = {} -> perform ComptimeEvalCodebase in match Codebase.resolve { module = "app", name = "size" } is
              Some { val } -> match Codebase.syntax { decl = val } is
                Some { val = written } -> match written.decl is
                  LetSyntax { value = LambdaExpr { params = NamedParams { names, ... }, body = MemberExpr { member, ... }, ... }, ... } ->
                    "$(member) of $(seq::size { xs = names }.to_str {})"
                  _ -> "another shape"
                None -> "no syntax"
              None -> "unresolved"
            answer {}
        """))
        assertEquals("n of 1", out.toString())
    }

    /**
     * The use a linter makes of it: every expression of a module, as written,
     * searched for an operator the way it was spelled.
     */
    @Test
    @Timeout(240)
    fun `a linter finds every == written in a module`() {
        val out = run(bare("""
            let same = { a: Int, b: Int } -> a == b
            let again = { s: Str } -> if s == "x" then 1 else 2
            let count = {} -> perform ComptimeEvalCodebase in seq::size { xs = seq::filter {
              xs = seq::flat_map { xs = Codebase.module_syntax { module = "app" }, f = { x } -> all_exprs { decl = x } },
              p = { x } -> match x is
                BinaryExpr { op, ... } -> op == "=="
                _ -> False } }
            count {}
        """.trimIndent().let { "use std::syntax::{ all_exprs, BinaryExpr }\n$it" }))
        // `same`, `again`, and the `op == "=="` in the filter itself.
        assertEquals(3L, out)
    }

    private val everyKind = listOf(
        "IntExpr", "NatExpr", "FloatExpr", "FracExpr", "StrExpr", "CharExpr", "NameExpr", "LambdaExpr",
        "CallExpr", "BinaryExpr", "PipeExpr", "PlaceholderExpr", "RecordExpr", "MemberExpr", "VariantExpr",
        "NewtypeExpr", "IfExpr", "MatchExpr", "DoExpr", "SeqExpr", "PerformExpr", "EffectCallExpr", "ParensExpr",
    ).joinToString(", ") { "\"$it\"" }

    /**
     * Two implementations of "where is this kind of node written" must agree: the
     * compiler's index (Kotlin, at indexing) and `std::syntax`'s `node_info`
     * (Mezze, which `FixtureCodebase` answers from). Fed the module's own syntax
     * tree, the fixture must answer exactly what the index does, for every kind.
     */
    @Test
    @Timeout(240)
    fun `the occurrence index and std syntax agree on every kind`() {
        val out = run(bare("""
            use std::syntax::{ SyntaxDecl }
            use std::codebase::{ Occurrence }
            use std::int

            let kinds = [$everyKind]
            let label = { o: Occurrence } -> "$(o.kind) $(match o.symbol is
              Some { val } -> val
              None -> "-") $(int::to_str { n = o.span.start_line })"
            newtype Score = Int
            let piped = {} -> [1n, 2n] |> seq::size { xs = @ }
            let checks = { a: Int, b: Int } -> if (a == b) && a > 3 then Score.(a) else Score.(b)
            let indexed = {} -> perform ComptimeEvalCodebase in seq::flat_map { xs = kinds, f = { x } ->
              seq::map { xs = Codebase.occurrences { kind = x, symbol = None }, f = { x } -> label { o = x } } }
            let written = {} -> perform ComptimeEvalCodebase in Codebase.module_syntax { module = "app" }
            let from_syntax = {} -> do
              let data = { ...empty_fixture, syntax = written {} }
              perform FixtureCodebase { data = data } in seq::flat_map { xs = kinds, f = { x } ->
                seq::map { xs = Codebase.occurrences { kind = x, symbol = None }, f = { x } -> label { o = x } } }
            let agree = {} -> seq::sort { xs = indexed {} } == seq::sort { xs = from_syntax {} }
            agree {}
        """))
        assertEquals(true, out)
    }

    @Test
    @Timeout(240)
    fun `a search finds the declarations that use an operator as written`() {
        val out = run(bare("""
            let same = { a: Int, b: Int } -> a == b
            let names = {} -> perform ComptimeEvalCodebase in
              seq::map { xs = Codebase.search { q = query {}.using { kind = "BinaryExpr", symbol = "==" } }, f = { x } -> x.name }
            names {}
        """))
        assertEquals("[same]", out.toString())
    }

    @Test
    @Timeout(240)
    fun `a module's own documentation is its leading block`() {
        val out = run(program("""match Codebase.module_doc { module = "app" } is
              Some { val } -> val
              None -> "no doc" """))
        assertEquals("The app under test.", out.toString())
    }
}
