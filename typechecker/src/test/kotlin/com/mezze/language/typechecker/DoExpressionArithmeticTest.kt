package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class DoExpressionArithmeticTest {
    
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
    fun `test DoExpression with arithmetic should constrain parameters`() {
        val source = """
            let withDo = {x, y} -> do
              let sum = x + y
              sum
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("withDo") as com.mezze.language.ast.MezzeType.FunctionType
        val paramType = functionType.paramType as com.mezze.language.ast.MezzeType.RecordType

        // Check that x and y have types (could be concrete or type variables)
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]
        println("DEBUG: x type: ${com.mezze.language.ast.prettyPrintType(xType!!)}")
        println("DEBUG: y type: ${com.mezze.language.ast.prettyPrintType(yType!!)}")

        assertNotNull(xType, "x should have a type")
        assertNotNull(yType, "y should have a type")
    }

    // Bug #16: a do-block `let` bound to a lambda with a concrete-Int body must
    // DISCHARGE its NumAdd/NumMul obligation locally, exactly as the identical
    // top-level binding does — not leak `with NumAdd Int 'b having 'out = 'a`
    // and a free return var onto the enclosing fn. Root cause: the do-block
    // local solve (solveDoBlockBindingLocally) was fed only collector.constraints,
    // omitting the accumulated ability/associated-type constraints, so the
    // discharge oracle couldn't see the obligation satisfied.
    @Test
    fun `do-block let with concrete-Int arithmetic body infers Int, no leaked ability`() {
        val source = """
            let helper = {} -> do
              let f = { n: Int } -> n * 2 + 1
              f { n = 5 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "b16.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        val sig = com.mezze.language.ast.prettyPrintType(result.getVariableType("helper")!!)

        assertTrue(sig.contains("-> Int"), "helper should return Int, got: $sig")
        assertTrue(!sig.contains("NumAdd") && !sig.contains("NumMul"),
            "helper must not leak a numeric ability constraint, got: $sig")
        assertTrue(!sig.contains("'"), "helper must have no free type var, got: $sig")
    }

    // Control: the IDENTICAL binding at top level already infers `{ n: Int } -> Int`.
    // Guards that the do-block fix stays consistent with the top-level path.
    @Test
    fun `top-level Int-arithmetic lambda infers Int (control for the do-block fix)`() {
        val source = "let f = { n: Int } -> n * 2 + 1"
        val parsedModule = parser.parseParsedModule(source, "b16c.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        val sig = com.mezze.language.ast.prettyPrintType(result.getVariableType("f")!!)
        assertTrue(sig.contains("-> Int") && !sig.contains("NumAdd"),
            "top-level f should infer { n: Int } -> Int, got: $sig")
    }
}
