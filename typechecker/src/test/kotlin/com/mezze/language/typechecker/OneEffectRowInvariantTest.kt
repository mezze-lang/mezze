package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #404 (effect-model migration): a value binding carries NO effect row — effects
 * ride the enclosing arrow. The "doubled effect row" bug this class was built for
 * is gone by construction, because there is no effect row on a value to double.
 *
 * These end-to-end tests assert the surviving invariant on real programs: a
 * `let`-bound value (e.g. a `race` result, a discharged exception) prints as a
 * bare type with zero effect rows, and effects surface on the function arrow.
 */
class OneEffectRowInvariantTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ---- End to end: the race scenario ----

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

    @Test
    fun `race-result binding carries NO effect row -- effects ride the arrow`() {
        // Effect-model migration: value bindings are BARE. The doubled-row bug (#404)
        // this class was built for is gone by construction — there is no effect row on
        // a value to double. `r` is a plain `Result Str AsyncError`; race's Async rides
        // the enclosing arrow, not the binding.
        val tm = typedModuleOf(raceProgram, "race.mz")
        val rType = bindingTypeAt(tm, 10, 7) // `let r =`
        assertEquals("Result Str AsyncError", rType) { "race binding must be bare; got: $rType" }
        assertEquals(0, rType.count { it == '<' }) { "no effect row on a value binding: $rType" }
    }

    @Test
    fun `identical spawns get identical types`() {
        val tm = typedModuleOf(raceProgram, "race.mz")
        val fastType = bindingTypeAt(tm, 8, 7) // `let fast =`
        val slowType = bindingTypeAt(tm, 9, 7) // `let slow =`
        assertEquals(fastType, slowType) {
            "identical spawn expressions must get identical types (determinism); fast=$fastType slow=$slowType"
        }
        assertEquals("Promise Str", fastType) { "spawn handle should be `Promise Str`, got: $fastType" }
    }

    @Test
    fun `no binding in the race program carries a doubled effect row`() {
        val tm = typedModuleOf(raceProgram, "race.mz")
        // Sweep every binding/expr the hover path can reach across the whole
        // do-block: none may print `> <`.
        for (line in 8..10) {
            for (col in listOf(7)) {
                val t = bindingTypeAt(tm, line, col)
                assertFalse(t.contains("> <")) { "binding at $line:$col carries a doubled row: $t" }
            }
        }
    }

    // ---- Bug 2: a DISCHARGED effect binding shows a plain value (no residual row) ----
    //
    // A binding whose RHS performs `Exception` UNDER an enclosing
    // `perform ExceptionToResult` handler has that effect DISCHARGED — the handler
    // converts the throw into an `Err` value. So the binding is a plain
    // `Result Int Str`, NOT `Result Int Str <Exception>`: the effect is GONE, folded
    // into the value, exactly as an unhandled `<Async>` binding still carries `<Async>`
    // because ITS handler is at a further-out boundary. This is the principled
    // distinction — discharged does not ride, unhandled does — and it must ALSO carry
    // exactly one row (here: zero, the discharged case).
    private val dischargedProgram = """
        use std::effects::exception::{ Exception, ExceptionToResult }
        let safe_div : { a: Int, b: Int } -> Int <Exception> = { a, b } ->
          if b == 0 then Exception.throw { error = "divide by zero" } else a / b
        let main = {} -> perform ExceptionToResult in do
          let good = safe_div { a = 10, b = 2 }.ok {}
          let bad = safe_div { a = 10, b = 0 }.ok {}
          good
    """.trimIndent()

    @Test
    fun `a handler-discharged exception binding shows a plain Result with no residual effect row`() {
        val tm = typedModuleOf(dischargedProgram, "discharged.mz")
        val goodType = bindingTypeAt(tm, 5, 7)
        val badType = bindingTypeAt(tm, 6, 7)
        // The Exception is discharged by the enclosing ExceptionToResult, so no
        // residual `<Exception>` rides on the binding — it is a plain, fully-ground
        // Result. (This is the established, tested behavior — see
        // ExceptionAssocTypeInferenceTest.)
        assertEquals("Result Int Str", goodType) { "discharged binding should be a plain Result, got: $goodType" }
        assertEquals("Result Int Str", badType) { "discharged binding should be a plain Result, got: $badType" }
        // And, trivially, exactly one-or-zero rows — never a doubled `> <`.
        assertFalse(goodType.contains("> <"))
        assertFalse(badType.contains("> <"))
    }
}
