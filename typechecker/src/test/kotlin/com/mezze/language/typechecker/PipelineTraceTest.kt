package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import com.mezze.language.truffleir.*
import org.junit.jupiter.api.Test
import java.io.FileWriter
import java.nio.file.Paths

class PipelineTraceTest {

    @Test
    fun `trace simple program through pipeline`() {
        val source = """
            use std::effects::log::{Console, println}
            perform Console in println { msg = "hello" }
        """.trimIndent()

        val fw = FileWriter("/tmp/pipeline_trace.txt")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // Stage 0: Parse
        val parser = MezzeParser()
        val parsed = parser.parseParsedModule(source, file = "<trace>")
        fw.write("=== STAGE 0: PARSED ===\n")
        fw.write("Decls: ${parsed.decls.size}\n")
        for (decl in parsed.decls) {
            fw.write("  ${decl::class.simpleName}\n")
        }

        // Stage 1: Typecheck
        val tc = TypeChecker()
        val result = tc.checkParsedModule(parsed, projectRoot, enablePrelude = true)
        if (result is TypeChecker.TypeCheckResult.Failure) {
            fw.write("TYPECHECK FAILED: ${result.errors.map { it.message }}\n")
            fw.close(); return
        }
        val success = result as TypeChecker.TypeCheckResult.Success
        val typedModule = success.typedModule!!

        fw.write("\n=== STAGE 1: TYPED MODULE ===\n")
        for (decl in typedModule.decls) {
            if (decl is TypedLetDecl) {
                fw.write("  LET ${decl.name} hash=${decl.hash}\n")
                fw.write("    value type: ${decl.value::class.simpleName}\n")
                dumpTypedExpr(fw, decl.value, "    ")
            }
        }

        // Stage 1.5: Wrap
        val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typedModule)
        fw.write("\n=== STAGE 1.5: WRAPPED ===\n")
        for (decl in wrapped.decls) {
            if (decl is TypedLetDecl) {
                fw.write("  LET ${decl.name} hash=${decl.hash}\n")
                if (decl.name == "__entry__") {
                    fw.write("    value type: ${decl.value::class.simpleName}\n")
                    dumpTypedExpr(fw, decl.value, "    ")
                }
            }
        }

        // Stage 2: Mono
        val preludeV2 = PrecompiledStdlib.stdlibTypedModules
        val allModules = HashMap<Hash, com.mezze.language.typedast.TypedModule>()
        allModules.putAll(preludeV2)
        allModules[wrapped.hash] = wrapped
        val preloweredHashes = PrecompiledStdlib.preloweredFunctions.keys

        // Compile with TypedASTCompiler
        val allAbilityImpls = allModules.values.flatMap { it.abilityImpls }
        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
            abilityImpls = allAbilityImpls,
            stdlibHashes = preloweredHashes
        )
        val compiled = compiler.compile(wrapped)

        fw.write("\n=== COMPILED (TypedASTCompiler) ===\n")
        fw.write("Functions: ${compiled.functions.size}\n")
        for (fn in compiled.functions) {
            fw.write("  FN ${fn.name} hash=${fn.hash.hex.take(16)}... params=${fn.paramNames} locals=${fn.localNames} captures=${fn.captureNames}\n")
            dumpNodeIR(fw, fn.body, "    ")
        }
        fw.write("PerformerRecords: ${compiled.performerRecords.size}\n")

        fw.close()
        println("Trace written to /tmp/pipeline_trace.txt")
    }

    private fun dumpTypedExpr(fw: FileWriter, expr: TypedExpr, indent: String) {
        when (expr) {
            is TypedVar -> fw.write("${indent}Var(${expr.name}, ${expr.binding})\n")
            is TypedLambda -> {
                fw.write("${indent}Lambda(${expr.params.map{it.name}}, captures=${expr.captures.map{it.name}})\n")
                dumpTypedExpr(fw, expr.body, "$indent  ")
            }
            is TypedCall -> {
                fw.write("${indent}Call\n")
                dumpTypedExpr(fw, expr.function, "$indent  fn: ")
                dumpTypedExpr(fw, expr.arg, "$indent  arg: ")
            }
            is TypedIf -> {
                fw.write("${indent}If\n")
                dumpTypedExpr(fw, expr.condition, "$indent  cond: ")
                dumpTypedExpr(fw, expr.thenBranch, "$indent  then: ")
                dumpTypedExpr(fw, expr.elseBranch, "$indent  else: ")
            }
            is TypedRecord -> fw.write("${indent}Record(${expr.entries.filterIsInstance<TypedRecordEntry.Field>().map{it.name}})\n")
            is TypedBlock -> {
                fw.write("${indent}Block(${expr.statements.size} stmts)\n")
                for (s in expr.statements) when (s) {
                    is TypedStatement.Let -> {
                        fw.write("$indent  let ${s.name} hash=${s.contentHash}\n")
                        dumpTypedExpr(fw, s.value, "$indent    ")
                    }
                    is TypedStatement.Expr -> dumpTypedExpr(fw, s.expr, "$indent  ")
                }
                dumpTypedExpr(fw, expr.result, "$indent  result: ")
            }
            is TypedIntLit -> fw.write("${indent}Int(${expr.value})\n")
            is TypedBoolLit -> fw.write("${indent}Bool(${expr.value})\n")
            else -> fw.write("${indent}${expr::class.simpleName}\n")
        }
    }

    private fun dumpNodeIR(fw: FileWriter, node: NodeIR, indent: String) {
        when (node) {
            is CallDirectIR -> {
                fw.write("${indent}CallDirect(${node.targetHash.hex.take(16)}... tail=${node.isTailCall})\n")
                dumpNodeIR(fw, node.arg, "$indent  arg: ")
            }
            is ReadLocalIR -> fw.write("${indent}ReadLocal(slot=${node.slot})\n")
            is ReadArgIR -> fw.write("${indent}ReadArg(idx=${node.index})\n")
            is WriteLocalIR -> {
                fw.write("${indent}WriteLocal(slot=${node.slot})\n")
                dumpNodeIR(fw, node.value, "$indent  val: ")
            }
            is MakeLambdaIR -> fw.write("${indent}MakeLambda(fnHash=${node.functionHash.hex.take(16)}... captures=${node.captures.size})\n")
            is MakeRecordIR -> fw.write("${indent}MakeRecord(${node.fields.map { it.name }})\n")
            is IfIR -> {
                fw.write("${indent}If\n")
                dumpNodeIR(fw, node.cond, "$indent  cond: ")
                dumpNodeIR(fw, node.thenBr, "$indent  then: ")
                dumpNodeIR(fw, node.elseBr, "$indent  else: ")
            }
            is BlockIR -> {
                fw.write("${indent}Block(${node.stmts.size} stmts)\n")
                for (s in node.stmts) when (s) {
                    is StmtIR.Let -> {
                        fw.write("$indent  let slot=${s.slot} hash=${s.contentHash.hex.take(16)}...\n")
                        dumpNodeIR(fw, s.value, "$indent    ")
                    }
                    is StmtIR.Expr -> dumpNodeIR(fw, s.node, "$indent  ")
                }
                dumpNodeIR(fw, node.result, "$indent  result: ")
            }
            is CallNativeIR -> fw.write("${indent}CallNative(${node.name})\n")
            is CallLocalIR -> fw.write("${indent}CallLocal(slot=${node.slot})\n")
            is CallIndirectIR -> {
                fw.write("${indent}CallIndirect\n")
                dumpNodeIR(fw, node.function, "$indent  fn: ")
                dumpNodeIR(fw, node.arg, "$indent  arg: ")
            }
            is LongLitIR -> fw.write("${indent}Long(${node.value})\n")
            is BoolLitIR -> fw.write("${indent}Bool(${node.value})\n")
            is UnitLitIR -> fw.write("${indent}Unit\n")
            else -> fw.write("${indent}${node::class.simpleName}\n")
        }
    }
}
