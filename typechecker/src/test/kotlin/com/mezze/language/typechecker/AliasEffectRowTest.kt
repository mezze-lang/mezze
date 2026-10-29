package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A `type`/`newtype` may not name a function type that performs a NAMED effect.
 *
 *     type Gen 'a = {} -> 'a <Tick>      # refused, T0219
 *
 * The row was SOUND behind the name — measured before the rule landed: making
 * one does not charge the inner effect, calling one does, and calling one
 * without declaring the effect is refused, naming it. Nothing leaked and
 * nothing was lost.
 *
 * What the alias cost is readability. `{} -> Gen Str <Other>` shows one row
 * where there are two, and the only way to find the second is to open the
 * declaration. The row cannot be moved to the use site instead, which is what
 * makes this a rule and not a style note: a row needs an ARROW to own it, so
 * `Gen Str <Tick>` is not a form at all. Either the effect hides behind the name
 * or the name goes.
 *
 * The stdlib had exactly one instance — `Sampler 'a = {} -> 'a <Sample>` — now
 * written out at its nine use sites. Its own doc already argued the generator
 * "is just a thunk"; spelling the thunk is that claim without the indirection.
 *
 * An OPEN row is refused too. It hides no PARTICULAR effect, but it widens
 * every use of the name to accept any effectful function — and against a closed
 * enclosing row those effects are absorbed, so a function can call an effectful
 * callback while declaring itself pure. A permissive default is worse behind a
 * name than written out.
 *
 * What MUST stay legal is pinned below: a record field holding an effectful
 * callback. That is how `Routes`, `RouteEntry` and `SelectBranch` are written,
 * and routing and `select` are impossible without it — the arrow there is the
 * FIELD's, not the alias's.
 */
class AliasEffectRowTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    private val effects = """
        effect Tick where { tick: {} -> Int <Tick> }
    """.trimIndent()

    /** null ⇒ accepted; else the rejection message. */
    private fun err(body: String): String? =
        try {
            tc.typeCheck(
                parser.parseParsedModule("$effects\n${body.trimIndent()}", "a.mz"),
                Paths.get("."),
                enablePrelude = true,
            )
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    /** A `type` alias naming a function with a named effect is refused. */
    @Test fun `a type alias with a named effect is refused`() {
        val message = err(
            """
            type Gen 'a = {} -> 'a <Tick>
            let ignored: {} -> Int = {} -> 0
            """
        )
        assertNotNull(message)
        assert(message!!.contains("Gen") && message.contains("Tick")) {
            "the error must name the alias and the effect it hides; got: $message"
        }
    }

    /** And a `newtype` wrapping one, for the same reason. */
    @Test fun `a newtype wrapping a function with a named effect is refused`() {
        assertNotNull(
            err(
                """
                newtype Gen = {} -> Int <Tick>
                let ignored: {} -> Int = {} -> 0
                """
            )
        )
    }

    /**
     * A record FIELD holding an effectful callback stays legal.
     *
     * The arrow is the field's, not the alias's, and this is how `Routes`,
     * `RouteEntry` and `SelectBranch` are written — the rule over-reaching here
     * would take out routing and `select`.
     */
    @Test fun `a record field holding an effectful callback is allowed`() {
        assertNull(
            err(
                """
                type Runner = { run: {} -> Int <Tick> }
                let use_it: { r: Runner } -> Int <Tick> = { r } -> r.run {}
                """
            )
        )
    }

    /**
     * …and calling that field WITHOUT declaring the effect is refused.
     *
     * The soundness half of the case above, and it matters more now than it
     * did: a record field is the only remaining way to carry a row into a type
     * declaration, so "the effect is hidden from a reader but never from the
     * checker" has to hold on this shape specifically. If this ever starts
     * passing, a record field has become a laundering route for an effect.
     */
    @Test fun `calling a record field's callback without declaring its effect is refused`() {
        val message = err(
            """
            type Runner = { run: {} -> Int <Tick> }
            let use_it: { r: Runner } -> Int = { r } -> r.run {}
            """
        )
        assertNotNull(message)
        assert(message!!.contains("Tick")) {
            "the refusal must name the effect that was not declared; got: $message"
        }
    }

    /**
     * An OPEN row target is refused too — a function alias may carry NO row.
     *
     * It hides no PARTICULAR effect, which is why it was allowed at first. But it
     * widens every use of the name to accept any effectful function, and against
     * a closed enclosing row those effects are absorbed — measured:
     * `{ g: {} -> Int <...> } -> Int` is ACCEPTED, so a function can call a
     * Tick-performing callback while declaring itself pure. A permissive default
     * is worse behind a name than written out, so the rule is the simple one: an
     * alias names the shape, the effects go on the signature that uses it.
     */
    @Test fun `an open row target is refused`() {
        val message = err(
            """
            type AnyFn = {} -> Int <...>
            let ignored: {} -> Int = {} -> 0
            """
        )
        assertNotNull(message)
        assert(message!!.contains("AnyFn")) {
            "the error must name the alias; got: $message"
        }
    }

    /** A function-typed alias with NO row is allowed — nothing to hide. */
    @Test fun `a pure function alias is allowed`() {
        assertNull(
            err(
                """
                type PureFn = {} -> Int
                let use_it: { f: PureFn } -> Int = { f } -> f {}
                """
            )
        )
    }

    /** The spelled-out form the rule pushes you to is of course allowed. */
    @Test fun `the spelled-out function type is allowed`() {
        assertNull(
            err(
                """
                let use_it: { g: {} -> Int <Tick> } -> Int <Tick> = { g } -> g {}
                """
            )
        )
    }
}
