package com.mezze.language.cli

import com.mezze.language.cli.commands.RunnabilityCheck
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * "Can a tool run this for me?" — one case per SHAPE of effect row, because the
 * shapes behave differently and only some of them mean "refuse".
 *
 * The distinction that matters: a concrete effect left undischarged means the
 * caller gets a `MezzeSuspend` instead of a value, silently. The `*`
 * performed-marker and an open row-var tail are NOT concrete effects, and
 * refusing those would reject functions that run perfectly well.
 *
 * Types come from a real parse + typecheck rather than hand-built MezzeTypes,
 * so the test exercises the rows the typechecker actually produces.
 */
class RunnabilityCheckTest {

    private fun decl(source: String, name: String): TypedLetDecl {
        val parsed = MezzeParser().parseParsedModule(source, "test.mz")
        val result = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
        val success = result as? TypeChecker.TypeCheckResult.Success
            ?: error("typecheck failed: " +
                (result as TypeChecker.TypeCheckResult.Failure).errors.joinToString { it.message })
        val typed = success.typedModule ?: error("typedModule is null")
        return typed.decls.filterIsInstance<TypedLetDecl>().find { it.name == name }
            ?: error("no decl named '$name' in: " +
                typed.decls.filterIsInstance<TypedLetDecl>().joinToString { it.name })
    }

    private fun check(source: String, name: String) = RunnabilityCheck.check(decl(source, name))

    // ---- runnable ----------------------------------------------------------

    @Test
    fun `a pure function is runnable`() {
        val result = check("let add: { x: Int, y: Int } -> Int = { x, y } -> x + y", "add")
        assertTrue(result is RunnabilityCheck.Result.Runnable, "expected Runnable, got $result")
    }

    @Test
    fun `a pure function produces no refusal`() {
        val result = check("let double: { x: Int } -> Int = { x } -> x * 2", "double")
        assertNull(RunnabilityCheck.refusal(result, "m::double"))
    }

    // ---- refuse ------------------------------------------------------------

    @Test
    fun `an effectful function needs handlers`() {
        val src = """
            use std::effects::log::{ println, Console }

            let greet: { name: Str } -> {} <Log> = { name } -> println { msg = name }
        """.trimIndent()
        val result = check(src, "greet")
        assertTrue(result is RunnabilityCheck.Result.NeedsHandlers,
            "expected NeedsHandlers, got $result")
    }

    @Test
    fun `the refusal names the effect and shows the perform form`() {
        val src = """
            use std::effects::log::{ println, Console }

            let greet: { name: Str } -> {} <Log> = { name } -> println { msg = name }
        """.trimIndent()
        val message = RunnabilityCheck.refusal(check(src, "greet"), "m::greet")
        assertNotNull(message, "an effectful function must be refused")
        // The point of the message is that it tells you what to do next.
        assertTrue(message!!.contains("mez sample perform"),
            "refusal must show the perform form, got:\n$message")
        assertTrue(message.contains("in m::greet"),
            "refusal must echo the reference the user typed, got:\n$message")
    }

    @Test
    fun `the refusal explains that running unhandled would not fail loudly`() {
        val src = """
            use std::effects::log::{ println, Console }

            let greet: { name: Str } -> {} <Log> = { name } -> println { msg = name }
        """.trimIndent()
        val message = RunnabilityCheck.refusal(check(src, "greet"), "m::greet")!!
        // Without this sentence the user reasonably assumes the tool is being
        // fussy, rather than that the alternative is silently meaningless output.
        assertTrue(message.contains("suspended"),
            "refusal must say why it cannot just run it, got:\n$message")
    }

    // ---- handled: the effect is discharged, so it IS runnable ---------------

    @Test
    fun `a function that discharges its own effects is runnable`() {
        // `<*>` is required, not decoration: the body performs an effect and
        // handles it itself, and an annotation that hides that would leave a caller
        // unable to tell it apart from a body that handled nothing (T0210). The
        // fixture predated that rule.
        val src = """
            use std::effects::log::{ println, Console }

            let greet: { name: Str } -> {} <*> = { name } ->
              perform Console in println { msg = name }
        """.trimIndent()
        val result = check(src, "greet")
        assertTrue(result is RunnabilityCheck.Result.Runnable,
            "an internally-handled function must be runnable, got $result")
    }

    // ---- shapes that must NOT be refused -----------------------------------
    //
    // These are the cases a careless implementation gets wrong: a `*` marker or
    // an open row tail LOOKS like an effect row, and refusing them would reject
    // functions that run perfectly well. EntryPointCheck allows `{} -> {} <*>`
    // for the same reason.

    @Test
    fun `a performed-marker row is runnable, not refused`() {
        val src = """
            use std::effects::log::{ println, Console }

            let run: {} -> {} <*> = {} -> perform Console in println { msg = "hi" }
        """.trimIndent()
        val result = check(src, "run")
        assertTrue(result is RunnabilityCheck.Result.Runnable,
            "`<*>` is a performed marker, not a concrete effect — got $result")
        assertNull(RunnabilityCheck.refusal(result, "m::run"))
    }

    // ---- not a function ----------------------------------------------------

    @Test
    fun `a non-function is refused with a different reason`() {
        val result = check("let answer: Int = 42", "answer")
        assertTrue(result is RunnabilityCheck.Result.NotAFunction,
            "expected NotAFunction, got $result")
        val message = RunnabilityCheck.refusal(result, "m::answer")!!
        assertTrue(message.contains("not a function"), message)
    }
}
