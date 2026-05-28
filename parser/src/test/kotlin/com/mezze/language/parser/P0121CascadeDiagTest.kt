package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * After a fatal P0121 error inside a lambda body, the synchronizer
 * used to re-enter parsing at the indented `let x = ...` line of the
 * broken body and successfully parse it as a NEW top-level decl. The
 * typechecker then surfaced a cascading V0702 "Undefined variable: p"
 * against that fabricated top-level decl.
 *
 * Fix: the synchronizer now only considers tokens at column 1 as
 * top-level declaration boundaries. Indented `let`/`use`/`pub`/etc.
 * are bodies of a broken outer scope, not new top-level items.
 */
class P0121CascadeDiagTest {
    private val parser = MezzeParser()

    @Test
    fun `P0121 fatal error does not leak indented let into top-level decls`() {
        val src = """
            let bad = {p} ->
              let x = p + 1
              x
        """.trimIndent()
        val r = parser.parseWithRecovery(src, "test.mz")

        // Exactly one parse error (P0121); no surviving top-level decls
        // from the broken lambda body.
        assertEquals(1, r.errors.size,
            "Expected 1 parse error, got ${r.errors.size}: " +
                r.errors.joinToString { "${it.code}=${it.errorMessage}" })
        assertEquals(ErrorCode.P0121_LAMBDA_BODY_BARE_LET, r.errors[0].code)

        // The indented `let x = p + 1` and `x` from the lambda body
        // must NOT be picked up as fresh top-level decls.
        assertEquals(0, r.module.decls.size,
            "Expected no recovered decls, got ${r.module.decls.size}: ${r.module.decls}")
    }

    @Test
    fun `subsequent top-level decl after broken decl still parses`() {
        val src = """
            let bad = {p} ->
              let x = p + 1
              x

            let good = {y} -> y + 1
        """.trimIndent()
        val r = parser.parseWithRecovery(src, "test.mz")

        // P0121 is one error; `good` recovers as one decl at column 1.
        assertEquals(1, r.errors.size, "errors: ${r.errors.joinToString { it.errorMessage }}")
        assertEquals(ErrorCode.P0121_LAMBDA_BODY_BARE_LET, r.errors[0].code)
        assertEquals(1, r.module.decls.size,
            "Expected 1 recovered decl (good), got ${r.module.decls}")
    }
}
