package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #404: THE INVARIANT — a value type carries exactly ONE effect row.
 *
 * A `SuspendType` whose `valueType` is itself a `SuspendType` is malformed: it
 * prints as `T <row2> <row1>` (two rows on one value). It arose when a call whose
 * callee return is already effectful (e.g. `race : ... Result 'a AsyncError
 * <Async, ...>`) was wrapped AGAIN with the call-site effect row, or when a type
 * var inside a SuspendType was substituted with another SuspendType.
 *
 * The fix: `SuspendType.of` is the sole well-formed constructor — it merges the
 * two rows into one and never nests; substitution and call-result wrapping funnel
 * through it; and the printer defends with `flattenSuspend` so it can never emit
 * `<row> <row>` even for a legacy/cached nested type.
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

    // ---- Unit: the smart constructor never nests ----

    private fun row(vararg effects: String, open: Boolean = false, performed: Boolean = false) =
        MezzeType.EffectRow(
            effects = effects.toSet(),
            rowVar = if (open) com.mezze.language.ast.RowVar("r") else null,
            performed = performed,
        )

    @Test
    fun `SuspendType of flattens a nested SuspendType into one row`() {
        val inner = MezzeType.SuspendType(MezzeType.StringType, row("Async", open = true))
        val outer = MezzeType.SuspendType.of(inner, row("Async"))
        // The value is NOT itself a SuspendType — the invariant.
        assertFalse(outer.valueType is MezzeType.SuspendType) {
            "SuspendType.of must not nest; got value ${outer.valueType}"
        }
        assertEquals(MezzeType.StringType, outer.valueType)
        // Rows merged: still Async, still open (open subsumes closed).
        assertTrue(outer.effects.effects.contains("Async"))
        assertTrue(outer.effects.isOpen) { "open tail must survive the merge" }
    }

    @Test
    fun `SuspendType of merges distinct effects from both rows`() {
        val inner = MezzeType.SuspendType(MezzeType.IntType, row("Log"))
        val outer = MezzeType.SuspendType.of(inner, row("Async", performed = true))
        assertEquals(MezzeType.IntType, outer.valueType)
        assertEquals(setOf("Async", "Log"), outer.effects.effects)
        assertTrue(outer.effects.performed) { "performed must OR across rows" }
    }

    @Test
    fun `SuspendType of leaves a non-suspended value alone`() {
        val s = MezzeType.SuspendType.of(MezzeType.IntType, row("Async"))
        assertEquals(MezzeType.IntType, s.valueType)
        assertEquals(setOf("Async"), s.effects.effects)
    }

    @Test
    fun `mergeRows prefers the instance carrying associated types`() {
        val bare = MezzeType.EffectInstance("Exception", emptyMap())
        val rich = MezzeType.EffectInstance("Exception", mapOf("error" to MezzeType.StringType))
        val merged = MezzeType.SuspendType.mergeRows(
            MezzeType.EffectRow(effects = emptySet(), effectInstances = setOf(bare)),
            MezzeType.EffectRow(effects = emptySet(), effectInstances = setOf(rich)),
        )
        assertEquals(1, merged.effectInstances.size)
        assertEquals(rich, merged.effectInstances.single()) {
            "the richer instance (with 'error binding) must win the dedup"
        }
    }

    // ---- Printer: never emits a doubled row ----

    @Test
    fun `printer collapses a nested SuspendType to a single row`() {
        // Build the malformed nesting DIRECTLY (bypassing the smart ctor) to prove
        // the printer defends against legacy/cached values.
        val nested = MezzeType.SuspendType(
            valueType = MezzeType.SuspendType(MezzeType.StringType, row("Async", open = true)),
            effects = row("Async"),
        )
        val printed = prettyPrintType(nested)
        // Exactly one `<...>` group — no `> <` sequence anywhere.
        assertFalse(printed.contains("> <")) { "printer emitted a doubled effect row: $printed" }
        assertEquals(1, printed.count { it == '<' }) { "expected exactly one effect row, got: $printed" }
        // Same guarantee on the alias-aware printer.
        val printedAlias = prettyPrintTypeWithAliases(nested)
        assertFalse(printedAlias.contains("> <")) { "alias printer emitted a doubled row: $printedAlias" }
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
    fun `race-result binding carries exactly one effect row`() {
        val tm = typedModuleOf(raceProgram, "race.mz")
        val rType = bindingTypeAt(tm, 10, 7) // `let r =`
        assertFalse(rType.contains("> <")) { "race binding has a doubled effect row: $rType" }
        assertEquals(1, rType.count { it == '<' }) { "race binding must carry ONE row, got: $rType" }
        assertTrue(rType.startsWith("Result Str AsyncError")) { "unexpected race result value type: $rType" }
        assertTrue(rType.contains("Async")) { "race result should retain its Async row: $rType" }
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
