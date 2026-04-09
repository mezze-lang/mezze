package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import com.mezze.language.monoast.*
import com.mezze.language.resolvedast.*
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
        val preludeV2 = PrecompiledStdlib.v2Modules
        val allModules = HashMap<Hash, com.mezze.language.typedast.TypedModule>()
        allModules.putAll(preludeV2)
        allModules[wrapped.hash] = wrapped
        val preloweredHashes = PrecompiledStdlib.preloweredFunctions.keys

        val mono = com.mezze.language.typechecker.mono.Monomorphizer()
            .monomorphize(wrapped, allModules, preloweredHashes)

        fw.write("\n=== STAGE 2: MONO ===\n")
        for (fn in mono.functions) {
            fw.write("  FN ${fn.name} hash=${fn.hash.hex.take(16)}... params=${fn.params.map{it.name}}\n")
            dumpMonoExpr(fw, fn.body, "    ")
        }

        // Stage 3: Resolve
        val resolved = com.mezze.language.typechecker.mono.Resolver().resolve(mono)
        fw.write("\n=== STAGE 3: RESOLVED ===\n")
        for (fn in resolved.functions) {
            fw.write("  FN ${fn.name} hash=${fn.hash.hex.take(16)}... params=${fn.params.map{it.name}} captures=${fn.captures.map{it.name}}\n")
            dumpResolvedExpr(fw, fn.body, "    ")
        }

        // Stage 4: Lower
        val lowered = com.mezze.language.typechecker.mono.Lowerer().lower(resolved)
        fw.write("\n=== STAGE 4: LOWERED ===\n")
        for (fn in lowered.functions) {
            fw.write("  FN ${fn.name} hash=${fn.hash.hex.take(16)}... params=${fn.paramNames} captures=${fn.captureNames}\n")
            dumpNodeIR(fw, fn.body, "    ")
        }

        // Also dump the pre-lowered result::map function
        fw.write("\n=== PRE-LOWERED result::map ===\n")
        for ((hash, fn) in PrecompiledStdlib.preloweredFunctions) {
            if (fn.name == "map" && fn.qualifiedName.contains("map") && fn.paramNames.contains("result")) {
                fw.write("  FN ${fn.name} (${fn.qualifiedName}) hash=${hash.take(16)}...\n")
                fw.write("    paramNames=${fn.paramNames} paramSlots=${fn.paramSlots}\n")
                fw.write("    localNames=${fn.localNames} localSlots=${fn.localSlots}\n")
                fw.write("    captureNames=${fn.captureNames}\n")
                dumpNodeIR(fw, fn.body, "    ")
            }
        }

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

    private fun dumpMonoExpr(fw: FileWriter, expr: MonoExpr, indent: String) {
        when (expr) {
            is MonoVar -> fw.write("${indent}Var(${expr.name}, ${expr.binding})\n")
            is MonoLambda -> {
                fw.write("${indent}Lambda(${expr.params.map{it.name}})\n")
                dumpMonoExpr(fw, expr.body, "$indent  ")
            }
            is MonoCall -> {
                fw.write("${indent}Call\n")
                dumpMonoExpr(fw, expr.function, "$indent  fn: ")
                dumpMonoExpr(fw, expr.arg, "$indent  arg: ")
            }
            is MonoIf -> {
                fw.write("${indent}If\n")
                dumpMonoExpr(fw, expr.condition, "$indent  cond: ")
                dumpMonoExpr(fw, expr.thenBranch, "$indent  then: ")
                dumpMonoExpr(fw, expr.elseBranch, "$indent  else: ")
            }
            is MonoRecord -> fw.write("${indent}Record(${expr.entries.filterIsInstance<MonoRecordEntry.Field>().map{it.name}})\n")
            is MonoBlock -> {
                fw.write("${indent}Block(${expr.statements.size} stmts)\n")
                for (s in expr.statements) when (s) {
                    is MonoStatement.Let -> {
                        fw.write("$indent  let ${s.name} hash=${s.contentHash}\n")
                        dumpMonoExpr(fw, s.value, "$indent    ")
                    }
                    is MonoStatement.Expr -> dumpMonoExpr(fw, s.expr, "$indent  ")
                }
                dumpMonoExpr(fw, expr.result, "$indent  result: ")
            }
            is MonoIntLit -> fw.write("${indent}Int(${expr.value})\n")
            is MonoBoolLit -> fw.write("${indent}Bool(${expr.value})\n")
            else -> fw.write("${indent}${expr::class.simpleName}\n")
        }
    }

    private fun dumpResolvedExpr(fw: FileWriter, expr: ResolvedExpr, indent: String) {
        when (expr) {
            is ResolvedFunctionRef -> fw.write("${indent}FunctionRef(${expr.targetHash.hex.take(16)}...)\n")
            is ResolvedCallDirect -> {
                fw.write("${indent}CallDirect(${expr.targetHash.hex.take(16)}... tail=${expr.isTailCall})\n")
                dumpResolvedExpr(fw, expr.arg, "$indent  arg: ")
            }
            is ResolvedReadLocal -> fw.write("${indent}ReadLocal(slot=${expr.slot})\n")
            is ResolvedReadParam -> fw.write("${indent}ReadParam(idx=${expr.index})\n")
            is ResolvedReadCapture -> fw.write("${indent}ReadCapture(idx=${expr.index})\n")
            is ResolvedReadContext -> fw.write("${indent}ReadContext(${expr.name})\n")
            is ResolvedWriteLocal -> {
                fw.write("${indent}WriteLocal(slot=${expr.slot})\n")
                dumpResolvedExpr(fw, expr.value, "$indent  val: ")
            }
            is ResolvedMakeLambda -> fw.write("${indent}MakeLambda(fnHash=${expr.functionHash.hex.take(16)}... captures=${expr.captures.size})\n")
            is ResolvedIf -> {
                fw.write("${indent}If\n")
                dumpResolvedExpr(fw, expr.cond, "$indent  cond: ")
                dumpResolvedExpr(fw, expr.thenBr, "$indent  then: ")
                dumpResolvedExpr(fw, expr.elseBr, "$indent  else: ")
            }
            is ResolvedBlock -> {
                fw.write("${indent}Block(${expr.stmts.size} stmts)\n")
                for (s in expr.stmts) when (s) {
                    is ResolvedStmt.Let -> {
                        fw.write("$indent  let slot=${s.slot} hash=${s.contentHash.hex.take(16)}...\n")
                        dumpResolvedExpr(fw, s.value, "$indent    ")
                    }
                    is ResolvedStmt.Expr -> dumpResolvedExpr(fw, s.expr, "$indent  ")
                }
                dumpResolvedExpr(fw, expr.result, "$indent  result: ")
            }
            is ResolvedCallNative -> fw.write("${indent}CallNative(${expr.name})\n")
            is ResolvedCallLocal -> fw.write("${indent}CallLocal(slot=${expr.slot})\n")
            is ResolvedCallIndirect -> fw.write("${indent}CallIndirect\n")
            is ResolvedIntLit -> fw.write("${indent}Int(${expr.value})\n")
            is ResolvedBoolLit -> fw.write("${indent}Bool(${expr.value})\n")
            is ResolvedUnitLit -> fw.write("${indent}Unit\n")
            else -> fw.write("${indent}${expr::class.simpleName}\n")
        }
    }

    private fun dumpNodeIR(fw: FileWriter, node: NodeIR, indent: String) {
        when (node) {
            is FunctionRefIR -> fw.write("${indent}FunctionRef(${node.targetHash.hex.take(16)}...)\n")
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
