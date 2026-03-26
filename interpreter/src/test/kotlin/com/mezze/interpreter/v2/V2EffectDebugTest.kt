package com.mezze.interpreter.v2

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckerV2
import com.mezze.language.typedast.*
import com.mezze.language.truffleir.*
import com.mezze.language.typechecker.mono.*
import com.mezze.interpreter.v2.compiler.EntryFunctionBuilder
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class V2EffectDebugTest {

    @Test
    fun `trace effect pipeline`() {
        val source = """
            use std::effects::log::{Log, Console, println}
            perform Console in do
                println { msg = "hello" }
                42
        """.trimIndent()

        val parser = MezzeParser()
        val parsed = parser.parseParsedModule(source, "test.mz")

        // Also dump v1 AST
        val v1Parser = MezzeParser()
        val v1Ast = v1Parser.parse(source, true, null)
        val v1Tc = TypeChecker()
        val v1Result = v1Tc.typeCheck(v1Ast, Paths.get("."))
        println("=== V1 AST ===")
        for (item in v1Result.program.items) {
            when (item) {
                is com.mezze.language.ast.TopLevelExpression -> {
                    val expr = item.expression
                    println("  TopLevelExpression: ${expr::class.simpleName}")
                    if (expr is com.mezze.language.ast.PerformExpression) {
                        println("    expression (body): ${expr.expression::class.simpleName}")
                        if (expr.expression is com.mezze.language.ast.DoExpression) {
                            val doExpr = expr.expression as com.mezze.language.ast.DoExpression
                            println("      do stmts=${doExpr.statements.size} result=${doExpr.result::class.simpleName}")
                        }
                    }
                }
                else -> println("  ${item::class.simpleName}")
            }
        }
        println()

        val tc = TypeChecker()
        val checkResult = tc.checkParsedModuleWithDeps(parsed, Paths.get("."), enablePrelude = true)
        val typed = checkResult.module

        println("=== TypedModule: ${typed.decls.size} decls ===")
        for (decl in typed.decls) {
            if (decl is TypedLetDecl) {
                println("  ${decl.name}: ${decl.value::class.simpleName}")
                if (decl.value is TypedBlock) {
                    val block = decl.value as TypedBlock
                    println("    block stmts=${block.statements.size} result=${block.result::class.simpleName}")
                }
                if (decl.value is TypedPerform) {
                    val perf = decl.value as TypedPerform
                    println("    perform handlers=${perf.handlers.size} body=${perf.body::class.simpleName}")
                    for (h in perf.handlers) println("      handler: ${h.performerName} hash=${h.performerHash}")
                }
                if (decl.value is TypedCall) {
                    val call = decl.value as TypedCall
                    println("    call fn=${call.function::class.simpleName}")
                    if (call.function is TypedVar) {
                        val v = call.function as TypedVar
                        println("      var ${v.name} binding=${v.binding}")
                    }
                }
            }
        }

        // Wrap
        val wrapped = EntryFunctionBuilder.wrapTopLevel(typed)
        println("\n=== Wrapped: ${wrapped.decls.size} decls ===")

        // Monomorphize
        val allModules = mapOf(wrapped.hash to wrapped)
        val monoModule = Monomorphizer().monomorphize(wrapped, allModules)
        println("\n=== Mono: ${monoModule.functions.size} functions ===")
        for (fn in monoModule.functions) println("  ${fn.name} body=${fn.body::class.simpleName}")

        // Resolve
        val resolved = Resolver().resolve(monoModule)
        println("\n=== Resolved: ${resolved.functions.size} functions ===")
        for (fn in resolved.functions) println("  ${fn.name} locals=${fn.locals.size}")

        // Lower
        val lowered = Lowerer().lower(resolved)
        println("\n=== Lowered: ${lowered.functions.size} functions ===")
        for (fn in lowered.functions) {
            println("  ${fn.name} body=${fn.body::class.simpleName}")
            dumpIR(fn.body, "    ")
        }
    }

    private fun dumpIR(node: NodeIR, indent: String) {
        when (node) {
            is BlockIR -> {
                println("${indent}BlockIR stmts=${node.stmts.size}")
                for (stmt in node.stmts) {
                    when (stmt) {
                        is StmtIR.Let -> {
                            println("${indent}  Let: ${stmt.name} hash=${stmt.contentHash}")
                            dumpIR(stmt.value, "$indent    ")
                        }
                        is StmtIR.Expr -> {
                            println("${indent}  Expr:")
                            dumpIR(stmt.node, "$indent    ")
                        }
                    }
                }
                println("${indent}  result:")
                dumpIR(node.result, "$indent    ")
            }
            is HandleIR -> {
                println("${indent}HandleIR handlers=${node.handlers.size}")
                for (h in node.handlers) println("${indent}  handler: ${h.performerName} effHash=${h.effectHash}")
                println("${indent}  body:")
                dumpIR(node.body, "$indent    ")
            }
            is CallDirectIR -> println("${indent}CallDirectIR hash=${node.targetHash} tailCall=${node.isTailCall}")
            is CallIndirectIR -> {
                println("${indent}CallIndirectIR")
                dumpIR(node.function, "$indent  fn: ")
            }
            is CallNativeIR -> println("${indent}CallNativeIR ${node.name}")
            is LongLitIR -> println("${indent}LongLitIR ${node.value}")
            is MakeRecordIR -> println("${indent}MakeRecordIR fields=${node.fields.map { it.name }}")
            is ReadLocalIR -> println("${indent}ReadLocalIR slot=${node.slot} kind=${node.kind}")
            is PerformOpIR -> println("${indent}PerformOpIR effect=${node.effectHash} op=${node.operationName}")
            is MakeLambdaIR -> println("${indent}MakeLambdaIR hash=${node.functionHash}")
            else -> println("${indent}${node::class.simpleName}")
        }
    }
}
