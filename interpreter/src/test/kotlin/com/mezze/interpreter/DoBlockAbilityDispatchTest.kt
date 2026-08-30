package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression for #344.
 *
 * An ability method call on a do-block `let`-bound variable that has a NAMED type
 * annotation crashed at interpreter runtime with `Field not found: <op>`, while the
 * identical binding hoisted to the TOP LEVEL worked.
 *
 *   type Post = { user: Str }
 *   impl Summary for Post where { summarize = { it } -> it.user }
 *   let main = {} -> perform Console in do
 *     let post : Post = { user = "amina" }     -- do-block binding
 *     println { msg = post.summarize {} }      -- CRASHED: Field not found: summarize
 *
 * Root cause: a record-literal value has `TypeIdentity.Anonymous`. At emit, the
 * do-block binding took the value's bare structural `RecordType` as its type instead
 * of the WRITTEN annotation (`Post`), so `post`'s receiver type at the call site had
 * no nominal identity. Runtime ability dispatch matches the receiver's type against
 * each impl's declared target — a nameless record matches no `impl Summary for Post`,
 * so the call fell back to a plain record field-access and crashed. A TOP-LEVEL let
 * already keeps the annotated type (ConstraintGenerator's authoritativeType).
 *
 * Fix: the do-block let emitter binds to the annotation's type when one is written
 * (and it resolves to a concrete shape), mirroring the top-level path — the
 * typechecker has already proved the value conforms, so this is pure
 * identity-preservation, not a re-check.
 */
class DoBlockAbilityDispatchTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `ability method on an annotated do-block record binding dispatches`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for Post where { summarize = { it } -> it.user }
            let main = {} -> do
              let post : Post = { user = "amina" }
              post.summarize {}
            main {}
        """.trimIndent()
        assertEquals("amina", MezzeTestHelper.run(src))
    }

    /** The same binding hoisted to the top level has always worked — keep it as a guard. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `ability method on an annotated top-level record binding dispatches`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for Post where { summarize = { it } -> it.user }
            let post : Post = { user = "amina" }
            let main = {} -> post.summarize {}
            main {}
        """.trimIndent()
        assertEquals("amina", MezzeTestHelper.run(src))
    }

    /** The exact reported repro: do-block binding under `perform Console`, printed. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `ability method on a do-block binding under perform Console prints`() {
        val src = """
            use std::effects::log::{ Console, println }
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for Post where { summarize = { it } -> it.user }
            let main = {} -> perform Console in do
              let post : Post = { user = "amina" }
              println { msg = post.summarize {} }
            main {}
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(src)
        assertEquals("amina", result.output().trim())
    }

    // The regression this fixes: a record literal whose shape matches an in-scope
    // `type X = {..}` ADOPTS `X` even WITHOUT an annotation ("if a type alias
    // matching a record shape is in scope, we use it. ALWAYS"). So the ability call
    // dispatches on an UNANNOTATED binding too — top-level AND in a do-block. This
    // regressed when `type X = {..}` became a transparent alias and record-literal
    // alias adoption was retired to a no-op (9d03c52d2).

    /** Unannotated top-level binding: the literal adopts `Post` by shape. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `ability method on an unannotated top-level record binding dispatches by shape`() {
        val src = """
            use std::effects::log::{ Console, println }
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str, body: Str }
            impl Summary for Post where { summarize = { it } -> it.user }
            let post = { user = "amina", body = "shipped" }
            let main = {} -> perform Console in println { msg = post.summarize {} }
            main {}
        """.trimIndent()
        assertEquals("amina", MezzeTestHelper.runWithOutput(src).output().trim())
    }

    /** Unannotated do-block binding: same adoption by shape, dispatches. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `ability method on an unannotated do-block record binding dispatches by shape`() {
        val src = """
            use std::effects::log::{ Console, println }
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str, body: Str }
            impl Summary for Post where { summarize = { it } -> it.user }
            let main = {} -> perform Console in do
              let post = { user = "amina", body = "shipped" }
              println { msg = post.summarize {} }
            main {}
        """.trimIndent()
        assertEquals("amina", MezzeTestHelper.runWithOutput(src).output().trim())
    }

    /** The reported repro, UNANNOTATED, under `perform Console`. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `ability method on an unannotated do-block binding under perform Console prints`() {
        val src = """
            use std::effects::log::{ Console, println }
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str, body: Str }
            impl Summary for Post where { summarize = { it } -> it.user }
            let main = {} -> perform Console in do
              let post = { user = "amina", body = "bug fix and shipped it" }
              println { msg = post.summarize {} }
            main {}
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(src)
        assertEquals("amina", result.output().trim())
    }
}
