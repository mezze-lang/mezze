package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Locks in: when `xs.method {}` appears at a declaration where `xs` is
 * unannotated, the typechecker infers `xs` as a closed record carrying
 * the called method as a function-valued field. It does NOT speculatively
 * dispatch to a prelude inherent impl (which would be unsound — multiple
 * stdlib types share method names).
 *
 * Tracked by issue #216. Three behaviours:
 *
 * 1. `let f = { xs } -> xs.size {}` infers
 *    `f : { xs: { size: {} -> 'a } } -> 'a`.
 *    No commitment to Str, Seq, Bytes, etc.
 *
 * 2. Calling `f` with a record whose shape matches works.
 *
 * 3. Calling `f` with a primitive (Str) or seq literal ([Int]) is a type
 *    error — they don't have the expected closed-record shape.
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
    fun `unannotated method call infers closed-record receiver`() {
        // No annotation, no concrete receiver — `xs.size {}` shouldn't pick
        // any specific inherent impl. The receiver `xs` should be inferred as
        // a closed record `{ length: {} -> 'a }`.
        val result = checkWithPrelude("let f = { xs } -> xs.size {}")
        val ftype = result.getVariableType("f")
            ?: fail("expected 'f' to be type-checked; got null")
        assertTrue(ftype is MezzeType.FunctionType,
            "expected f to be FunctionType, got ${ftype::class.simpleName}: $ftype")
        val ft = ftype as MezzeType.FunctionType
        // f's param is { xs: <receiverType> }
        val paramRecord = ft.paramType as? MezzeType.RecordType
            ?: fail("expected param to be RecordType, got ${ft.paramType}")
        val xsType = paramRecord.fields["xs"]
            ?: fail("expected xs field; got fields ${paramRecord.fields.keys}")
        // xs should be a record with `size` field — NOT Str, NOT Seq.
        assertTrue(xsType is MezzeType.RecordType,
            "expected xs to be a structural RecordType (closed-record method-call inference), " +
            "got ${xsType::class.simpleName}: $xsType. " +
            "If this is Str/Seq, the typechecker is speculatively dispatching.")
        val xsRecord = xsType as MezzeType.RecordType
        assertTrue(xsRecord.fields.containsKey("size"),
            "expected xs's record to have field 'size', got ${xsRecord.fields.keys}")
    }

    @Test
    fun `calling with mismatched primitive is a type error`() {
        // After #216, `f { xs = "hello" }` should fail because Str doesn't
        // match the inferred closed-record shape.
        val code = """
            let f = { xs } -> xs.size {}
            let n = f { xs = "hello" }
        """.trimIndent()
        assertThrows(TypeCheckError::class.java) {
            checkWithPrelude(code)
        }
    }

    @Test
    fun `calling with mismatched seq literal is a type error`() {
        // After #216, `f { xs = [1, 2, 3] }` should fail because [Int] doesn't
        // match the inferred closed-record shape.
        val code = """
            let f = { xs } -> xs.size {}
            let n = f { xs = [1, 2, 3] }
        """.trimIndent()
        assertThrows(TypeCheckError::class.java) {
            checkWithPrelude(code)
        }
    }

    @Test
    fun `calling with matching record shape works`() {
        // A literal record carrying the right field shape DOES typecheck.
        val code = """
            let f = { xs } -> xs.size {}
            let n = f { xs = { size = {} -> 5 } }
        """.trimIndent()
        val r = checkWithPrelude(code)
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
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
}
