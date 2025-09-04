package com.funk.language.typechecker

import com.funk.language.parser.FunkParserImpl
import com.funk.language.parser.LetBinding
import com.funk.language.parser.Literal
import com.funk.language.parser.BinaryOperation
import com.funk.language.parser.IfExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeCheckerTest {

    private val parser = FunkParserImpl()
    private val typeChecker = TypeChecker()

    @Test
    fun `infers correct type for a simple integer let binding`() {
        val code = "let x = 10"
        val ast = parser.parse(code)
        val typedAst = typeChecker.check(ast)

        val letBindingNode = ast.statements[0] as LetBinding
        val literalNode = letBindingNode.expression as Literal<*>

        assertEquals(Type.IntType, typedAst[letBindingNode])
        assertEquals(Type.IntType, typedAst[literalNode])
    }

    @Test
    fun `infers correct type for a binary operation`() {
        val code = "let result = 10 + 5"
        val ast = parser.parse(code)
        val typedAst = typeChecker.check(ast)

        val letBindingNode = ast.statements[0] as LetBinding
        val binaryOpNode = letBindingNode.expression as BinaryOperation

        assertEquals(Type.IntType, typedAst[binaryOpNode])
    }

    @Test
    fun `infers correct type for an if expression`() {
        val code = "let result = if true then 1 else 0"
        val ast = parser.parse(code)
        val typedAst = typeChecker.check(ast)

        val letBindingNode = ast.statements[0] as LetBinding
        val ifExprNode = letBindingNode.expression as IfExpression

        assertEquals(Type.IntType, typedAst[ifExprNode])
    }
}
