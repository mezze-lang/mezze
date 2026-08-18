package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Effect soundness: calling an effectful function THROUGH a record field
 * (`(r.g) {}`) must surface the field function's effect row at the call site,
 * exactly as a direct call does. Otherwise a function annotated PURE could
 * perform effects untracked.
 *
 * Regression for the laundering hole: `generateMemberAccess` typed a field
 * access as a fresh VarType (resolved later via HasField), so a member-access
 * callee reached the call handler as a VarType, and the handler's
 * `is FunctionType` effect-harvest was skipped — the field function's effects
 * vanished. Fix: when the receiver is a concrete record and the field is a
 * function type, the access surfaces that concrete function type (with effects)
 * instead of the fresh VarType, so the normal call-effect harvest fires.
 *
 * (Sources are kept fully inline — do-blocks are indentation-sensitive, and
 * splicing a shared `$const` into another trimIndent()'d string corrupts them.)
 */
class EffectThroughFieldCallTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }
    private fun typeOf(name: String, src: String): String? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)?.let { prettyPrintType(it) }

    @Test
    fun `calling an effectful field in a PURE function is rejected`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} -> do
              let r = { g = noisy }
              (r.g) {}
        """)
        assertNotNull(e, "a pure fn that calls an effectful field must be rejected")
        assertTrue(e!!.contains("Log"), "error should name the leaked effect Log: $e")
    }

    @Test
    fun `the effect surfaces in the inferred type`() {
        val t = typeOf("f", """
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f = {} -> do
              let r = { g = noisy }
              (r.g) {}
        """)
        assertNotNull(t)
        assertTrue(t!!.contains("Log"), "inferred type must carry <Log>, got: $t")
    }

    @Test
    fun `declaring the effect makes it type-check`() {
        assertNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str <Log> = {} -> do
              let r = { g = noisy }
              (r.g) {}
        """), "declaring <Log> must be accepted")
    }

    @Test
    fun `inline field call also surfaces the effect`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} -> ({ g = noisy }.g) {}
        """)
        assertNotNull(e, "the inline `({g=noisy}.g){}` form must also be rejected when pure")
        assertTrue(e!!.contains("Log"))
    }

    @Test
    fun `a PURE function stored in a field does NOT gain effects`() {
        // The dual: no over-reporting. A pure field function keeps the caller pure.
        assertNull(err("""
            let pureFn: {} -> Str = {} -> "v"
            let f: {} -> Str = {} -> do
              let r = { g = pureFn }
              (r.g) {}
        """), "a pure field function must not make the caller effectful")
    }

    @Test
    fun `merely storing an effectful fn without calling it stays pure`() {
        // Storing a function value is pure; only CALLING it performs.
        assertNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} -> do
              let r = { g = noisy }
              "not called"
        """), "storing (not calling) an effectful fn must not add effects")
    }
}
