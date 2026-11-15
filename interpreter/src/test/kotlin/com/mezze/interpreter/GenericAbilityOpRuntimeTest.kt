package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * BUG 42 — an ability operation with its OWN type parameter, and the dictionary
 * that has to arrive per INSTANTIATION.
 *
 * ```mezze
 * ability Wrap for 'w where { wrap: { it: 'w, x: 'a } with Show 'a -> Int }
 * ```
 *
 * `'a` belongs to the OPERATION, not to `Wrap` — Rust's
 * `fn wrap<A: Show>(&self, x: A)`. T0301 permits it because the per-op `with`
 * clause introduces it; V0417 then refused every impl of it, so the ability was
 * uninhabitable. Widening V0417 made it compile, and THAT ALONE WAS WORSE THAN
 * THE BUG:
 *
 *     s.wrap { x = s }   ## 101   (Show Src = 100)
 *     s.wrap { x = o }   ## 101   <- must be 201 (Show Other = 200)
 *
 * Both instantiations ran `Src`'s dictionary: "cannot be implemented" replaced
 * by "runs wrong, silently".
 *
 * CAUSE: `obligationDispatchTypes` harvested the op's variable bindings from the
 * declared RETURN type only. `wrap` returns `Int`, so it bound nothing, the
 * obligation dispatched nothing, and resolution fell through to the flattened
 * receiver/argument pile — which starts with the RECEIVER and takes the first
 * impl claiming it. An operation's own parameter binds from the ARGUMENT, so the
 * arguments are harvested too now.
 *
 * WHY A RUNTIME TEST: the typechecker accepted both call sites before and after.
 * Only running them tells the two apart, which is the same reason
 * `ClassParamDispatchRuntimeTest` exists for BUGS 38/39.
 */
class GenericAbilityOpRuntimeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val src = """
        use std::effects::log::{ println, Console }
        type Src = { n: Int }
        type Other = { m: Int }

        ability Show for 'a where { show: { it: 'a } -> Int }
        impl Show for Src where { show: { it: Src } -> Int = { it } -> 100 }
        impl Show for Other where { show: { it: Other } -> Int = { it } -> 200 }

        ability Wrap for 'w where { wrap: { it: 'w, x: 'a } with Show 'a -> Int }
        impl Wrap for Src where {
          wrap: { it: Src, x: 'a } with Show 'a -> Int = { it, x } -> x.show {} + 1,
        }

        let s: Src = { n = 1 }
        let o: Other = { m = 2 }
        let main = {} -> perform Console in do
          println { msg = s.wrap { x = s }.to_str {} }
          println { msg = s.wrap { x = o }.to_str {} }
        main {}
    """.trimIndent()

    @Test
    fun `each instantiation of an operation's own type param gets its own dictionary`() {
        val out = MezzeTestHelper.runWithOutput(src).output
        assertEquals(
            listOf("101", "201"),
            out.trim().lines().map { it.trim() }.filter { it.isNotEmpty() },
            "one generic operation, two instantiations: `x = s` must reach `Show Src` (100+1) " +
                "and `x = o` must reach `Show Other` (200+1). Both reading 101 is the " +
                "receiver's dictionary being used for an argument's type.",
        )
    }
}
