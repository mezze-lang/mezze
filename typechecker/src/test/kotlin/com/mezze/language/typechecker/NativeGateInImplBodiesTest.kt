package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * BUG 62: the #109 rule — `__native_*` intrinsics are stdlib-only — was enforced
 * in plain code (`let t = __native_instant_now {}` is V0702) but NOT inside any
 * impl body: performer, ability and inherent op bodies all excused undefined
 * native names unconditionally, because stdlib impls reference natives. User
 * code could therefore read the wall clock from a performer whose row says
 * `<Async>` alone — exactly the route the deterministic-scheduler design relies
 * on not existing.
 *
 * Every claim is mirrored: the same reference is refused in user code and must
 * stay legal in the stdlib, where the rule is checked by `compileStdlib`.
 */
class NativeGateInImplBodiesTest : AbilityEffectSpecBase() {

    @Test
    fun `a performer op body may not reference a native`() {
        rejects(
            """
            effect Tick where { tick: {} -> Int <Tick>, }
            impl Tick for Sneaky where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              tick = {} -> do
                let t = __native_instant_now {}
                1,
            }
            $MAIN
            """,
            "V0702",
            "a performer is userland policy; the clock is reachable only through `Time`",
        )
    }

    @Test
    fun `an ability impl body may not reference a native`() {
        rejects(
            """
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for Int where { sz = { it } -> do
              let t = __native_instant_now {}
              1 }
            $MAIN
            """,
            "V0702",
            "same rule, second impl kind",
        )
    }

    @Test
    fun `an inherent impl body may not reference a native`() {
        rejects(
            """
            type Bx = { v: Int }
            impl Bx where { peek = { it } -> do
              let t = __native_instant_now {}
              it.v }
            $MAIN
            """,
            "V0702",
            "same rule, third impl kind",
        )
    }

    @Test
    fun `the refusal names the native, not a later symptom`() {
        val r = check(
            """
            effect Tick where { tick: {} -> Int <Tick>, }
            impl Tick for Sneaky where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              tick = {} -> __native_instant_now {},
            }
            $MAIN
            """.trimIndent(),
        )
        val msg = r.exceptionOrNull()?.message ?: ""
        kotlin.test.assertTrue(msg.contains("__native_instant_now"), "the native must be named: $msg")
    }
}
