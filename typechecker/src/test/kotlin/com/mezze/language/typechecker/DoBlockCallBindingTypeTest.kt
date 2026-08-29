package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A do-block `let` must record the type inference gave it, for calls as well
 * as literals.
 *
 * Sibling of DoBlockSpreadBindingTypeTest: the same emit path stored a record
 * literal without its spread fields. Here it is a call whose result type is
 * only known once the solver has run — the enclosing function's return type
 * comes out right while the binding keeps the unsolved variable, so hover and
 * inlay hints show `'a` for something the compiler knows exactly.
 */
class DoBlockCallBindingTypeTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun bindingTypeAt(src: String, line: Int, col: Int): String {
        val parsed = parser.parseParsedModule(src, "call.mz")
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
    fun `do-block let of a generic call records the solved type`() {
        val src = """
            let id: { v: 'a } -> 'a = { v } -> v

            let f = {} -> do
              let s = id { v = "hello" }
              s
        """.trimIndent()

        // `s` is on line 4, col 7.
        assertEquals("Str", bindingTypeAt(src, 4, 7))
    }

    @Test
    fun `top-level let of a generic call records the solved type`() {
        val src = """
            let id: { v: 'a } -> 'a = { v } -> v
            let s = id { v = "hello" }
        """.trimIndent()

        assertEquals("Str", bindingTypeAt(src, 2, 5))
    }

    @Test
    fun `a do-block binding keeps its declared type name`() {
        // The same annotated value resolves .get_or_else at the top level and
        // fails inside a do-block with "Cannot access field 'get_or_else' on
        // variant type: | Ok { val: Str }, Err { error: Str } |" — the binding
        // kept the structural variant and lost the name `Result`, so method
        // lookup had nothing to match the impl against.
        val src = """
            let top: Result Str Str = Ok { val = "hi" }
            let a = top.get_or_else { def = "?" }

            let f = {} -> do
              let inner: Result Str Str = Ok { val = "hi" }
              inner.get_or_else { def = "?" }
        """.trimIndent()

        val parsed = parser.parseParsedModule(src, "name.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        assertTrue(
            r is TypeChecker.TypeCheckResult.Success,
            "expected it to typecheck; got: " + (r as? TypeChecker.TypeCheckResult.Failure)
                ?.errors?.joinToString("\n") { it.message },
        )
    }
}
