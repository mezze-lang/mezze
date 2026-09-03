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
}
