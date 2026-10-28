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
 * Before this fix any value of the type was rejected against the qualified
 * spelling — `Field 'e' expects std::syntax::Expr but got Expr` — while the same
 * signature under `use std::syntax::{ Expr }` passed. So a function written with
 * qualified types could neither accept nor return one, and writing the qualifier
 * (the thing you reach for when two modules export the same name) was the one
 * spelling that did not work.
 *
 * CAUSE: the module path was being discarded one line before it was needed.
 * `TypeChecker` walks `loadedModules: Map<ModulePath, …>` to index every loaded
 * type and wrote `transitiveTypesByHash[it.name]` — bare name, key ignored. No
 * resolver knew a qualified name, so `TypeAnnotationConverter` produced a
 * `TypeApplication` whose constructor was the whole string `std::syntax::Expr`
 * and whose hash was null: an application with no identity and a constructor no
 * declaration has, which unifies only with itself.
 *
 * FIX: index each loaded type under its qualified spelling too, so
 * `resolveTypeHash("std::syntax::Expr")` answers. Everything downstream was
 * already hash-driven and needed no change — `DeclarationRegistry.resolve`
 * prefers the hash over the name, so `declarationFor` finds the declaration and
 * variant expansion works, and `unifyTypeApplications` relates the two spellings
 * through `sameDeclaredIdentity`, the same mechanism that already lets a renamed
 * import unify with its declaration.
 *
 * Keyed by SPELLING rather than reduced to the bare name deliberately: the
 * qualifier is what the author wrote to disambiguate, and `allTypeHashesByName`
 * holds one entry per bare name across every loaded module — so resolving
 * `a::b::T` by looking up `T` would answer with whichever module won that slot.
 * That is the name-keyed guess this codebase keeps removing, not a fix.
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
     * A qualified type WITH ARGUMENTS.
     *
     * Exercised through a call rather than a bare declaration on purpose: a
     * self-unifying annotation declares perfectly well and only fails when
     * something has to match it, so a declaration alone proves nothing here.
     *
     * `Verdict` is a DECLARED type. `std::collections::map::Map` would not do:
     * `Map` is a builtin, not a member of `std::collections::map`, so that
     * spelling names nothing and no amount of resolution will make it work —
     * which is its own gap, recorded with BUG 28's neighbours.
     */
    @Test fun `a qualified type with arguments accepts a value`() {
        assertNull(
            err(
                """
                use std::property::{ Verdict }
                let takes_v: { v: std::property::Verdict Int } -> Int = { v } -> 1
                let give_it: { v: Verdict Int } -> Int = { v } -> takes_v { v = v }
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

    /** CONTROL: a genuine type mismatch through a qualified annotation is still
     *  a mismatch — resolving the spelling must not make it accept anything. */
    @Test fun `a qualified annotation still rejects the wrong type`() {
        assertNotNull(
            err(
                """
                let takes_q: { e: std::syntax::Expr } -> Int = { e } -> 1
                takes_q { e = 42 }
                """
            )
        )
    }
}
