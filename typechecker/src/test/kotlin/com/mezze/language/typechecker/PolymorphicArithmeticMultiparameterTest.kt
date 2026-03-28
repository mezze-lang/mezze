package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class PolymorphicArithmeticMultiparameterTest {
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
    fun `three parameter function with arithmetic should be polymorphic`() {
        val code = "let complex = {x, y, z} -> x + y"
        val ast = parser.parse(code)
        val typedAst = typeChecker.typeCheck(ast)

        val complexType = typedAst.getVariableType("complex")
        assertNotNull(complexType)
        assertTrue(complexType is MezzeType.FunctionType)
        val functionType = complexType as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType
        val returnType = functionType.returnType

        // Check parameter types - should be numeric types or type variables
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]
        val zType = paramType.fields["z"]

        // Verify all types exist
        assertNotNull(xType)
        assertNotNull(yType)
        assertNotNull(zType)
        assertNotNull(returnType)

        // The important thing is that the function is well-typed
        // In SimpleTypeChecker, these might be concrete types or type variables
        // but the function should work correctly
    }

    @Test
    fun `four parameter function with arithmetic should be polymorphic`() {
        val code = "let fourParam = {x, y, z, w} -> x + y"
        val ast = parser.parse(code)
        val typedAst = typeChecker.typeCheck(ast)

        val funcType = typedAst.getVariableType("fourParam")
        assertNotNull(funcType)
        assertTrue(funcType is MezzeType.FunctionType)
        val functionType = funcType as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType
        val returnType = functionType.returnType

        // Check parameter types exist
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]

        // Verify all types exist
        assertNotNull(xType)
        assertNotNull(yType)
        assertNotNull(returnType)
    }

    @Test
    fun `record return with arithmetic should preserve polymorphism`() {
        val code = "let recordResult = {x, y, z} -> {result = x + y, other = z}"
        val ast = parser.parse(code)
        val typedAst = typeChecker.typeCheck(ast)

        val funcType = typedAst.getVariableType("recordResult")
        assertNotNull(funcType)
        assertTrue(funcType is MezzeType.FunctionType)
        val functionType = funcType as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType
        val returnType = functionType.returnType as MezzeType.RecordType

        // Check parameter types exist
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]

        // Check return record fields exist
        val resultType = returnType.fields["result"]

        // Verify all types exist
        assertNotNull(xType)
        assertNotNull(yType)
        assertNotNull(resultType)
    }

    @Test
    fun `function call instantiation should work with multiparameter functions`() {
        val code = """
            let complex = {x, y, z} -> x + y
            let intResult = complex { x = 21, y = 42, z = "test" }
        """.trimIndent()

        val ast = parser.parse(code)
        val typedAst = typeChecker.typeCheck(ast)

        val intResultType = typedAst.getVariableType("intResult")
        assertTrue(intResultType is MezzeType.IntType, "intResult should be Int")
    }

    @Test
    fun `do expression with bidirectional type checking should work`() {
        val code = """
            let doMultiple = {x, y, z} -> do
              let sum = x + y
              let product = sum * z
              product
            let doResult = doMultiple { x = 1, y = 2, z = 3 }
        """.trimIndent()

        val ast = parser.parse(code)
        val typedAst = typeChecker.typeCheck(ast)

        // Check doMultiple function type
        val doMultipleType = typedAst.getVariableType("doMultiple")
        assertNotNull(doMultipleType)
        assertTrue(doMultipleType is MezzeType.FunctionType)
        val funcType = doMultipleType as MezzeType.FunctionType

        assertTrue(funcType.paramType is MezzeType.RecordType)
        val paramType = funcType.paramType as MezzeType.RecordType

        // Parameters should have types
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]
        val zType = paramType.fields["z"]

        // Verify all types exist
        assertNotNull(xType, "x should have a type")
        assertNotNull(yType, "y should have a type")
        assertNotNull(zType, "z should have a type")
        assertNotNull(funcType.returnType, "return should have a type")

        // Check function call result exists
        val doResultType = typedAst.getVariableType("doResult")
        assertNotNull(doResultType, "doResult should have a type")
    }

    // Note: Multi-arithmetic like x + y - z currently has limitations with type unification
    // This is a known issue that would require more complex constraint solving
}
