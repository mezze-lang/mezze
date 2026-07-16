package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #76: a do-block `let` should generalize its inferred type the same way a
 * top-level `let` does, so a polymorphic lambda can be used at two different
 * types within the block. Previously do-block lets were always Monomorphic and
 * failed on the second use.
 *
 * Uses checkParsedModule so we can read the error list.
 */
class DoBlockLetPolymorphismTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun errorsFor(src: String): List<TypeChecker.TypeCheckError> {
        val parsed = parser.parseParsedModule(src, "doblock.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors
            is TypeChecker.TypeCheckResult.Success -> emptyList()
        }
    }

    @Test
    fun `do-block let identity used at two types generalizes`() {
        // `id` is used at Int and Str — must generalize like top-level.
        val src = """
            let main = {} -> do
              let id = { x } -> x
              let a = id { x = 1 }
              let b = id { x = "s" }
              a
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "expected do-block let to generalize, got: ${errors.map { it.message }}")
    }

    @Test
    fun `top-level identity used at two types still works (control)`() {
        val src = """
            let id = { x } -> x
            let a = id { x = 1 }
            let b = id { x = "s" }
            a
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "top-level control should type-check, got: ${errors.map { it.message }}")
    }

    @Test
    fun `do-block let with pinned param stays monomorphic and rejects wrong use`() {
        // `f`'s body pins x to Int (`x + 1`), so f must NOT generalize; using it
        // with a Str must fail. This guards against unsound over-generalization.
        val src = """
            let main = {} -> do
              let f = { x } -> x + 1
              let a = f { x = 1 }
              let b = f { x = "s" }
              a
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isNotEmpty(), "expected wrong-type use of a monomorphic-in-body binding to fail")
    }

    @Test
    fun `do-block lambda requiring an ability does not drop the constraint`() {
        // `show_it` is a lambda whose body needs Show on its param. Generalizing
        // it must CARRY the Show constraint — using it on a Float (no Show impl)
        // must still fail. Guards against dropping ability constraints when
        // building the generalized scheme.
        val src = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> "n"
            }
            let main = {} -> do
              let show_it = { v } -> v.show {}
              let ok = show_it { v = 1 }
              let bad = show_it { v = 2.0 }
              ok
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isNotEmpty(), "expected Show-on-Float to fail; ability constraint must not be dropped")
    }

    @Test
    fun `do-block let closing over an outer var does not over-generalize`() {
        // `add_n` captures `n: Int` from the enclosing lambda; its result is Int,
        // there is nothing to generalize, and it must type-check normally.
        val src = """
            let outer = { n: Int } -> do
              let add_n = { x: Int } -> x + n
              add_n { x = 5 }
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "capturing binding should type-check, got: ${errors.map { it.message }}")
    }

    @Test
    fun `do-block capturing closure returned from block still type-checks`() {
        // #76 regression guard: a lambda that captures an outer var (`base`) and
        // is RETURNED from the do-block is left monomorphic — generalizing a
        // capturing closure broke monomorphization/lowering (a MezzeFunction was
        // cast to MezzeRecord at runtime). Must type-check.
        val src = """
            let createAdder = { base } -> do
              let add = { x } -> { sum = x + base, base = base }
              add
            let addTen = createAdder { base = 10 }
            addTen { x = 5 }
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "capturing-closure return should type-check, got: ${errors.map { it.message }}")
    }
}
