package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<E? having 'a = T, ...>` — an effect a callback MAY perform.
 *
 * A row had two shapes and neither fit a callback slot whose enclosing function
 * DISCHARGES the effect. `<...>` is zero or more, so the thrown type is absorbed
 * without ever being checked. `<E, ...>` is AT LEAST E, so every callback has to
 * perform it and a pure one is refused. What was wanted is the upper bound: may
 * perform E, and if it does, with THIS instance.
 *
 * `http_router` is the case that forced it. Its verbs catch Exception for the
 * handler, so `.get { handler = { m } -> "plain" }` must pass while
 * `.get { handler = { m } -> throw { error = { why = "…" } } }` must be refused
 * for throwing a non-Response. Folding `catching` into the verbs was tried,
 * reverted for exactly this reason, and the note left behind named this
 * notation as what it would take. HttpIntoResponseTest pins the router end.
 *
 * Only the LOWER bound is lifted. The third case is the one that earns the
 * notation: naming the effect still checks the instance when it IS performed,
 * which a bare `<...>` does not.
 */
class OptionalEffectRowTest {
    private fun error(src: String): String? =
        try {
            TypeChecker().typeCheck(
                MezzeParser().parseParsedModule(src.trimIndent(), "t.mz"),
                Paths.get("."),
                enablePrelude = true,
            )
            null
        } catch (e: Throwable) {
            e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName
        }

    /** Catches Exception from its callback and names the type it may throw. */
    private val runs = """
        use std::effects::exception::{ ExceptionToResult, throw }
        let runs
          : { f: {} -> 'a <Exception? having 'error = Str, ...> }
          -> Str <*>
          = { f } -> do
            let outcome = perform ExceptionToResult in do
              let v = f {}
              Ok { val = "ok" }
            match outcome is
              Ok { val } -> val
              Err { error } -> error
    """.trimIndent()

    @Test fun `a pure callback satisfies an optional effect`() {
        assertNull(
            error("$runs\nruns { f = {} -> 7 }"),
            "a callback that never throws must satisfy `Exception?`",
        )
    }

    @Test fun `a callback throwing the declared type is accepted`() {
        assertNull(
            error("$runs\nruns { f = {} -> throw { error = \"boom\" } }"),
            "a callback throwing the declared Str must be accepted",
        )
    }

    /**
     * The reason to name the effect at all. `<...>` would take this silently and
     * the mistake would surface at run time, which is what the first fold of
     * `catching` into the router's verbs did.
     */
    @Test fun `a callback throwing the WRONG type is refused`() {
        val message = error("$runs\nruns { f = {} -> throw { error = 7 } }")
        assertTrue(
            message != null && message.contains("Int") && message.contains("Str"),
            "throwing an Int where Str is declared must be refused naming both; got: " +
                (message ?: "accepted"),
        )
    }

    /** Without the `?`, the same program's pure callback is still refused. */
    @Test fun `a mandatory effect still rejects a pure callback`() {
        val mandatory = runs.replace("Exception? having", "Exception having")
        val message = error("$mandatory\nruns { f = {} -> 7 }")
        assertTrue(
            message != null,
            "`<Exception, ...>` is a LOWER bound and must still refuse a pure callback; " +
                "if this stops failing, the `?` has stopped being the thing that lifts it",
        )
    }
}
