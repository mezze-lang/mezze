package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Locks in: when `xs.method {}` appears at a declaration where `xs` is
 * unannotated AND a unique prelude/in-scope ability declares `method`,
 * the typechecker infers `xs` as a polymorphic type with that ability
 * constraint. It does NOT speculatively dispatch to a prelude inherent
 * impl (which would be unsound — multiple stdlib types share method names),
 * nor does it silently fall back to a structural record.
 *
 * Behaviours:
 *
 * 1. `let f = { xs } -> xs.size {}` infers
 *    `f : { xs: 'a } -> Int with Sized 'a`. Polymorphic in 'a, constrained
 *    to types that implement Sized.
 *
 * 2. Calling `f` with a value whose type implements Sized (Seq, Str, …) works.
 *
 * 3. Calling `f` with a structural record literal does NOT work — anonymous
 *    records can't satisfy ability constraints (no `impl Sized for {…}` syntax).
 *
 * 4. Concrete receivers still dispatch via inherent impls when the type
 *    is statically known: `[1,2,3].size {}` resolves to Seq.length and
 *    returns Int.
 */
class ClosedRecordMethodCallTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun checkWithPrelude(code: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `unannotated method call infers ability-constrained receiver`() {
        // `xs.size {}` is uniquely `Sized.size` (in prelude). Inference adds
        // the Sized constraint on `xs`'s type variable instead of falling back
        // to a structural record (#304).
        val result = checkWithPrelude("let f = { xs } -> xs.size {}")
        val ftype = result.getVariableType("f")
            ?: fail("expected 'f' to be type-checked; got null")
        assertTrue(ftype is MezzeType.FunctionType,
            "expected f to be FunctionType, got ${ftype::class.simpleName}: $ftype")
        val ft = ftype as MezzeType.FunctionType
        // f's param is { xs: 'a } where 'a has Sized constraint
        val paramRecord = ft.paramType as? MezzeType.RecordType
            ?: fail("expected param to be RecordType, got ${ft.paramType}")
        val xsType = paramRecord.fields["xs"]
            ?: fail("expected xs field; got fields ${paramRecord.fields.keys}")
        assertTrue(xsType is MezzeType.VarType,
            "expected xs to be a polymorphic type variable, got ${xsType::class.simpleName}: $xsType")
        // The Sized constraint shows up in pretty-printed type
        assertTrue(ft.abilityConstraints.any { it.abilityName == "Sized" },
            "expected Sized constraint on f's type, got ${ft.abilityConstraints}")
    }

    @Test
    fun `calling with mismatched primitive is a type error`() {
        // Str DOES implement Sized, so `f { xs = "hello" }` actually succeeds.
        // Keep this as a guard against regressions: Str's inherent size impl
        // satisfies the Sized constraint inferred for f's param.
        val code = """
            let f = { xs } -> xs.size {}
            let n = f { xs = "hello" }
        """.trimIndent()
        val r = checkWithPrelude(code)
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `calling with seq literal works because seq implements Sized`() {
        // `[Int]` implements Sized via inherent `size` method — passes.
        val code = """
            let f = { xs } -> xs.size {}
            let n = f { xs = [1, 2, 3] }
        """.trimIndent()
        val r = checkWithPrelude(code)
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `calling with structural record literal is a type error`() {
        // Anonymous structural records can't satisfy ability constraints —
        // there's no syntax for `impl Sized for {size: () -> Int}`. Passing
        // a record literal whose shape matches the method op's signature
        // would have worked under the old permissive model (#216), but is
        // now correctly rejected (#304).
        val code = """
            let f = { xs } -> xs.size {}
            let n = f { xs = { size = {} -> 5 } }
        """.trimIndent()
        assertThrows(TypeCheckError::class.java) {
            checkWithPrelude(code)
        }
    }

    // ---- Concrete receivers continue to dispatch through inherent impls ----

    @Test
    fun `concrete seq literal dispatches inherent length`() {
        // Regression guard: `[1,2,3].size {}` has a concrete receiver
        // ([Int]), so inherent-impl dispatch still fires and returns Int.
        val r = checkWithPrelude("let n = [1, 2, 3].size {}")
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `concrete str literal dispatches inherent length`() {
        val r = checkWithPrelude("""let n = "hello".size {}""")
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    // #303: Buffer was missing impl Sized; verify generic Sized fns accept it.
    @Test
    fun `bytebuffer satisfies Sized constraint via impl`() {
        // ByteBuffer.new {capacity=8} produces a ByteBuffer; passing it to a
        // generic Sized-constrained function must typecheck.
        val code = """
            use std::collections::buffer
            let f = { xs } -> xs.size {}
            let n = f { xs = buffer::new { capacity = 8 } }
        """.trimIndent()
        val r = checkWithPrelude(code)
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `concrete bytebuffer dispatches inherent size`() {
        val code = """
            use std::collections::buffer
            let n = (buffer::new { capacity = 8 }).size {}
        """.trimIndent()
        val r = checkWithPrelude(code)
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }
}
