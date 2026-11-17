package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * BUG 60: with several inherent candidates and a receiver nothing has typed yet,
 * #184 COMMITTED to the single concrete candidate. For `{ ys } -> ys.map {…}`
 * that was `Bytes.map`, so a caller passing a list was told `ys` "expects Bytes".
 * The pick now waits for the solved receiver and refuses loudly if it never comes.
 */
class InherentMethodNoGuessTest : AbilityEffectSpecBase() {

    @Test
    fun `an unresolved receiver is refused, not guessed`() {
        rejects(
            """
            use std::collections::seq::{ map }
            let mapper = { ys } -> ys.map { f = { x } -> x }
            $MAIN
            """,
            "T0206",
            "inherent methods are not abstractable, so nothing can choose the impl",
        )
    }

    @Test
    fun `a receiver the solve determines still resolves`() {
        accepts(
            """
            type Resp = { status: Int }
            type Er = | Failed { msg: Str } |
            impl Resp where { is_ok = { it } -> it.status == 200, }
            let make_resp: {} -> Result Resp Er = {} -> Ok { val = { status = 200 } }
            let out: Int = match make_resp {} is
              Ok { val } -> if val.is_ok {} then 1 else 0
              Err { error = _ } -> 0
            $MAIN
            """,
            "#184's case: `val` is Resp once solved, so the pick waits and lands",
        )
    }
}
