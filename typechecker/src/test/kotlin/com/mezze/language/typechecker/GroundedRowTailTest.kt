package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #405: An unconstrained effect-row TAIL is grounded (closed) at concrete call
 * sites.
 *
 * `race : { ps: [Promise 'a] } -> Result 'a AsyncError <Async, ...>` has an
 * INTENTIONALLY open tail so it composes inside any effect context. But at a
 * concrete call site where nothing further flows into that tail, hover leaked
 * `Result Str AsyncError <Async, ...>`. The open `...` there is meaningless: no
 * caller effect can ever be threaded into a binding's own solved type. So the
 * call-result's fresh tail is defaulted to CLOSED once solving proves it
 * unconstrained — exactly as an unconstrained ordinary type-var is defaulted.
 *
 * The grounding is done at EMIT (closeGeneratedRowVars in TypedAstBuilder), the
 * same post-solve finalization that closes generated record/variant tails —
 * NOT in the printer, which must keep emitting `...` for genuinely-open tails
 * (e.g. inside `and_then`/`flat_map` bodies whose tail still carries the
 * caller's effects).
 */
class GroundedRowTailTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typedModuleOf(src: String, file: String): com.mezze.language.typedast.TypedModule {
        val parsed = parser.parseParsedModule(src, file)
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: throw AssertionError("expected typecheck success, got: $r")
        return success.typedModule ?: throw AssertionError("no typed module")
    }

    private fun bindingTypeAt(tm: com.mezze.language.typedast.TypedModule, line: Int, col: Int): String =
        when (val hit = TypedAstLookup.findAtPosition(tm, line, col)) {
            is TypedAstLookup.LookupResult.Binding -> prettyPrintType(hit.type)
            is TypedAstLookup.LookupResult.Expr -> prettyPrintType(hit.expr.type)
            else -> throw AssertionError("nothing at $line:$col: $hit")
        }

    private val raceProgram = """
use std::effects::log::{ Console, println }
use std::effects::async::{ Async, LoomAsync, sleep }
use std::effects::time::{ SystemTime }
let fetch = { url, ms } -> do
  sleep { ms }
  "${'$'}(url) replied"
let main = {} -> perform Console, LoomAsync, SystemTime in do
  let fast = Async.spawn { f = {} -> fetch { url = "eu", ms = 10 } }
  let slow = Async.spawn { f = {} -> fetch { url = "us", ms = 500 } }
  let r = [fast, slow].race {}
  match r is
    Ok { val } -> println { msg = val }
    Err { error } -> println { msg = "both failed" }
""".trimIndent()

    // Case 1: effect-model migration — at a concrete call site the binding is BARE.
    // The whole #405 "ground the value's open tail" concern dissolves: effects ride
    // the enclosing ARROW, never the value, so `r` carries no row at all (neither the
    // leaked `<Async, ...>` nor a grounded `<Async>`).
    @Test
    fun `race-result binding is bare -- no effect row rides the value`() {
        val tm = typedModuleOf(raceProgram, "race.mz")
        val rType = bindingTypeAt(tm, 10, 7) // `let r =`
        assertEquals("Result Str AsyncError", rType) {
            "a value binding must be bare — effects ride the arrow, not the value; got: $rType"
        }
        assertFalse(rType.contains("<")) { "no effect row on a value binding: $rType" }
    }

    // Case 2 + 4 (guard): a user fn ANNOTATED to return the open tail keeps
    // `...` at its DECLARATION (decl-site polymorphism preserved — the user
    // wrote `<Async, ...>` explicitly, a `userOpen` tail), AND its body's
    // `ps.race {}` call, whose result flows into that annotated open return,
    // stays open too. Grounding is a call-site defaulting that fires ONLY when
    // the fresh tail ends solving genuinely unconstrained.
    private val annotatedReexportProgram = """
use std::effects::async::{ Async, Promise, LoomAsync, race }
pub let my_race : { ps: [Promise 'a] } -> Result 'a AsyncError <Async, ...>
  = { ps } -> ps.race {}
""".trimIndent()

    @Test
    fun `a user fn annotated with an open tail keeps it open at its declaration`() {
        val tm = typedModuleOf(annotatedReexportProgram, "reexport.mz")
        // Hover the top-level `my_race` binding name.
        val declType = bindingTypeAt(tm, 2, 9) // `pub let my_race`
        assertTrue(declType.contains("...")) {
            "an explicitly annotated open tail must survive at the declaration; got: $declType"
        }
    }
}
