package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import org.junit.jupiter.api.Test

/**
 * Debug test to trace exactly what the v2 pipeline produces for each test case.
 */
class V2PipelineDebugTest {

    @Test
    fun `trace multi-arg function`() {
        val parser = MezzeParser()
        val parsed = parser.parseParsedModule("let add = {x, y} -> x + y\nadd { x = 10, y = 32 }", file = "test.mz")

        val tc = TypeChecker()
        val result = tc.checkParsedModuleWithDeps(parsed, enablePrelude = true)
        val typed = result.module

        println("=== TypedModule decls ===")
        for (decl in typed.decls) {
            when (decl) {
                is TypedLetDecl -> {
                    println("  TypedLetDecl: name=${decl.name}, hash=${decl.hash.hex.take(20)}, isPublic=${decl.isPublic}")
                    println("    value type: ${decl.value::class.simpleName}")
                    if (decl.value is TypedBlock) {
                        val block = decl.value as TypedBlock
                        println("    block stmts: ${block.statements.size}")
                        for (stmt in block.statements) {
                            when (stmt) {
                                is TypedStatement.Let -> println("      Let: ${stmt.name}, hash=${stmt.contentHash.hex.take(20)}, value=${stmt.value::class.simpleName}")
                                is TypedStatement.Expr -> println("      Expr: ${stmt.expr::class.simpleName}")
                            }
                        }
                        println("    block result: ${block.result::class.simpleName}")
                        if (block.result is TypedCall) {
                            val call = block.result as TypedCall
                            println("      call function: ${call.function::class.simpleName}")
                            if (call.function is TypedVar) {
                                val v = call.function as TypedVar
                                println("        var name: ${v.name}, binding: ${v.binding}")
                            }
                        }
                    }
                }
                else -> println("  ${decl::class.simpleName}")
            }
        }

        // Check the call inside __toplevel_expr_1
        val topExprDecl = typed.decls.filterIsInstance<TypedLetDecl>().find { it.name.startsWith("__toplevel") }
        if (topExprDecl?.value is TypedCall) {
            val call = topExprDecl.value as TypedCall
            println("\ncall function: ${call.function::class.simpleName}")
            if (call.function is TypedVar) {
                val v = call.function as TypedVar
                println("call var name: ${v.name}, binding: ${v.binding}")
                if (v.binding is BindingRef.ByHash) println("call hash: ${(v.binding as BindingRef.ByHash).hash.hex.take(30)}")
            }
        }

        val addDecl = typed.decls.filterIsInstance<TypedLetDecl>().find { it.name == "add" }
        println("add decl hash: ${addDecl?.hash?.hex?.take(30)}")
        println("hashes match: ${addDecl?.hash == (((topExprDecl?.value as? TypedCall)?.function as? TypedVar)?.binding as? BindingRef.ByHash)?.hash}")
    }
}
