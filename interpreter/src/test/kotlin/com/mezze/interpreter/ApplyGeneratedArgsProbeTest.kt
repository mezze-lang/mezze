package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** How `mez sample` should shape its generated expression. */
class ApplyGeneratedArgsProbeTest {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, with_seed }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun show(label: String, body: String) {
        System.err.println("=== $label ===")
        try {
            System.err.println("   OK -> " + MezzeTestHelper.run("$imports\n\n$body"))
        } catch (e: Exception) {
            System.err.println("   FAILED -> " + (e.message ?: "").lines().first().take(175))
        }
    }

    /** No annotation: can the DOWNSTREAM call pin each generated field? */
    @Test fun `B1 unannotated record, pinned by the call that consumes it`() {
        show("B1  let args = { n = arbitrary {} }; f { n = args.n }", """
            let twice: { n: Int } -> Int = { n } -> n + n

            with_seed { seed = 3, call = {} -> do
              let args = { n = Arbitrary.arbitrary {} }
              { args = args, result = twice { n = args.n } }
            }
        """.trimIndent())
    }

    /** Annotated with a NAMED param type — the rendering is just the name. */
    @Test fun `B2 annotated with a named type`() {
        show("B2  let args: Args = arbitrary {}", """
            type Args = { n: Int, s: Str }
            let describe: Args -> Int = { n, s } -> n

            with_seed { seed = 3, call = {} -> do
              let args: Args = Arbitrary.arbitrary {}
              { args = args, result = describe { n = args.n, s = args.s } }
            }
        """.trimIndent())
    }

    /** Annotated with a rendered ANONYMOUS record type. */
    @Test fun `B3 annotated with a rendered record type`() {
        show("B3  let args: { n: Int, s: Str } = arbitrary {}", """
            let describe: { n: Int, s: Str } -> Int = { n, s } -> n

            with_seed { seed = 3, call = {} -> do
              let args: { n: Int, s: Str } = Arbitrary.arbitrary {}
              { args = args, result = describe { n = args.n, s = args.s } }
            }
        """.trimIndent())
    }

    /** A zero-argument function — the degenerate case the command must not crash on. */
    @Test fun `B4 a function taking no arguments`() {
        show("B4  f: {} -> Int", """
            let answer: {} -> Int = {} -> 42

            with_seed { seed = 3, call = {} -> { args = {}, result = answer {} } }
        """.trimIndent())
    }
}
