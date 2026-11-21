package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * When the scope hooks run — in particular, whether `finalize_scope` survives
 * an `abort`.
 *
 * V0506 requires `finalize_scope` alongside `initialize_scope` "for resource
 * safety", so the guarantee is only worth having if cleanup also runs on the
 * ABORT path — the one an author is least likely to test and most likely to
 * leak on. `abort` unwinds by THROWING, and the push/pop are separate
 * operations, so this is not obvious from reading. It is pinned here because
 * nothing else in the suite asserts it.
 */
class ScopeHookLifecycleTest {

    private fun scoped(performer: String, op: String) = """
use std::effects::log::{ println, Console }

effect R where { use_it: {} -> Int <R> }

impl R for $performer where {
  initialize_scope = {} -> do
    println { msg = "INIT" }
    { n = 1 },
  finalize_scope = {} -> do
    println { msg = "FINALIZE" }
    {},
  use_it = {} -> $op
}

pub let body: {} -> Int <R> = {} -> R.use_it {}

let main = {} -> perform Console in do
  let v = perform $performer in body {}
  let s = v.to_str {}
  println { msg = "RESULT=$(s)" }
main {}
    """.trimIndent()

    @Test
    fun `finalize_scope runs when the handler resumes`() {
        val r = MezzeTestHelper.runWithOutput(scoped("Resuming", "cont.resume { val = 7 }"))
        assertTrue(r.output.contains("INIT"), "init ran:\n${r.output}")
        assertTrue(r.output.contains("FINALIZE"), "cleanup ran:\n${r.output}")
        assertTrue(r.output.contains("RESULT=7"), "body's value came out:\n${r.output}")
    }

    @Test
    fun `finalize_scope ALSO runs when the handler aborts`() {
        // THE POINT. `abort` throws, and the handler-chain push/pop are separate
        // operations, so cleanup running here is a real guarantee rather than an
        // obvious consequence. If this ever regresses, every scoped handler
        // leaks on exactly the path V0506 exists to protect.
        val r = MezzeTestHelper.runWithOutput(scoped("Aborting", "cont.abort { val = 99 }"))
        assertTrue(r.output.contains("INIT"), "init ran:\n${r.output}")
        assertTrue(
            r.output.contains("FINALIZE"),
            "cleanup must run on the ABORT path too — V0506 requires " +
                "finalize_scope 'for resource safety', which is worth nothing if " +
                "an abort skips it:\n${r.output}",
        )
        assertTrue(r.output.contains("RESULT=99"), "the abort's value came out:\n${r.output}")
    }

    @Test
    fun `the hooks run once each, not once per operation call`() {
        val src = """
use std::effects::log::{ println, Console }

effect R where { use_it: {} -> Int <R> }

impl R for Counting where {
  initialize_scope = {} -> do
    println { msg = "INIT" }
    { n = 1 },
  finalize_scope = {} -> do
    println { msg = "FINALIZE" }
    {},
  use_it = {} -> cont.resume { val = scope.n }
}

pub let body: {} -> Int <R> = {} -> do
  let a = R.use_it {}
  let b = R.use_it {}
  let c = R.use_it {}
  a + b + c

let main = {} -> perform Console in do
  let v = perform Counting in body {}
  let s = v.to_str {}
  println { msg = "RESULT=$(s)" }
main {}
        """.trimIndent()
        val r = MezzeTestHelper.runWithOutput(src)
        assertEquals(
            1, Regex("INIT").findAll(r.output).count(),
            "initialize_scope is per PERFORM, not per operation call:\n${r.output}",
        )
        assertEquals(
            1, Regex("FINALIZE").findAll(r.output).count(),
            "finalize_scope likewise:\n${r.output}",
        )
        assertTrue(r.output.contains("RESULT=3"), "three calls each read scope.n = 1:\n${r.output}")
    }
}
