package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.langservice.hover.HoverInfo
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Hover rendering for effectful-call results, named error variants, and match
 * pattern binders — the shapes surfaced by a spawn/race program.
 *
 * Regression coverage for three hover bugs:
 *  - a named error variant (`AsyncError`) rendered its whole constructor union
 *    instead of its name (the alias-aware printer diverged from the plain one),
 *  - a stale pre-migration binary showed a doubled effect row `<Async,..> <Async>`
 *    (SuspendType leak — deleted by the effect-model migration; asserted absent),
 *  - a match pattern binder (`val` / `error`) hovered as the enclosing match's
 *    result type (`{}`), and then as an unsolved `'a`.
 */
class HoverMatchAndAliasTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun hoverAt(src: String, needle: String, offInTok: Int): String {
        val body = src.trimIndent()
        val pm = parser.parseParsedModule(body, "t.mz")
        val result = tc.typeCheck(pm, Paths.get("."), enablePrelude = true)
        val ctx = HoverInfo.Context(
            content = body,
            typeCheckResult = result,
            typeAliases = tc.getTypeAliases(),
            parsedModule = pm,
        )
        val lines = body.lines()
        var li = -1; var ci = -1
        lines.forEachIndexed { i, l ->
            val idx = l.indexOf(needle)
            if (idx >= 0 && li < 0) { li = i; ci = idx + offInTok }
        }
        return HoverInfo.at(ctx, li, ci) ?: ""
    }

    private val prog = """
        use std::effects::log::{ Console, println }
        use std::effects::async::{ Async, LoomAsync, sleep }
        use std::effects::time::{ SystemTime }

        let fetch = { url, ms } -> do
          sleep { ms }
          "${'$'}(url) replied"

        let main = {} -> perform Console, LoomAsync, SystemTime in do
          let fast = Async.spawn { f = {} -> fetch { url = "eu", ms = 10 } }
          let slow = Async.spawn { f = {} -> fetch { url = "us", ms = 500 } }
          match [fast, slow].race {} is
            Ok { val } -> println { msg = val }
            Err { error } -> println { msg = "e: ${'$'}(error.to_dbg {})" }
    """

    @Test
    fun `named error variant renders as its name, not its constructor union`() {
        val h = hoverAt(prog, ".race", 1)
        assertTrue(h.contains("Result Str AsyncError")) {
            "race hover should name AsyncError, got: $h"
        }
        assertTrue(!h.contains("AsyncFailed")) {
            "race hover must NOT expand AsyncError's constructor union, got: $h"
        }
    }

    @Test
    fun `race hover carries no doubled effect row`() {
        // `<Async, ...> <Async>` was a SuspendType-era leak; the migration bars it.
        val h = hoverAt(prog, ".race", 1)
        assertTrue(!h.contains("> <")) { "race hover must not carry a doubled effect row, got: $h" }
    }

    @Test
    fun `match binder Ok val hovers as the scrutinee-derived Str, not {} nor 'a`() {
        val h = hoverAt(prog, "Ok { val }", 5)
        assertTrue(h.contains("val : Str")) { "val binder should hover as Str, got: $h" }
    }

    @Test
    fun `match binder Err error hovers as the scrutinee-derived AsyncError`() {
        val h = hoverAt(prog, "Err { error }", 6)
        assertTrue(h.contains("error : AsyncError")) { "error binder should hover as AsyncError, got: $h" }
    }

    @Test
    fun `spawned promise binding still hovers as Promise Str`() {
        val h = hoverAt(prog, "let fast", 4)
        assertTrue(h.contains("Promise Str")) { "fast should hover as Promise Str, got: $h" }
    }

    // ── Bug: a variant value whose shape matches an in-scope alias must hover
    //    as the ALIAS NAME (Status), not as `'a` and not as the raw union.
    //    `mez check` already resolves this (`order : { ..., status: Status }`);
    //    the hover path diverged. Same family as the AsyncError test above.

    private val orders = """
        type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |
        let order = { id = "A-1", status = Queued }
    """

    @Test
    fun `variant binding hovers as its in-scope alias name, not 'a`() {
        val h = hoverAt(orders, "status = Queued", 0) // hover on `status` field
        assertTrue(h.contains("Status")) { "status should hover as the alias Status, got: $h" }
        assertTrue(!h.contains("'a")) { "status must not hover as an unsolved 'a, got: $h" }
    }

    @Test
    fun `order binding hovers with status typed as the alias Status`() {
        val h = hoverAt(orders, "let order", 4) // hover on `order`
        assertTrue(h.contains("status: Status")) {
            "order should hover with status: Status (the in-scope alias), got: $h"
        }
    }

    // Faithful reproduction of the deployed home-page `onion.mz`: `order` is a
    // LOCAL `let` inside a `do` block, later used via `describe { order }`. The
    // deployed hovers.json showed the binding-name position rendering
    // `{ id: Str, status: 'a }` — the local-do-let path, which the top-level
    // `orders` case above does not exercise.
    private val ordersFull = """
        use std::int
        type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |
        let describe = { order } -> match order.status is
          Queued -> "${'$'}(order.id) waiting"
          Shipped { days } -> "${'$'}(order.id) in ${'$'}(int::to_str { n = days })"
          Cancelled { why } -> "${'$'}(order.id) cancelled: ${'$'}(why)"
        let main = {} -> do
          let order = { id = "A-1", status = Queued }
          describe { order }
    """

    // KNOWN LIMITATION (disabled): in a LOCAL `let` inside a `do` block, a bare
    // variant field (`let order = { status = Queued }`) hovers as `status: 'a`
    // instead of `Status`. Root cause (traced): the per-binding solve DOES pin
    // the var to Status (VariantSolver.inferVariantTypes returns Success(Status)
    // and bindLet records the fully-resolved `{ id: Str, status: Status }` in
    // doBindingSolvedTypes), but (a) that per-binding substitution is NOT merged
    // into the FINAL global substitution emit reads (only solveResult.substitution
    // is, via compose at TypeChecker.kt:479 — the ConstraintGenerator.kt:193
    // per-binding solve's subst is discarded), and (b) the doBindingSolvedTypes
    // entry recorded in the constraint phase does not survive into emit's
    // aggregated map for this nested case (present at ExprControlFlowConstraints
    // write time, null at TypedAstBuilder read time). The top-level case (the
    // `orders` tests above) works; only the do-block-local path regresses. Fixing
    // it cleanly needs the per-binding variant resolution to reach the global
    // substitution (or the doBindingSolvedTypes entry to survive aggregation) —
    // deeper than a display tweak and risks broad regressions, so it is deferred.
    @Test
    fun `local do-block order binding hovers status as Status, not 'a`() {
        // Hover the `order` binding NAME at its local `let` definition.
        val h = hoverAt(ordersFull, "let order", 4)
        assertTrue(h.contains("Status")) {
            "local `let order` binding must hover with status: Status, got: $h"
        }
        assertTrue(!h.contains("status: 'a")) {
            "local `let order` binding must not render status as 'a, got: $h"
        }
    }

    @Test
    fun `record literal field in a local do-block hovers as Status, not 'a`() {
        // Hover the `status` field inside the record literal at the local let.
        val h = hoverAt(ordersFull, "status = Queued", 0)
        assertTrue(!h.contains("'a")) {
            "record-literal `status` field in a do-block must not render as 'a, got: $h"
        }
    }

    @Test
    fun `a bare variant with NO alias in scope hovers as the structural union`() {
        // No `type` declared — the value's type is exactly its structural shape.
        val src = "let s = Queued"
        val h = hoverAt(src, "let s", 4)
        assertTrue(h.contains("Queued")) {
            "with no alias in scope, a bare variant should show its structural union naming Queued, got: $h"
        }
        assertTrue(!h.contains("'a")) { "a concrete variant must not hover as 'a, got: $h" }
    }

    // ── Bug: hovering an effectful METHOD-ALIAS call (`.send {}`, whose body
    //    performs Http) must carry `<Http>` in the method's hovered signature —
    //    exactly as hovering a free-fn call `println` shows `<Log>`. The
    //    deployed hovers.json showed `send : { it: Request } -> Result Response
    //    HttpError` with the effect row DROPPED, while `println` keeps `<Log>`.
    //    (The enclosing wrapper `weather` DOES carry `<Http>` — that path is
    //    fine; the bug is specific to the method-call hover position.)

    private val httpProg = """
        use std::effects::http::{ request }
        let weather = { url } -> request { url }.send {}.text_or { def = "offline" }
    """

    @Test
    fun `effectful wrapper hovers with its Http effect row`() {
        // Regression guard: the wrapper arrow correctly carries the <Http> effect row.
        // NOTE assert on the EFFECT ROW `<Http`, not the substring "Http" — the return
        // type `HttpError` also contains "Http", which would pass for the wrong reason.
        val h = hoverAt(httpProg, "let weather", 4)
        assertTrue(h.contains("<Http")) {
            "weather calls .send {} (performs Http) — its hover must carry the <Http> effect row, got: $h"
        }
    }

    @Test
    fun `hovering the send method call carries its Http effect row`() {
        // The real bug: `.send {}` hovers as `send : {..} -> Result Response HttpError`
        // WITHOUT the `<Http>` effect row, even though its body performs Http.request.
        // Free-fn `println` keeps `<Log>`; the inherent-method hover drops the row.
        val h = hoverAt(httpProg, ".send", 1) // cursor on the `send` token
        assertTrue(h.contains("send")) { "sanity: hover should be about send, got: $h" }
        assertTrue(h.contains("<Http")) {
            "the .send method call performs Http — its hover must carry the <Http> effect row " +
                "(not merely the HttpError return type), got: $h"
        }
    }

    @Test
    fun `hovering request shows a pure signature, no Http effect row`() {
        // Control: request { url } only BUILDS a Request — it is pure, so its hover
        // must NOT carry a `<Http>` effect row. (Confirms .send, not request, is the
        // effectful call. Asserts absence of the ROW `<Http`, not the word "Http".)
        val h = hoverAt(httpProg, "request {", 0)
        assertTrue(h.contains("request")) { "sanity: hover should be about request, got: $h" }
        assertTrue(!h.contains("<Http")) {
            "request only builds a Request (pure) — its hover must NOT carry a <Http> effect row, got: $h"
        }
    }
}
