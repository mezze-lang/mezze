package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class AuditProbeTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }


    private fun writeProbe(msg: String) {
        java.io.File("/tmp/audit_probe_out.txt").appendText(msg + "\n")
    }

    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // PROBE A: a fn `with Ord 'a` called at a type lacking Ord — should be rejected at the call site.
    @Test fun `A generic ord fn called at record type`() {
        val e = err("""
            type P = { x: Int }
            pub let cmp : { a: 'a, b: 'a } with Ord 'a -> Bool = { a, b } -> a < b
            pub let g = {} -> cmp { a = { x = 1 }, b = { x = 2 } }
        """.trimIndent())
        writeProbe("PROBE A (want reject): " + (e ?: "ACCEPTED"))
    }

    // PROBE B: obligation propagated through a HOF that takes the constrained fn as callback.
    @Test fun `B ord obligation through hof`() {
        val e = err("""
            type P = { x: Int }
            pub let apply2 : { f: { a: 'a, b: 'a } -> Bool, x: 'a, y: 'a } -> Bool = { f, x, y } -> f { a = x, b = y }
            pub let cmp : { a: 'a, b: 'a } with Ord 'a -> Bool = { a, b } -> a < b
            pub let g = {} -> apply2 { f = cmp, x = { x = 1 }, y = { x = 2 } }
        """.trimIndent())
        writeProbe("PROBE B (want reject): " + (e ?: "ACCEPTED"))
    }

    // PROBE C: sanity - the same generic ord fn at Int (has Ord) should be accepted.
    @Test fun `C generic ord fn at int accepted`() {
        val e = err("""
            pub let cmp : { a: 'a, b: 'a } with Ord 'a -> Bool = { a, b } -> a < b
            pub let g = {} -> cmp { a = 1, b = 2 }
        """.trimIndent())
        writeProbe("PROBE C (want accept): " + (e ?: "ACCEPTED"))
    }

    // PROBE D: container Ord needs element Ord (option.mz pattern). Option of record.
    @Test fun `D option lt on record element`() {
        val e = err("""
            use std::option::{ Option }
            type P = { x: Int }
            pub let g = { a: Option P, b: Option P } -> a < b
        """.trimIndent())
        writeProbe("PROBE D (want reject): " + (e ?: "ACCEPTED"))
    }

    // PROBE E: multi-param ability, first typeArg concrete, obligation on 2nd param.
    @Test fun `E multiparam obligation first arg concrete`() {
        val e = err("""
            ability Conv 'from for 'to where { conv: { it: 'to, from: 'from } -> 'to }
            impl Conv Int for Int where { conv = { it, from } -> it }
            type Bad = { z: Int }
            pub let useit : { x: 'to } with Conv Int 'to -> 'to = { x } -> x
            pub let g = {} -> useit { x = { z = 5 } }
        """.trimIndent())
        writeProbe("PROBE E (want reject - no Conv Int Bad): " + (e ?: "ACCEPTED"))
    }
}
