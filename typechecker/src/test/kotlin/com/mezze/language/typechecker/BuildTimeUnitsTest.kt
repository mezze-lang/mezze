package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedBuildTime
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * What runs at build is the smallest closed expression around a question whose
 * `Comptime` `ComptimeEval` answers — and the typed tree says so without being
 * rewritten: no `Comptime.comptime` is fabricated around a question, and no
 * handler the user did not write is inserted.
 */
class BuildTimeUnitsTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private val head = """
        use std::comptime::{ Comptime, ComptimeEval }
        use std::codebase::{ Codebase, ComptimeEvalCodebase, EvalCodebase, DeclRef }
        use std::effects::log::{ println, Console }
        use std::collections::seq
        use std::effects::noop::{ noop }
    """.trimIndent()

    private fun decls(body: String): Map<String, TypedLetDecl> {
        val parsed = MezzeParser().parseParsedModule("$head\n\n${body.trimIndent()}", "app.mz")
        val result = TypeChecker().checkParsedModule(parsed, Paths.get("."), enablePrelude = true)
        val ok = result as? TypeChecker.TypeCheckResult.Success ?: error("did not type-check: $result")
        return ok.typedModule!!.decls.filterIsInstance<TypedLetDecl>().associateBy { it.name }
    }

    private fun units(e: TypedExpr): List<TypedBuildTime> {
        val out = mutableListOf<TypedBuildTime>()
        fun walk(x: TypedExpr) {
            if (x is TypedBuildTime) out += x
            x.forEachChild { walk(it) }
        }
        walk(e)
        return out
    }

    private fun comptimeOps(e: TypedExpr): Int {
        var n = 0
        fun walk(x: TypedExpr) {
            if (x is TypedPerformOp && x.operationName == "comptime") n++
            x.forEachChild { walk(it) }
        }
        walk(e)
        return n
    }

    @Test
    fun `a closed question is its own unit, under the handler that answers it`() {
        val d = decls("""
            let names = {} -> perform ComptimeEvalCodebase in seq::size { xs = Codebase.decls { module = "app" } }
        """)
        val unit = units(d.getValue("names").value).single()
        assertTrue(unit.expr is TypedPerformOp, "the question itself, not the code around it: ${unit.expr}")
        assertEquals(listOf("ComptimeEvalCodebase"), unit.handlers.map { it.handler.performerName })
        assertEquals(0, comptimeOps(d.getValue("names").value), "no `Comptime.comptime` is fabricated")
    }

    @Test
    fun `a chain is one unit - the match that makes it closed`() {
        val d = decls("""
            let doc = {} -> perform ComptimeEvalCodebase in match Codebase.resolve { module = "app", name = "doc" } is
              Some { val } -> match Codebase.info { decl = val } is
                Some { val = info } -> info.is_public
                None -> False
              None -> False
        """)
        val unit = units(d.getValue("doc").value).single()
        assertTrue(unit.expr is TypedMatch, "the whole chain: ${unit.expr::class.simpleName}")
        assertTrue(units(unit.expr).isEmpty(), "nothing inside it is a unit of its own")
    }

    @Test
    fun `a question in a lambda grows to the call that consumes it`() {
        val d = decls("""
            let docs = {} -> perform ComptimeEvalCodebase in seq::map {
              xs = Codebase.decls { module = "app" },
              f = { x } -> match Codebase.info { decl = x } is
                Some { val } -> val.is_public
                None -> False }
        """)
        val unit = units(d.getValue("docs").value).single()
        assertTrue(unit.expr is TypedCall, "the map call: ${unit.expr::class.simpleName}")
    }

    @Test
    fun `written out, the answer and the translator are the handlers, in order`() {
        val d = decls("""
            let names = {} -> perform ComptimeEval in perform EvalCodebase in Codebase.decls { module = "app" }
        """)
        val unit = units(d.getValue("names").value).single()
        assertEquals(listOf("ComptimeEval", "EvalCodebase"), unit.handlers.map { it.handler.performerName })
    }

    @Test
    fun `with no answer, the function keeps Comptime in its type and nothing is folded there`() {
        val d = decls("""
            let ask = {} -> perform EvalCodebase in Codebase.decls { module = "app" }
            let main = {} -> perform Console in perform ComptimeEval in do
              println { msg = "before" }
              println { msg = seq::size { xs = ask {} }.to_str {} }
        """)
        val ask = d.getValue("ask")
        assertTrue(units(ask.value).isEmpty(), "nothing in `ask` runs at build")
        val row = (ask.scheme.type as MezzeType.FunctionType).effects
        assertTrue(row?.allEffectNames?.contains("Comptime") == true, "`ask` says it needs Comptime: $row")

        val unit = units(d.getValue("main").value).single()
        val call = unit.expr as TypedCall
        assertEquals("ask", (call.function as TypedVar).name, "only `ask {}` runs at build; the printing does not")
        assertEquals(listOf("ComptimeEval"), unit.handlers.map { it.handler.performerName })
    }

    @Test
    fun `asking with no ComptimeEval anywhere shows Comptime all the way to main`() {
        // `EvalCodebase` raises `Comptime` from every operation. Imported, that was
        // lost at the module boundary: `main` came out `<>`, compiled, and failed at
        // run time with "no codebase". Now it carries `<Comptime>`, which the entry
        // rule refuses — the consent has to be written.
        val d = decls("""
            let ask = {} -> perform EvalCodebase in Codebase.decls { module = "app" }
            let main = {} -> perform Console in println { msg = seq::size { xs = ask {} }.to_str {} }
        """)
        val row = (d.getValue("main").scheme.type as MezzeType.FunctionType).effects
        assertTrue(row?.allEffectNames?.contains("Comptime") == true, "main: $row")
        assertTrue(units(d.getValue("main").value).isEmpty(), "nothing answers it, so nothing is folded")
    }

    @Test
    fun `an explicit comptime block is folded where written, as before`() {
        val d = decls("""
            let table = {} -> perform ComptimeEval in Comptime.comptime { eval = {} -> do
              noop {}
              40 + 2
            }
        """)
        assertTrue(units(d.getValue("table").value).isEmpty())
        assertEquals(1, comptimeOps(d.getValue("table").value))
    }

    @Test
    fun `a question that reads a run-time value names it`() {
        val e = assertThrows<TypeCheckError> {
            decls("""
                let ask = { m: Str } -> perform ComptimeEvalCodebase in Codebase.decls { module = m }
            """)
        }
        assertTrue(e.message.orEmpty().contains("depends on `m`"), e.message)
    }
}
