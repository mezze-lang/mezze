package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Runtime-level integration tests for the typechecker fixes that landed
 * with Tryable. The typechecker-level regression tests (in
 * `AbilityMethodEffectsRegressionTest`) confirm constraint generation
 * succeeds. These confirm the IR compilation + runtime dispatch is
 * also correct.
 */
class AbilityEffectIntegrationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `custom ability method with Log effect runs end-to-end`() {
        // Fix #1 + #2 integration: user-defined ability method declaring <Log>,
        // implemented for Str, called inside `perform QuietConsole`.
        // If either fix is reverted, this either fails to typecheck or crashes
        // at runtime.
        val result = eval("""
            use std::effects::log::{Log, QuietConsole, info}

            ability Greeter for 'g where {
                greet: {it: 'g} -> Str <Log>
            }

            impl Greeter for Str where {
                greet = {it} -> do
                    info {msg = "greeting"}
                    "hello"
            }

            let run = {} -> perform QuietConsole in "world".greet {}

            run {}
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `Sized_size on direct effectful Seq result runs end-to-end`() {
        // Fix #4 integration: dispatch on SuspendedType receiver for a
        // non-Tryable ability (Sized). Confirms the unwrap fix in
        // tryResolveAbilityOp works generally, not just for Tryable.
        val result = eval("""
            use std::effects::log::{Log, QuietConsole, info}

            let make_seq : {} -> [Int] <Log> = {} -> do
                info {msg = "producing"}
                [1, 2, 3, 4, 5]

            let run = {} -> perform QuietConsole in (make_seq {}).size {}

            run {}
        """)
        assertEquals(5L, result)
    }
}
