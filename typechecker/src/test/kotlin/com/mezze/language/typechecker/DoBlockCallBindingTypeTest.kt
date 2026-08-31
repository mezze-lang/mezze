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
        // The same annotated value resolves .get_or at the top level and
        // fails inside a do-block with "Cannot access field 'get_or' on
        // variant type: | Ok { val: Str }, Err { error: Str } |" — the binding
        // kept the structural variant and lost the name `Result`, so method
        // lookup had nothing to match the impl against.
        val src = """
            let top: Result Str Str = Ok { val = "hi" }
            let a = top.get_or { def = "?" }

            let f = {} -> do
              let inner: Result Str Str = Ok { val = "hi" }
              inner.get_or { def = "?" }
        """.trimIndent()

        val parsed = parser.parseParsedModule(src, "name.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        assertTrue(
            r is TypeChecker.TypeCheckResult.Success,
            "expected it to typecheck; got: " + (r as? TypeChecker.TypeCheckResult.Failure)
                ?.errors?.joinToString("\n") { it.message },
        )
    }

    @Test
    fun `top-level let of an inherent-method call records the solved type`() {
        val src = """
            use std::result::{ get_or }

            let top: Result Str Str = Ok { val = "hi" }
            let a = top.get_or { def = "?" }
        """.trimIndent()

        // `a` is on line 4, col 5.
        assertEquals("Str", bindingTypeAt(src, 4, 5))
    }

    @Test
    fun `S1_2 do-block let of a filter result has a concrete Seq type available`() {
        // S1.2: the local incremental solve resolves `kept`'s binding type to a
        // concrete `[...]` BEFORE the next statement (`kept.map {…}`) generates,
        // instead of leaving a raw VarType that only the letBoundExprs crutch
        // could see through. The recorded binding type must be a Seq, not `'a`.
        val src = """
            use std::collections::seq::{ filter, map }
            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              let kept = xs.filter { p = { x } -> x.ok }
              kept.map { f = { x } -> x.qty }
        """.trimIndent()

        // `kept` is on line 4, col 7.
        val t = bindingTypeAt(src, 4, 7)
        assertTrue(t.startsWith("["), "expected a concrete Seq type for `kept`, got: $t")
        assertFalse(t.contains("'"), "expected no unsolved type var in `kept`'s type, got: $t")
    }

    @Test
    fun `a do-block let of an effect operation records the solved type`() {
        // `Async.spawn { f = {} -> "hello" }` binds `Promise 'a`, not
        // `Promise Str`. The same expression at the top level gets it right,
        // and so does the enclosing function's return type — only the do-block
        // binding keeps the unsolved variable.
        //
        // Ruled out while narrowing this:
        //   - not the emitter: substitution.apply(value.type) returns
        //     `Promise 'a`, so the solver never pinned the variable
        //   - not missing instantiation: the op type IS freshly instantiated
        //   - not the annotation path: there is no annotation here, so the fix
        //     in 98fdd87fd does not apply
        //
        // Decisive: two spawns in one block with DIFFERENT callback types both
        // come out `Promise 'a`. Were instantiation and unification working,
        // one would pin to Str and the other to Int.
        //
        // So: the result type variable of an effect operation is not unified
        // with its callback's return type when the call is bound by a do-block
        // let. tryGenerateEffectOperationCall does not unify arguments itself —
        // it returns a type for something downstream to constrain.
        val src = """
            use std::effects::async::{ Async, LoomAsync }

            let f = {} -> perform LoomAsync in do
              let a = Async.spawn { f = {} -> "hello" }
              let b = Async.spawn { f = {} -> 42 }
              a
        """.trimIndent()

        assertEquals("Promise Str", bindingTypeAt(src, 4, 7))
        assertEquals("Promise Int", bindingTypeAt(src, 5, 7))
    }
}
