package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Where does a receiver-less ability op get its return type pinned FROM?
 *
 * No effects here at all — this isolates pinning from everything S-1 was about.
 * `Maker.make` takes no argument, so only the expected type can select an impl;
 * the question is which positions actually supply one.
 *
 * This decides whether S-2's generated `Arbitrary` bodies can be written as plain
 * record literals or have to annotate every component.
 */
class StaticAbilityOpPinningTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val decls = """
        ability Maker for 'a where {
          make: {} -> 'a
        }

        impl Maker for Int where {
          make = {} -> 7
        }

        type Point = { x: Int, y: Int }
    """.trimIndent()

    private fun show(label: String, body: String) {
        System.err.println("=== $label ===")
        try {
            System.err.println("   OK -> " + MezzeTestHelper.run("$decls\n\n$body"))
        } catch (e: Exception) {
            System.err.println("   FAILED -> " + (e.message ?: "").lines().first().take(200))
        }
    }

    /** An annotated `let` — the form the error message itself recommends. */
    @Test
    fun `P1 annotated let`() {
        show("P1  let v: Int = Maker.make {}", "do\n  let v: Int = Maker.make {}\n  v")
    }

    /** A record field, where the ANNOTATED LET supplies the record's type. */
    @Test
    fun `P2 record field under an annotated let`() {
        show(
            "P2  let p: Point = { x = Maker.make {}, y = Maker.make {} }",
            "do\n  let p: Point = { x = Maker.make {}, y = Maker.make {} }\n  p.x",
        )
    }

    /** The IMPL BODY case — what a derived impl would actually be. */
    @Test
    fun `P3 record literal as an impl body`() {
        show(
            "P3  impl Maker for Point { make = {} -> { x = Maker.make {}, ... } }",
            """
            impl Maker for Point where {
              make = {} -> { x = Maker.make {}, y = Maker.make {} }
            }

            do
              let p: Point = Maker.make {}
              p.x
            """.trimIndent(),
        )
    }

    /** Impl body with each component pinned by an annotated let — the fallback. */
    @Test
    fun `P4 impl body with annotated lets per component`() {
        show(
            "P4  impl body, `let x: Int = Maker.make {}` per field",
            """
            impl Maker for Point where {
              make = {} -> do
                let x: Int = Maker.make {}
                let y: Int = Maker.make {}
                { x = x, y = y }
            }

            do
              let p: Point = Maker.make {}
              p.x
            """.trimIndent(),
        )
    }

    /** An ARGUMENT position — how `mez sample` will call the function under test. */
    @Test
    fun `P5 argument position`() {
        show(
            "P5  takes_point { p = Maker.make {} }",
            """
            let takes_point: { p: Point } -> Int = { p } -> p.x

            impl Maker for Point where {
              make = {} -> do
                let x: Int = Maker.make {}
                let y: Int = Maker.make {}
                { x = x, y = y }
            }

            takes_point { p = Maker.make {} }
            """.trimIndent(),
        )
    }

    /** A variant payload, built the way Mezze builds one: `Ctor { field = v }`. */
    @Test
    fun `P6 variant payload position`() {
        show(
            "P6  impl body returning `Wrapped { val = Maker.make {} }`",
            """
            type Wrap = | Wrapped { val: Int } |

            impl Maker for Wrap where {
              make = {} -> Wrapped { val = Maker.make {} }
            }

            do
              let w: Wrap = Maker.make {}
              match w is
                Wrapped { val } -> val
            """.trimIndent(),
        )
    }
}
