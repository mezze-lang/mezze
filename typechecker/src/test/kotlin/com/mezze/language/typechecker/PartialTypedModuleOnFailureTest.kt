package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #218 follow-up: when one decl fails to typecheck, surviving sibling
 * decls used to lose their types — the typechecker bailed at the first
 * Failure return without emitting a TypedAST. The LSP then had nothing to
 * power hover, inlay hints, or go-to-def for the rest of the file.
 *
 * Fix: collect non-fatal errors instead of returning early, build the
 * TypedAST from partial constraint results, and return Failure with both
 * the errors and the partial typedModule. These tests pin the new shape:
 * Failure now carries a typedModule when one could be emitted.
 */
class PartialTypedModuleOnFailureTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `Failure carries a partial typedModule when one decl errors but siblings are fine`() {
        // `bad` will fail (non-record receiver). `good` and `also_good`
        // should still get types so editors can hover them.
        val src = """
            let bad = { x } -> x.no_such_method {}
            let good = { y } -> y
            let also_good = {} -> 42
        """.trimIndent()
        val parsed = parser.parseParsedModule(src, "test.mz")
        val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = false)

        // It's a Failure (or Failure-with-recovery) because `bad` errored.
        // The contract is: typedModule must not be null.
        val typedModule = r.typedModule
        assertNotNull(typedModule, "Failure should still emit a partial typedModule")

        val letDecls = typedModule!!.decls.filterIsInstance<TypedLetDecl>()
        // `good` and `also_good` should be present in the partial AST.
        val names = letDecls.map { it.name }.toSet()
        assertTrue("good" in names, "expected `good` in partial typedModule, got: $names")
        assertTrue("also_good" in names, "expected `also_good` in partial typedModule, got: $names")
    }

}
