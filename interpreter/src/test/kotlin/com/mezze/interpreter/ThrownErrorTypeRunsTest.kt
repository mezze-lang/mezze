package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A declared thrown error type, CONVERTED in the `Err` arm, at run time.
 *
 * `ThrownErrorTypeThreadsTest` in the typechecker proves these programs type
 * check — that was the gap, and it is fixed. It does not run them, and the
 * conversion in the `Err` arm needs a dictionary for a constraint declared on
 * the enclosing function, which is a separate machine. These run them.
 *
 * Worth having because the whole point of the type reaching the arm is to apply
 * an ability to it there, so type-checking alone proves half the feature. The
 * single-module shapes below all work, including two constraints discharged one
 * per arm, which is what a `catching_any` in the router would need.
 */
class ThrownErrorTypeRunsTest {
    private val conv = """
        use std::effects::exception::{ ExceptionToResult, throw }
        ability Conv for 'e where { conv: { it: 'e } -> Str }
        impl Conv for Str where { conv = { it } -> it }
    """.trimIndent()

    private fun run(src: String): String =
        MezzeTestHelper.run(src.trimIndent()).toString()

    @Test fun `a declared thrown error type is converted in the Err arm`() {
        assertEquals(
            "boom",
            run(
                """
                $conv
                let adapt
                  : { f: {} -> 'r <Exception having 'error = 'e, ...> }
                  with Conv 'e
                  -> Str <*>
                  = { f } -> do
                    let outcome = perform ExceptionToResult in do
                      let answered = f {}
                      Ok { val = "ok" }
                    match outcome is
                      Ok { val } -> val
                      Err { error } -> error.conv {}
                adapt { f = {} -> throw { error = "boom" } }
                """
            ),
        )
    }

    /** The control: the same shape with the thrown type written out. */
    @Test fun `a concrete thrown error type is converted in the Err arm`() {
        assertEquals(
            "boom",
            run(
                """
                $conv
                let adapt
                  : { f: {} -> 'r <Exception having 'error = Str, ...> }
                  -> Str <*>
                  = { f } -> do
                    let outcome = perform ExceptionToResult in do
                      let answered = f {}
                      Ok { val = "ok" }
                    match outcome is
                      Ok { val } -> val
                      Err { error } -> error.conv {}
                adapt { f = {} -> throw { error = "boom" } }
                """
            ),
        )
    }

    /** No perform region: a declared constraint used on a plain Result. */
    @Test fun `a declared constraint on a plain Result is discharged`() {
        assertEquals(
            "boom",
            run(
                """
                $conv
                let describe: { r: Result Str 'e } with Conv 'e -> Str = { r } -> match r is
                  Ok { val } -> val
                  Err { error } -> error.conv {}
                describe { r = Err { error = "boom" } }
                """
            ),
        )
    }

    /**
     * TWO constraints, one discharged in each arm — the shape a `catching_any`
     * needs, where the Ok arm converts the answer and the Err arm converts the
     * thrown error through a DIFFERENT ability.
     */
    @Test fun `two constraints are discharged one per arm`() {
        assertEquals(
            "boom",
            run(
                """
                use std::effects::exception::{ ExceptionToResult, throw }
                ability A for 'a where { ca: { it: 'a } -> Str }
                ability B for 'e where { cb: { it: 'e } -> Str }
                impl A for Int where { ca = { it } -> "an int" }
                impl B for Str where { cb = { it } -> it }

                let adapt
                  : { f: {} -> 'r <Exception having 'error = 'e, ...> }
                  with A 'r, B 'e
                  -> Str <*>
                  = { f } -> do
                    let outcome = perform ExceptionToResult in do
                      let answered = f {}
                      Ok { val = answered.ca {} }
                    match outcome is
                      Ok { val } -> val
                      Err { error } -> error.cb {}
                adapt { f = {} -> throw { error = "boom" } }
                """
            ),
        )
    }
}
