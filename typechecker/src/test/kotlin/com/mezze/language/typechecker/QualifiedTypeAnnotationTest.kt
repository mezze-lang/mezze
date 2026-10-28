package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A module-qualified type annotation resolves to its declaration.
 *
 *     let fwd: { e: std::syntax::Expr } -> std::syntax::Expr = { e } -> e
 *
 * Any value of the type used to be rejected against the qualified spelling —
 * `Field 'e' expects std::syntax::Expr but got Expr` — while the same signature
 * under `use std::syntax::{ Expr }` passed. So the spelling you reach for when
 * two modules export the same name was the only one that did not work.
 *
 * CAUSE: the module path was discarded one line before it was needed.
 * `TypeChecker` walks `loadedModules: Map<ModulePath, …>` to index every loaded
 * type and wrote `transitiveTypesByHash[it.name]` — bare name, key ignored. No
 * resolver knew a qualified name, so the annotation became a `TypeApplication`
 * with no identity and a constructor no declaration has: it unified only with
 * itself, and the program was ACCEPTED until some concrete value had to match
 * it, at which point the error blamed the value.
 *
 * FIX, three parts, each measured:
 *
 *  1. Index every loaded type under its qualified spelling too. Downstream was
 *     already hash-driven — `DeclarationRegistry.resolve` prefers the hash, so
 *     `declarationFor` finds the declaration and variant expansion works, and
 *     `unifyTypeApplications` relates the two spellings through
 *     `sameDeclaredIdentity`, the same mechanism that lets a renamed import
 *     unify with its declaration. Keyed by SPELLING, never reduced to the bare
 *     name: `allTypeHashesByName` holds one entry per bare name across every
 *     loaded module, so looking up `T` for `a::b::T` would answer with whichever
 *     module won that slot — a name-keyed guess, not a fix.
 *  2. A GLOBALLY NAMEABLE type resolves on its last segment. A builtin belongs
 *     to no module (`Map` is not a member of `std::collections::map`), and a
 *     well-known stdlib type resolves bare with no import at all — measured:
 *     `WellKnownHashConstants.resolveTypeHash("Expr")` answers. For those the
 *     qualified and bare spellings must denote ONE type.
 *  3. A qualified spelling that resolves to NOTHING is refused. It used to be
 *     skipped, justified as "resolved by the import machinery" — written when no
 *     resolver knew a qualified name at all, so `no::such::module::Thing`
 *     type-checked.
 *
 * KNOWN COST of (2), pinned below: the qualifier goes unchecked for a global
 * type, so `std::str::Map` resolves as readily as `std::collections::map::Map`.
 * Nothing records which module such a type would belong to — for a builtin the
 * answer is "none" — so checking it would mean inventing a type-to-home-module
 * table for this one case. A wrong qualifier on a global type is a spelling slip,
 * not a type error waiting to happen.
 */
class QualifiedTypeAnnotationTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    /** null ⇒ accepted; else the rejection message. */
    private fun err(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "q.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    /** A qualified PARAMETER accepts a value of that type. */
    @Test fun `a qualified parameter accepts a value of the type`() {
        assertNull(
            err(
                """
                use std::syntax::{ Expr }
                let takes_q: { e: std::syntax::Expr } -> Int = { e } -> 1
                let give_it: { e: Expr } -> Int = { e } -> takes_q { e = e }
                """
            )
        )
    }

    /** A qualified RETURN is accepted where the unqualified type is wanted. */
    @Test fun `a qualified return feeds an unqualified parameter`() {
        assertNull(
            err(
                """
                use std::syntax::{ Expr }
                let fwd: { e: std::syntax::Expr } -> std::syntax::Expr = { e } -> e
                let plain: { e: Expr } -> Int = { e } -> 1
                let chain: { e: Expr } -> Int = { e } -> plain { e = fwd { e = e } }
                """
            )
        )
    }

    /**
     * A qualified BUILTIN is the same type as the bare spelling.
     *
     * `Map` is global — not a member of `std::collections::map` — so both
     * spellings must denote one type. Exercised by handing a `Map Str Int` to a
     * parameter written the qualified way: a bare declaration would prove
     * nothing, because a self-unifying annotation declares perfectly well and
     * only fails when something has to match it.
     */
    @Test fun `a qualified builtin unifies with the bare spelling`() {
        assertNull(
            err(
                """
                use std::collections::map
                let takes_q: { m: std::collections::map::Map Str Int } -> Int = { m } -> 1
                let give_it: { m: Map Str Int } -> Int = { m } -> takes_q { m = m }
                """
            )
        )
    }

    /**
     * A well-known stdlib type is nameable qualified with NO import, because it
     * is nameable BARE with no import — the two spellings stay symmetric.
     *
     * Before this, the qualified form alone was refused while the bare form was
     * accepted, which had the explicit spelling losing to the implicit one.
     */
    @Test fun `a qualified well-known type needs no import`() {
        assertNull(
            err(
                """
                let takes_q: { e: std::syntax::Expr } -> Int = { e } -> 1
                """
            )
        )
    }

    /** CONTROL: the unqualified spelling, which always worked. */
    @Test fun `the unqualified spelling still works`() {
        assertNull(
            err(
                """
                use std::syntax::{ Expr }
                let fwd: { e: Expr } -> Expr = { e } -> e
                let plain: { e: Expr } -> Int = { e } -> 1
                let chain: { e: Expr } -> Int = { e } -> plain { e = fwd { e = e } }
                """
            )
        )
    }

    /** A real module that declares no such type is refused, naming the spelling. */
    @Test fun `a qualified name a module does not declare is refused`() {
        val message = err(
            """
            let f: { e: std::syntax::NoSuchTypeHere } -> Int = { e } -> 1
            """
        )
        assertNotNull(message)
        assert(message!!.contains("std::syntax::NoSuchTypeHere")) {
            "the error must name the spelling that failed; got: $message"
        }
    }

    /** A module that does not exist at all is refused. */
    @Test fun `a qualified name whose module does not exist is refused`() {
        val message = err(
            """
            let f: { e: no::such::module::Thing } -> Int = { e } -> 1
            """
        )
        assertNotNull(message)
        assert(message!!.contains("no::such::module::Thing")) {
            "the error must name the spelling that failed; got: $message"
        }
    }

    /**
     * A genuine type mismatch through a qualified annotation is still a
     * mismatch: resolving the spelling must not make it accept anything.
     */
    @Test fun `a qualified annotation still rejects the wrong type`() {
        assertNotNull(
            err(
                """
                let takes_q: { m: std::collections::map::Map Str Int } -> Int = { m } -> 1
                takes_q { m = 42 }
                """
            )
        )
    }

    /**
     * THE KNOWN COST, pinned so it is a decision and not a surprise: a global
     * type under a wrong qualifier resolves anyway. See the class header.
     */
    @Test fun `a builtin under a wrong module path still resolves`() {
        assertNull(
            err(
                """
                use std::collections::map
                let takes_q: { m: std::str::Map Str Int } -> Int = { m } -> 1
                let give_it: { m: Map Str Int } -> Int = { m } -> takes_q { m = m }
                """
            )
        )
    }
}
