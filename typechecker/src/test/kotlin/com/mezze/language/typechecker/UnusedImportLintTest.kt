package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.validation.UnusedImportLinter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * #84 linter — W0102 unused imports. The linter is a pure function of the parsed
 * AST, so it's tested directly (no typechecking / import resolution needed).
 */
class UnusedImportLintTest {
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() { parser = MezzeParser() }

    /** Local names the linter reports as unused. */
    private fun unused(code: String): List<String> {
        val module = parser.parseParsedModule(code, "test.mz")
        return UnusedImportLinter.lint(module)
            .filter { it.code == WarningCode.W0102_UNUSED_IMPORT }
            .map { it.message }
    }

    @Test
    fun `a selective import that is never used warns`() {
        val msgs = unused("""
            use std::collections::seq::{ map, filter }

            let f = {xs: [Int]} -> map { xs = xs, f = {x} -> x + 1 }
        """.trimIndent())
        assertTrue(msgs.any { it.contains("filter") }, "Expected 'filter' unused. Got: $msgs")
        assertFalse(msgs.any { it.contains("'map'") }, "'map' is used bare. Got: $msgs")
    }

    @Test
    fun `an import used only in a type annotation is not flagged`() {
        val msgs = unused("""
            use std::collections::map::{ Map }

            let describe : { m: Map Str Int } -> Str = {m} -> "a map"
        """.trimIndent())
        assertFalse(msgs.any { it.contains("'Map'") }, "'Map' is used in a type signature. Got: $msgs")
    }

    @Test
    fun `a whole-module import is never flagged (may bring impls into scope)`() {
        // Whole-module imports commonly exist to bring ability impls / instances
        // into scope (used implicitly, never by name). W0102 only checks
        // selective imports, so an unreferenced `use std::int` is left alone.
        val msgs = unused("""
            use std::int

            let f = {} -> 42
        """.trimIndent())
        assertFalse(msgs.any { it.contains("'int'") }, "Whole-module import must not be flagged. Got: $msgs")
    }

    @Test
    fun `a selective import used only in an impl is not flagged`() {
        val msgs = unused("""
            use std::codec::core::{ Format }
            type Foo = { x: Int }
            impl Format for Foo having 'out = Str where { }
        """.trimIndent())
        assertFalse(msgs.any { it.contains("'Format'") }, "'Format' is used in an impl. Got: $msgs")
    }

    @Test
    fun `a fully-used import produces no warning`() {
        val msgs = unused("""
            use std::collections::seq::{ map }

            let f = {xs: [Int]} -> map { xs = xs, f = {x} -> x + 1 }
        """.trimIndent())
        assertTrue(msgs.isEmpty(), "Expected no unused-import warnings. Got: $msgs")
    }

    @Test
    fun `an import used in a constructor reference is not flagged`() {
        val msgs = unused("""
            use std::option::{ Some }

            let f = {} -> Some { val = 42 }
        """.trimIndent())
        assertFalse(msgs.any { it.contains("'Some'") }, "'Some' is used as a constructor. Got: $msgs")
    }

    @Test
    fun `a fully-unused selective import warns`() {
        val msgs = unused("""
            use std::collections::seq::{ map, filter }

            let f = {} -> 42
        """.trimIndent())
        assertTrue(msgs.any { it.contains("map") }, "Expected 'map' flagged unused. Got: $msgs")
        assertTrue(msgs.any { it.contains("filter") }, "Expected 'filter' flagged unused. Got: $msgs")
    }

    @Test
    fun `unusedItemsFor reports only the unused items of a partly-used import`() {
        // Backs the LSP's surgical "remove just these items" quick-fix: `map` is
        // used, `filter` and `reduce` are not, so only the latter two come back.
        val module = parser.parseParsedModule("""
            use std::collections::seq::{ map, filter, reduce }

            let f = {xs: [Int]} -> map { xs = xs, f = {x} -> x + 1 }
        """.trimIndent(), "test.mz")
        val useDecl = module.decls
            .filterIsInstance<com.mezze.language.parsedast.ParsedUseDecl>()
            .single()
        val unused = UnusedImportLinter.unusedItemsFor(module, useDecl)
        assertTrue("filter" in unused && "reduce" in unused,
            "Expected filter + reduce unused. Got: $unused")
        assertFalse("map" in unused, "map is used bare. Got: $unused")
    }

    // ---- positions the reference walkers reach and this linter used not to ----
    //
    // A false W0102 is worse than a missed one: acting on it (deleting the import)
    // breaks the build. Each case below is a position where `ParsedExpr.children()`
    // / `ParsedTypeAnnotation.children()` see a reference and the linter's own
    // recursion did not.

    @Test
    fun `an import used only inside a string interpolation is not flagged`() {
        // #160's shape: `ParsedStringLit` looks like a leaf, but `$( … )` holes are
        // sub-expressions. The linter treated the literal as childless.
        val msgs = unused("""
            use std::collections::seq::{ sum }

            let report = {xs: [Int]} -> "total: $(sum { xs = xs })"
        """.trimIndent())
        assertFalse(msgs.any { it.contains("sum") }, "'sum' is used in an interpolation hole. Got: $msgs")
    }

    @Test
    fun `an import used only in an effect row's having binding is not flagged`() {
        // `{ … } -> T <Exception having 'error = (Map Str Int)>` — the annotation
        // hangs off the effect row, which the type walk stopped short of.
        val msgs = unused("""
            use std::collections::map::{ Map }
            use std::effects::exception::{ Exception }

            let risky : { x: Int } -> Int <Exception having 'error = (Map Str Int)> = {x} -> x
        """.trimIndent())
        assertFalse(msgs.any { it.contains("'Map'") }, "'Map' is used in the effect row. Got: $msgs")
    }

    @Test
    fun `an import used only in a with-clause having binding is not flagged`() {
        // `with Format 'a having 'out = Map Str Int` — same gap on the other side
        // of the arrow's constraint list.
        val msgs = unused("""
            use std::collections::map::{ Map }
            use std::codec::core::{ Format }

            let render : { x: 'a } with Format 'a having 'out = (Map Str Int) -> Int = {x} -> 1
        """.trimIndent())
        assertFalse(msgs.any { it.contains("'Map'") }, "'Map' is used in the with-clause binding. Got: $msgs")
    }
}
