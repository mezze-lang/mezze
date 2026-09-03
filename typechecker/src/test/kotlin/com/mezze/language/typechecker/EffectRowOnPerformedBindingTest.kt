package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.langservice.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A do-block `let` binding whose RHS PERFORMS an effect must carry that effect
 * row on the binding's type — effects ride on value types, not only on arrows.
 *
 *   let var = if b == 0 then Exception.throw { err = "x" } else b
 *
 * must give `var : Int <Exception having 'err = Str>`, NOT bare `Int` (the effect
 * laundered off the binding). Today it comes out bare — this test pins the fix.
 */
class EffectRowOnPerformedBindingTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun bindingType(src: String, line: Int, col: Int): String {
        val parsed = parser.parseParsedModule(src, "eff.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: throw AssertionError("expected typecheck success, got: $r")
        val tm = success.typedModule ?: throw AssertionError("no typed module")
        return when (val hit = TypedAstLookup.findAtPosition(tm, line, col)) {
            is TypedAstLookup.LookupResult.Binding -> prettyPrintType(hit.type)
            is TypedAstLookup.LookupResult.Expr -> prettyPrintType(hit.expr.type)
            else -> throw AssertionError("nothing at $line:$col: $hit")
        }
    }

    @Test
    fun `performed do-block binding is bare -- the effect rides the enclosing arrow`() {
        val src = """
            use std::effects::exception::{ Exception }

            let f = { b } -> do
              let var = if b == 0 then Exception.throw { error = "x" } else b
              var
        """.trimIndent()
        // Effect-model migration: the binding is BARE (`var : Int`) — the performed
        // Exception no longer rides on the value; it rides on `f`'s ARROW.
        val varTy = bindingType(src, 4, 7) // `var` binding
        assertTrue(varTy == "Int") { "expected bare `var : Int`, got: $varTy" }
        // The enclosing function `f` carries <Exception ...> on its arrow.
        val fTy = bindingType(src, 3, 5) // `f` decl name
        assertTrue(fTy.contains("Exception")) {
            "expected `f`'s arrow to carry <Exception ...>, got: $fTy"
        }
    }

    @Test
    fun `a pure do-block binding stays bare with no effect row`() {
        // Only genuinely-performed effects ride on the binding. A pure RHS keeps
        // the bare value type — no spurious empty/`<*>` row.
        val src = """
            let f = {} -> do
              let x = 5
              x
        """.trimIndent()
        // `x` binding at line 2, col 7.
        val ty = bindingType(src, 2, 7)
        assertTrue(ty == "Int") {
            "expected pure binding `x` to stay bare `Int`, got: $ty"
        }
    }

    @Test
    fun `a performed binding is still usable as its bare value type downstream`() {
        // The row is provenance on the STORED type; `var` is still an Int value.
        // Using it in an Int-demanding position (`.add`) must type-check, i.e.
        // carrying the row does not break ordinary value use. The whole program
        // type-checking is the assertion.
        val src = """
            use std::effects::exception::{ Exception }

            let f = { b } -> do
              let var = if b == 0 then Exception.throw { error = "x" } else b
              var + 1
        """.trimIndent()
        val parsed = parser.parseParsedModule(src, "eff.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        assertTrue(r is TypeChecker.TypeCheckResult.Success) {
            "expected `var` to remain usable as Int downstream, got: $r"
        }
    }
}
