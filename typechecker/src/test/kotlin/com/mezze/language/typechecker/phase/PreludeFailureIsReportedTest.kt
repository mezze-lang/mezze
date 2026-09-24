package com.mezze.language.typechecker.phase

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A prelude that will not load is REPORTED, not absorbed.
 *
 * `loadPreludeModules` used to answer a failed resolution with
 * `ModuleImportContext.empty()`. That is a lie with no diagnostic attached:
 * every ability, impl and operator the standard library provides silently
 * disappears, and the first thing the user's program does with one becomes the
 * error they see. A single broken stdlib module presented as
 *
 *     let x: Int = 1 + 2
 *     Cannot use '+' with Int, Int. To enable this, implement:
 *       impl NumAdd Int for Int having 'out = ... where { ... }
 *
 * which is untrue, leads nowhere, and reads the same whatever the real cause
 * was. The real cause in that instance was one under-applied impl target in
 * `std/option.mz`, five words long and invisible from the message.
 *
 * MEASURED before the change: with a healthy stdlib this branch fires ZERO times
 * across both full suites. It is not a path normal compilation takes, so nothing
 * legitimate was relying on the silence.
 *
 * WHAT THESE COVER, precisely: the DIAGNOSTIC — what it says, that it names the
 * failing module, that it carries every error and the cause — plus the control
 * that a healthy prelude still compiles. They do NOT cover the WIRING: that
 * `loadPreludeModules` calls [preludeFailure] rather than returning an empty
 * context. Re-introducing the swallow would leave every test here green.
 *
 * That gap is not an oversight to be papered over with a weaker assertion.
 * Reaching the real branch needs a deliberately broken standard library on the
 * classpath — `readStdlibFile` resolves through the class loader with no root
 * override — and `ImportResolver` is final, so it cannot be stubbed either.
 * Covering it means either a custom class loader or making the resolver
 * injectable, and neither is worth doing for this alone. Whoever next touches
 * prelude loading should know the wiring rests on review, not on this file.
 */
class PreludeFailureIsReportedTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun importError(module: String, reason: String) =
        ModuleError.ImportError(modulePath = module, itemName = "*", reason = reason)

    @Test
    fun `the diagnostic names the underlying module error`() {
        val err = preludeFailure(listOf(importError("std::option", "Operation 'default' returns wrong type")))

        assertEquals(ErrorCode.M0804_MODULE_HAS_ERRORS, err.code)
        assertTrue(
            err.message.contains("std::option"),
            "the failing MODULE must be named, or the reader has nowhere to look: ${err.message}",
        )
        assertTrue(
            err.message.contains("Operation 'default' returns wrong type"),
            "the underlying REASON must survive: ${err.message}",
        )
    }

    @Test
    fun `it says whose fault this is`() {
        // The message that replaced this one blamed the user's arithmetic. This
        // one has to point at the standard library instead, and admit the one
        // case where the user's own code can cause it.
        val err = preludeFailure(listOf(importError("std::option", "boom")))
        assertTrue(
            err.message.contains("not in your program"),
            "must not read as an error in the user's code: ${err.message}",
        )
        assertTrue(
            err.message.contains("shadows"),
            "must mention the one way a user's own module CAN cause this: ${err.message}",
        )
    }

    @Test
    fun `every error is carried, not just the first`() {
        // One bad declaration can fail several imports at once. Reporting the
        // first only would hide the rest behind a recompile each.
        val err = preludeFailure(
            listOf(
                importError("std::option", "first"),
                importError("std::result", "second"),
                importError("std::int", "third"),
            )
        )

        assertEquals(3, err.all().size, "all three problems should be reachable from one throw")
        val messages = err.all().joinToString(" | ") { it.message }
        assertTrue(messages.contains("second") && messages.contains("third"), messages)
    }

    @Test
    fun `it carries the underlying error as the cause`() {
        // The CLI prints a cause's stack trace only under `--verbose`; carrying
        // it is what makes that flag able to show anything here.
        val underlying = importError("std::option", "boom")
        assertEquals(underlying, preludeFailure(listOf(underlying)).cause)
    }

    @Test
    fun `an empty error list still produces a usable message`() {
        // Defensive: a Failure carrying no errors must not blow up in the error
        // path itself, which is the worst place to throw a second exception.
        val err = preludeFailure(emptyList())
        assertTrue(err.message.isNotBlank())
        assertEquals(ErrorCode.M0804_MODULE_HAS_ERRORS, err.code)
    }

    @Test
    fun `a healthy prelude does NOT throw`() {
        // The control, and the regression risk of the change: turning the silent
        // path into a throw must not fire on an ordinary compile.
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        val result = TypeChecker().typeCheck(
            MezzeParser().parseParsedModule("let x: Int = 1 + 2\nx", "ok.mz"), Paths.get(".")
        )
        assertEquals(com.mezze.language.ast.MezzeType.IntType, result.getVariableType("x"))
    }
}
