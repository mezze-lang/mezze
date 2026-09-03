package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.langservice.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A `let` inside a do-block must record the same type inference gave it.
 *
 * Hover, inlay hints and go-to-definition all read the binding type out of the
 * TypedAST rather than re-inferring, so a binding stored with the wrong type is
 * wrong everywhere the editor shows it — even while the program itself
 * typechecks, because the function's own return type is computed separately.
 *
 * The case that exposed this: `{ ...point, x = 10 }` in a do-block stored
 * `{ x: Int }`, dropping every field the spread contributed, while the
 * enclosing function still returned the correct `{ x: Int, y: Int }`.
 */
class DoBlockSpreadBindingTypeTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /** The type recorded for the binding at (line, col), as hover would show it. */
    private fun bindingTypeAt(src: String, line: Int, col: Int): String {
        val parsed = parser.parseParsedModule(src, "spread.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: fail("expected the program to typecheck, got: $r")
        val tm = success.typedModule ?: fail("no typed module")
        return when (val hit = TypedAstLookup.findAtPosition(tm, line, col)) {
            is TypedAstLookup.LookupResult.Binding -> prettyPrintType(hit.type)
            is TypedAstLookup.LookupResult.Expr -> prettyPrintType(hit.expr.type)
            else -> fail("nothing found at $line:$col")
        }
    }

    @Test
    fun `do-block let of a spread keeps every field`() {
        val src = """
            let point = { x = 3, y = 4 }
            let f = {} -> do
              let moved = { ...point, x = 10 }
              moved
        """.trimIndent()

        // `moved` is on line 3, col 7.
        assertEquals("{ x: Int, y: Int }", bindingTypeAt(src, 3, 7))
    }

    @Test
    fun `top-level let of a spread keeps every field`() {
        // The same expression at the top level already worked; pinned so a fix
        // to the do-block path cannot regress it.
        val src = """
            let point = { x = 3, y = 4 }
            let moved = { ...point, x = 10 }
        """.trimIndent()

        assertEquals("{ x: Int, y: Int }", bindingTypeAt(src, 2, 5))
    }
}
