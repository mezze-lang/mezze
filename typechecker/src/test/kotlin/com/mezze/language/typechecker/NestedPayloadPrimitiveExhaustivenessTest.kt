package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * B3: a NESTED non-enumerable-primitive payload field (`Wrap { n: Int }`) that
 * arms pin to literals with no catch-all must be rejected as non-exhaustive —
 * mirroring the top-level `match n is 0 -> .. 1 -> ..` rule. Before the fix it
 * type-checked and then trapped at runtime (`#221` synthesizes `n==0`/`n==1`
 * guards with no fallthrough).
 */
class NestedPayloadPrimitiveExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── rejected: nested primitive field pinned to literals, no catch-all ──

    @Test fun `nested Int-literal payload with no catch-all is rejected`() {
        val m = err(
            """
            type Outer = | Wrap { n: Int } |
            let f: { o: Outer } -> Int = { o } -> match o is
              Wrap { n = 0 } -> 100
              Wrap { n = 1 } -> 200
            """
        )
        assertNotNull(m, "nested Int field pinned to 0/1 with no catch-all is non-exhaustive")
    }

    @Test fun `nested Str-literal payload with no catch-all is rejected`() {
        val m = err(
            """
            type Msg = | Tag { s: Str } |
            let f: { m: Msg } -> Int = { m } -> match m is
              Tag { s = "a" } -> 1
              Tag { s = "b" } -> 2
            """
        )
        assertNotNull(m, "nested Str field pinned to literals with no catch-all is non-exhaustive")
    }

    // ── accepted: nested primitive field WITH an irrefutable catch-all ──

    @Test fun `nested Int payload with a catch-all arm is accepted`() {
        assertNull(
            err(
                """
                type Outer = | Wrap { n: Int } |
                let f: { o: Outer } -> Int = { o } -> match o is
                  Wrap { n = 0 } -> 100
                  Wrap { n = other } -> other
                """
            ),
            "a variable sub-pattern on the Int field is a catch-all → exhaustive",
        )
    }

    @Test fun `nested Int payload bound whole is accepted`() {
        assertNull(
            err(
                """
                type Outer = | Wrap { n: Int } |
                let f: { o: Outer } -> Int = { o } -> match o is
                  Wrap { n = n } -> n
                """
            ),
            "binding the Int field to a variable covers all values → exhaustive",
        )
    }
}
