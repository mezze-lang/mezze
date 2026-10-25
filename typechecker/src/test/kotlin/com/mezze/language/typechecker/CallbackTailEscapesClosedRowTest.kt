package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A callback's `...` tail escapes a CLOSED enclosing row without being named.
 *
 *     let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom> = { f } -> f { x = 1 }
 *
 * `runs` calls its callback INLINE, in its own frame, so whatever that callback
 * performs, `runs` performs. Its row says `<Boom>` and nothing else — so a
 * caller passing a callback that also performs `Noise` gets `Noise` performed
 * through a function whose type never mentions it.
 *
 * `<Boom, ...>` means AT LEAST Boom (enforced since the lower-bound fix), and
 * `...` is the rest — which `runs` must either name, pass on with its own
 * `...`, or handle and mark with `*`. It does none of these and is accepted.
 *
 * Where the error belongs is the CALLER: `runs` itself is a legitimate
 * declaration — a callback performing only Boom satisfies it — and it is the
 * call supplying a Noise-performing callback that cannot be honoured.
 */
class CallbackTailEscapesClosedRowTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val head = """
        effect Boom where { bang: { msg: Str } -> Str <Boom> }
        effect Noise where { shout: { msg: Str } -> Str <Noise> }
        impl Boom for Banger where { bang = { msg } -> msg }
        impl Noise for Shouter where { shout = { msg } -> msg }
    """.trimIndent()

    /** THE hole: Noise reaches the caller through a row naming only Boom. */
    @Test fun `a callback effect outside the enclosing row is rejected`() {
        assertNotNull(err("""
            $head
            let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom> = { f } -> f { x = 1 }
            let main = {} -> perform Shouter in perform Banger in runs { f = { x } -> do
              let a = Boom.bang { msg = "b" }
              let b = Noise.shout { msg = "n" }
              a }
            main {}
        """), "Noise escapes `runs`, whose row says only <Boom>")
    }

    /** The control: a callback performing exactly what the row names is fine. */
    @Test fun `a callback performing only the named effect is accepted`() {
        assertNull(err("""
            $head
            let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom> = { f } -> f { x = 1 }
            let main = {} -> perform Banger in runs { f = { x } -> Boom.bang { msg = "b" } }
            main {}
        """))
    }

    /** And an OPEN enclosing row carries the tail out honestly. */
    @Test fun `an open enclosing row accepts the extra effect`() {
        assertNull(err("""
            $head
            let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom, ...> = { f } -> f { x = 1 }
            let main = {} -> perform Shouter in perform Banger in runs { f = { x } -> do
              let a = Boom.bang { msg = "b" }
              let b = Noise.shout { msg = "n" }
              a }
            main {}
        """))
    }
}
