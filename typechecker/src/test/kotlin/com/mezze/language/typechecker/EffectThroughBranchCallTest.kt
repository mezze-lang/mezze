package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Effect soundness: calling the RESULT of a `match`/`if` expression must surface
 * the selected function's effect row at the call site — a function annotated
 * PURE cannot perform effects picked in a branch. `if`/`else` desugars to
 * `match`, so both are covered by the same fix in `generateMatch`.
 *
 * Regression for the branch-call laundering hole (the general form of
 * [EffectThroughFieldCallTest]): a match/if result type is a fresh VarType, so a
 * branch-result callee reached the call handler as a VarType and its
 * `is FunctionType` effect-harvest was skipped. Fix: when every arm's value is a
 * concrete function type, the match result surfaces a merged function type
 * UNIONing the arms' effect rows. Sources are inline (do-blocks are
 * indentation-sensitive).
 */
class EffectThroughBranchCallTest {
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
    fun `calling a match result with an effectful arm in a PURE fn is rejected`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let a: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "a"
            let b: {} -> Str = {} -> "b"
            let f: {} -> Str = {} ->
              (match True is
                True -> a
                False -> b) {}
        """)
        assertNotNull(e, "calling a match result with an effectful arm must be rejected in a pure fn")
        assertTrue(e!!.contains("Log"), "error should name the leaked effect Log: $e")
    }

    @Test
    fun `the branch effect surfaces in the inferred type`() {
        val t = typeOf("f", """
            effect Log where { log : {msg: Str} -> {} <Log> }
            let a: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "a"
            let b: {} -> Str = {} -> "b"
            let f = {} -> do
              let chosen = match True is
                True -> a
                False -> b
              chosen {}
        """)
        assertNotNull(t)
        assertTrue(t!!.contains("Log"), "inferred type must carry <Log>, got: $t")
    }

    @Test
    fun `if-else result call is rejected too (desugars to match)`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let a: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "a"
            let b: {} -> Str = {} -> "b"
            let f: {} -> Str = {} ->
              (if True then a else b) {}
        """)
        assertNotNull(e, "if/else result call must also be rejected when pure")
        assertTrue(e!!.contains("Log"))
    }

    @Test
    fun `both arms effectful, let-bound then called, is rejected`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let a: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "a"
            let b: {} -> Str <Log> = {} -> do
              Log.log { msg = "y" }
              "b"
            let f: {} -> Str = {} -> do
              let chosen = match True is
                True -> a
                False -> b
              chosen {}
        """)
        assertNotNull(e)
        assertTrue(e!!.contains("Log"))
    }

    @Test
    fun `declaring the effect makes it type-check`() {
        assertNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let a: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "a"
            let b: {} -> Str = {} -> "b"
            let f: {} -> Str <Log> = {} ->
              (match True is
                True -> a
                False -> b) {}
        """), "declaring <Log> must be accepted")
    }

    @Test
    fun `both arms pure, calling the result stays pure (no over-report)`() {
        assertNull(err("""
            let a: {} -> Str = {} -> "a"
            let b: {} -> Str = {} -> "b"
            let f: {} -> Str = {} ->
              (match True is
                True -> a
                False -> b) {}
        """), "a branch between two pure functions must not add effects")
    }

    @Test
    fun `a match returning a non-function value is unaffected`() {
        assertNull(err("""
            let f: {} -> Int = {} ->
              match True is
                True -> 1
                False -> 2
        """), "match over Int arms must still type-check")
    }
}
