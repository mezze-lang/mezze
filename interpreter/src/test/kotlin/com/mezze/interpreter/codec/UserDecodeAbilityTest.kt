package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * A USER ability whose op happens to be named `decode` must not be confused
 * with the codec `Decode` ability (name-dispatch hole): the per-site decoder
 * wiring (`decodeOpBySite` / `decodeImplByOpHash`) previously indexed ANY impl
 * with an op named `decode`, and applied the pinned codec decoder to any dict
 * slot literally named "decode". Both are now keyed structurally — the codec
 * `Decode 'config 'a for 'in` three-slot class-arg shape and op-hash identity —
 * so user `decode` ops route through normal ability dispatch even when codec
 * from_json decoding is active in the same program.
 */
class UserDecodeAbilityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(source: String): Any = MezzeTestHelper.run(source)

    /** User `decode` op called generically (dict-needing) alongside real codec use. */
    @Test
    fun `user ability decode op is not hijacked by codec decoder wiring`() {
        val src = """
            use std::codec::json::{ FromJson }
            use std::codec::core::{ DecodeError }

            ability Parse for 'a where {
              decode: { it: 'a } -> Int,
            }
            type Token = { raw: Int }
            impl Parse for Token where {
              decode = { it } -> it.raw * 10,
            }
            let via: { x: 'a } with Parse 'a -> Int = { x } -> x.decode {}

            let main = {} -> do
              let jr: Result { name: Str } DecodeError = "{\"name\":\"Ada\"}".from_json {}
              let jname = match jr is
                Ok { val = { name } } -> name
                Err { error = _ } -> "err"
              let tk: Token = { raw = 7 }
              if jname == "Ada" then via { x = tk } else 0 - 1
            main {}
        """.trimIndent()
        assertEquals(70L, run(src))
    }

    /** Same user ability, no codec anywhere — plain generic dispatch baseline. */
    @Test
    fun `user ability decode op works without codec in scope`() {
        val src = """
            ability Parse for 'a where {
              decode: { it: 'a } -> Int,
            }
            type Token = { raw: Int }
            impl Parse for Token where {
              decode = { it } -> it.raw * 10,
            }
            let via: { x: 'a } with Parse 'a -> Int = { x } -> x.decode {}
            let main = {} -> do
              let tk: Token = { raw = 4 }
              via { x = tk }
            main {}
        """.trimIndent()
        assertEquals(40L, run(src))
    }
}
