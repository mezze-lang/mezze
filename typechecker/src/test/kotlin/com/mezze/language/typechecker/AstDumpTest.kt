package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class AstDumpTest {

    @Test
    fun `dump v1 AST for 1 + 2`() {
        val parser = MezzeParser()
        val ast = parser.parse("1 + 2", true, null)
        val tc = TypeChecker()
        val result = tc.typeCheck(ast, Paths.get("."))

        println("=== V1 AST for '1 + 2' ===")
        for (item in result.program.items) {
            dumpItem(item, 0)
        }
    }

    @Test
    fun `dump v1 AST for multi-arg function`() {
        val parser = MezzeParser()
        val ast = parser.parse("let add = {x, y} -> x + y\nadd { x = 10, y = 32 }", true, null)
        val tc = TypeChecker()
        val result = tc.typeCheck(ast, Paths.get("."))
        println("=== V1 AST for multi-arg function ===")
        for (item in result.program.items) { dumpItem(item, 0) }
    }

    @Test
    fun `dump effect handler test`() {
        val parser = MezzeParser()
        val ast = parser.parse("""
            use std::effects::log::{Log, Console, println}
            perform Console in do
                println { msg = "hello" }
                42
        """.trimIndent(), true, null)
        val tc = TypeChecker()
        val result = tc.typeCheck(ast, Paths.get("."))
        println("=== Effect handler test ===")
        for (item in result.program.items) { dumpItem(item, 0) }
    }

    @Test
    fun `dump lambda body for x + y`() {
        val parser = MezzeParser()
        val ast = parser.parse("let add = {x, y} -> x + y\nadd { x = 10, y = 32 }", true, null)
        val tc = TypeChecker()
        val result = tc.typeCheck(ast, Paths.get("."))
        val addBinding = result.program.items.filterIsInstance<LetBinding>().find { it.isSimpleBinding && it.variableName == "add" }
        val lambda = addBinding?.expression as? LambdaExpression
        println("=== Lambda body for {x,y} -> x + y ===")
        if (lambda != null) {
            println("Lambda inferredType: ${lambda.inferredType}")
            dumpExpr(lambda.body, 0)
        }
    }

    @Test
    fun `dump v1 AST for function call`() {
        val parser = MezzeParser()
        val ast = parser.parse("let double = {x} -> x * 2\ndouble { x = 21 }", true, null)
        val tc = TypeChecker()
        val result = tc.typeCheck(ast, Paths.get("."))

        println("=== V1 AST for 'let double; double {x=21}' ===")
        for (item in result.program.items) {
            dumpItem(item, 0)
        }
    }

    private fun dumpItem(item: ProgramItem, indent: Int) {
        val prefix = "  ".repeat(indent)
        when (item) {
            is TopLevelExpression -> {
                println("${prefix}TopLevelExpression:")
                dumpExpr(item.expression, indent + 1)
            }
            is LetBinding -> {
                println("${prefix}LetBinding: ${if (item.isSimpleBinding) item.variableName else "<pattern>"}")
                println("${prefix}  contentHash: ${item.contentHash?.take(16)}")
                dumpExpr(item.expression, indent + 1)
            }
            else -> println("${prefix}${item::class.simpleName}")
        }
    }

    private fun dumpExpr(expr: Expression, indent: Int) {
        val prefix = "  ".repeat(indent)
        when (expr) {
            is FunctionCall -> {
                println("${prefix}FunctionCall:")
                println("${prefix}  resolvedFunctionContentHash: ${expr.resolvedFunctionContentHash?.take(16)}")
                println("${prefix}  resolvedFunctionName: ${expr.resolvedFunctionName}")
                println("${prefix}  resolvedAbilities: ${expr.resolvedAbilities.map { (k, v) -> "$k→${v.abilityName}(builtin=${v.isBuiltin}, hash=${v.implementationHash?.take(16)})" }}")
                println("${prefix}  isTailCall: ${expr.isTailCall}")
                println("${prefix}  function:")
                dumpExpr(expr.function, indent + 2)
                println("${prefix}  arguments:")
                dumpExpr(expr.arguments, indent + 2)
            }
            is MemberAccess -> {
                println("${prefix}MemberAccess: .${expr.member}")
                println("${prefix}  resolvedContentHash: ${expr.resolvedContentHash?.take(16)}")
                println("${prefix}  receiver:")
                dumpExpr(expr.receiver, indent + 2)
            }
            is Identifier -> {
                println("${prefix}Identifier: ${expr.localName}")
                println("${prefix}  inferredType: ${expr.inferredType}")
                println("${prefix}  resolvedContentHash: ${expr.resolvedContentHash?.take(16)}")
                println("${prefix}  isNativeFunction: ${expr.isNativeFunction}")
            }
            is RecordLiteral -> {
                println("${prefix}RecordLiteral: ${expr.entries.size} entries")
                for (entry in expr.entries) {
                    when (entry) {
                        is RecordFieldEntry -> {
                            println("${prefix}  ${entry.name} =")
                            entry.value?.let { dumpExpr(it, indent + 2) }
                        }
                        is RecordSpreadEntry -> println("${prefix}  ...spread")
                    }
                }
            }
            is IntLiteral -> println("${prefix}IntLiteral: ${expr.value}")
            else -> println("${prefix}${expr::class.simpleName}")
        }
    }
}
