package com.mezze.language.cli

import com.mezze.language.cli.commands.EntryPointCheck
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Regression for the path that `mez dev` / `mez exec` exercise:
 *   parse -> typecheck -> grab main TypedLetDecl -> EntryPointCheck.check.
 *
 * The typechecker can resolve nested `perform Console in perform SystemStdin
 * in do { ... }` cleanly (see ImportedPerformerTest), but the value the
 * typechecker stores on the let-decl is what EntryPointCheck inspects — if
 * that differs from the externally-visible inferred type, the CLI rejects
 * a program the typechecker considers fine.
 */
class EntryPointCheckTest {

    /** Every top-level `let` the module typechecks to — what the CLI hands the rule. */
    private fun typedDecls(source: String): List<TypedLetDecl> {
        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")
        val result = TypeChecker().checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
        val success = result as? TypeChecker.TypeCheckResult.Success
            ?: error("typecheck failed: ${(result as TypeChecker.TypeCheckResult.Failure).errors.joinToString { it.message }}")
        return (success.typedModule ?: error("typedModule is null")).decls.filterIsInstance<TypedLetDecl>()
    }

    private fun checkMain(source: String): EntryPointCheck.Result {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typeChecker = TypeChecker()
        val result = typeChecker.checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
        val success = result as? TypeChecker.TypeCheckResult.Success
            ?: error("typecheck failed: ${(result as TypeChecker.TypeCheckResult.Failure).errors.joinToString { it.message }}")
        val typedModule = success.typedModule
            ?: error("typedModule is null")
        val main = typedModule.decls
            .filterIsInstance<TypedLetDecl>()
            .find { it.name == "main" }
            ?: error("no `main` decl found")
        return EntryPointCheck.check(main)
    }

    @Test
    fun `main with println under perform Console is accepted`() {
        val source = """
            use std::effects::log::{println, Console}

            let main = {} -> perform Console in do
                println { msg = "hi" }
        """.trimIndent()

        val result = checkMain(source)
        assertEquals(EntryPointCheck.Result.Ok, result,
            "expected Ok, got: ${EntryPointCheck.errorMessage(result, "main", emptyMap())}")
    }

    @Test
    fun `main with read_line under perform SystemStdin inside Console is accepted`() {
        // Reproduces the `mez dev` smoke-test failure.
        val source = """
            use std::effects::log::{println, Console}
            use std::effects::stdin::{SystemStdin, read_line}

            let main = {} -> perform Console in perform SystemStdin in do
                let line = read_line {}
                println { msg = "done" }
        """.trimIndent()

        val result = checkMain(source)
        val msg = EntryPointCheck.errorMessage(result, "main", emptyMap())
        assertEquals(EntryPointCheck.Result.Ok, result,
            "expected Ok, got error: $msg")
        assertNull(msg)
    }

    @Test
    fun `main with match on Option from read_line under nested perform is accepted`() {
        val source = """
            use std::effects::log::{println, Console}
            use std::effects::stdin::{SystemStdin, read_line}

            let main = {} -> perform Console in perform SystemStdin in do
                match read_line {} is
                    Some { val = name } -> println { msg = "hi" }
                    None -> println { msg = "(no input)" }
        """.trimIndent()

        // Print both views: what the typechecker context says vs what the decl carries.
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typeChecker = TypeChecker()
        val tcRes = typeChecker.typeCheck(parsedModule, Paths.get("."))
        val contextType = tcRes.getVariableType("main")
        println("== getVariableType(\"main\"): $contextType")

        val checkRes = typeChecker.checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
        val success = checkRes as? TypeChecker.TypeCheckResult.Success
            ?: error("typecheck failed: ${(checkRes as TypeChecker.TypeCheckResult.Failure).errors.joinToString { it.message }}")
        val mainDecl = success.typedModule!!.decls
            .filterIsInstance<TypedLetDecl>()
            .first { it.name == "main" }
        println("== mainDecl.scheme.type:    ${mainDecl.scheme.type}")

        val result = EntryPointCheck.check(mainDecl)
        assertEquals(EntryPointCheck.Result.Ok, result,
            "expected Ok, got: ${EntryPointCheck.errorMessage(result, "main", emptyMap())}")
    }

    @Test
    fun `main that performs an unhandled well-known effect is rejected`() {
        // Console imported but never performed → Log rides main's arrow unhandled.
        // The typechecker types this fine; EntryPointCheck (the CLI rule) rejects it.
        val source = """
            use std::effects::log::{println, Console}

            let main = {} -> println { msg = "hi" }
        """.trimIndent()

        val result = checkMain(source)
        assertTrue(result is EntryPointCheck.Result.UnhandledEffects,
            "expected UnhandledEffects (Log leaks at the entry), got: $result")
        val msg = EntryPointCheck.errorMessage(result, "main", emptyMap())
        assertNotNull(msg)
        assertTrue(msg!!.contains("perform"),
            "message must tell the user to add a handler, got: $msg")
    }

    /**
     * The manifest names an entry no module defines.
     *
     * `mez build` and the wire runner rejected this; `mez check` SKIPPED its whole
     * entry block when the decl was absent, so the one command whose job is to
     * tell you in advance stayed quiet and the failure surfaced at build. All
     * three now ask [EntryPointCheck.entryError], which owns both halves of the
     * rule — is there an entry, and is it acceptable.
     */
    @Test
    fun `a missing entry is an error, not a skipped check`() {
        val decls = typedDecls("let other = {} -> {}")
        val msg = EntryPointCheck.entryError(decls, "main", emptyMap())
        assertNotNull(msg, "an app whose declared entry is absent must be rejected")
        assertTrue(msg!!.contains("main"), "the message must name the missing entry, got: $msg")
    }

    @Test
    fun `entryError is null when the entry is present and acceptable`() {
        assertNull(EntryPointCheck.entryError(typedDecls("let main = {} -> {}"), "main", emptyMap()))
    }

    @Test
    fun `entryError reports an unhandled effect on the entry it found`() {
        val source = """
            use std::effects::log::{println, Console}

            let main = {} -> println { msg = "hi" }
        """.trimIndent()
        val msg = EntryPointCheck.entryError(typedDecls(source), "main", emptyMap())
        assertNotNull(msg)
        assertTrue(msg!!.contains("perform"), "must tell the user to add a handler, got: $msg")
    }

    /**
     * Which performer to reach for is the effect AUTHOR's to say, written on the
     * effect as `#{ unhandled: "…" }` — for the stdlib's `Log`, `Console`. It
     * used to be a list kept in this file, naming three stdlib effects and none
     * of anyone else's.
     */
    @Test
    fun `an unhandled effect carries its author's advice`() {
        val source = """
            use std::effects::log::{println, Console}

            #{ unhandled: "Answer it with `perform Oracle42 in …`." }
            effect Oracle where { ask: {} -> Int <Oracle> }

            let main = {} -> do
              println { msg = "hi" }
              println { msg = Oracle.ask {}.to_str {} }
        """.trimIndent()
        val parsed = MezzeParser().parseParsedModule(source, "test.mz")
        val wrapped = TypeChecker().typeCheck(parsed, Paths.get("."), enablePrelude = true)
        val decls = wrapped.typedModule!!.decls.filterIsInstance<TypedLetDecl>()

        val msg = EntryPointCheck.entryError(decls, "main", wrapped.metadataByHash)!!

        assertTrue(msg.contains("Oracle: Answer it with `perform Oracle42 in …`."), "this module's effect: $msg")
        assertTrue(msg.contains("Log: Wrap it in `perform Console in …`"), "the stdlib's effect: $msg")
    }

    @Test
    fun `a pure main is accepted`() {
        assertEquals(EntryPointCheck.Result.Ok, checkMain("let main = {} -> {}"))
    }

    @Test
    fun `main that performs an unhandled USER effect is rejected`() {
        // Well-typed (`main : {} -> {} <Oracle, *>`), but Oracle has no handler at
        // the entry. Returns `{}` so it reaches the effect gate, not the return-shape one.
        val source = """
            effect Oracle where { ask: {} -> Int <Oracle> }

            let main = {} -> do
                let n = Oracle.ask {}
                {}
        """.trimIndent()

        val result = checkMain(source)
        assertTrue(result is EntryPointCheck.Result.UnhandledEffects,
            "expected UnhandledEffects (Oracle leaks at the entry), got: $result")
        assertTrue((result as EntryPointCheck.Result.UnhandledEffects).effects.any { it.first == "Oracle" },
            "message should name Oracle, got: ${result.effects}")
    }
}
