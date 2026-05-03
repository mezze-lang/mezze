package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #232: each TypedParam in an untyped lambda used to carry the
 * lambda's whole loc, so hovering on a sibling param in the LSP
 * resolved to the first param. Parser now records per-name locs and
 * TypedAstBuilder threads them through.
 *
 * Tests: every param's loc is distinct and points at its own name
 * token's column range.
 */
class TypedParamLocTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun firstLambda(code: String): TypedLambda {
        val parsed = parser.parseParsedModule(code, "test.mz")
        val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = false)
        val module = r.typedModule!!
        val let = module.decls.filterIsInstance<TypedLetDecl>().first()
        return let.value as TypedLambda
    }

    @Test
    fun `each untyped param has its own narrow loc, not the lambda loc`() {
        // {x, y, z} on one line — each name is in a distinct column.
        // 0         1         2
        // 0123456789012345678901234567
        // let f = {x, y, z} -> x + y + z
        //          ^  ^  ^
        //          9  12 15
        val lambda = firstLambda("let f = {x, y, z} -> x + y + z")
        assertEquals(3, lambda.params.size, "expected 3 params")

        val xLoc = lambda.params[0].loc
        val yLoc = lambda.params[1].loc
        val zLoc = lambda.params[2].loc

        // All three locs must be distinct — that was the bug.
        assertNotEquals(xLoc, yLoc, "x and y param locs must differ")
        assertNotEquals(yLoc, zLoc, "y and z param locs must differ")
        assertNotEquals(xLoc, zLoc, "x and z param locs must differ")

        // Locs should be MUCH narrower than the lambda's full loc — each
        // name is one column wide.
        for (p in lambda.params) {
            val width = p.loc.endCol - p.loc.startCol + 1
            assertTrue(width <= 2,
                "${p.name} loc width should be 1, got $width (loc=${p.loc})")
        }

        // Order must reflect source: x's column < y's column < z's column.
        assertTrue(xLoc.startCol < yLoc.startCol,
            "x must come before y on the same line; xLoc=$xLoc yLoc=$yLoc")
        assertTrue(yLoc.startCol < zLoc.startCol,
            "y must come before z on the same line; yLoc=$yLoc zLoc=$zLoc")
    }

    @Test
    fun `single param keeps a real per-name loc, not the whole lambda`() {
        val lambda = firstLambda("let g = {n} -> n + 1")
        assertEquals(1, lambda.params.size)
        val n = lambda.params[0]
        // The name `n` is one column wide. The lambda's full loc spans
        // the entire `{n} -> n + 1`, so a per-name loc must be shorter.
        val nameWidth = n.loc.endCol - n.loc.startCol + 1
        val lambdaWidth = lambda.loc.endCol - lambda.loc.startCol + 1
        assertTrue(nameWidth < lambdaWidth,
            "single-param loc must be narrower than the lambda's loc; " +
                "name=${n.loc} lambda=${lambda.loc}"
        )
    }
}
