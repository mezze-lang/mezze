package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import com.mezze.language.monoast.*
import org.junit.jupiter.api.Test
import java.io.FileWriter
import java.nio.file.Paths

/**
 * Trace a simple program through every pipeline stage.
 */
class PipelineTraceTest {

    @Test
    fun `trace simple program through pipeline`() {
        val source = """
            use std::effects::log::{Console, println}

            let sum = {x, y} -> x + y

            let main = {} -> perform Console in do
              let res = sum { x = 5, y = 6 }
              println { msg = "result" }
        """.trimIndent()

        val fw = FileWriter("/tmp/pipeline_trace.txt")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // Stage 0: Parse
        val parser = MezzeParser()
        val parsed = parser.parseParsedModule(source, file = "<trace>")
        fw.write("=== STAGE 0: PARSED MODULE ===\n")
        fw.write("Path: ${parsed.path}\n")
        fw.write("Decls: ${parsed.decls.size}\n")
        for (decl in parsed.decls) {
            fw.write("  ${decl::class.simpleName}: ${decl}\n".take(200) + "\n")
        }

        // Stage 1: Typecheck
        val tc = TypeChecker()
        val result = tc.checkParsedModule(parsed, projectRoot, enablePrelude = true)
        fw.write("\n=== STAGE 1: TYPECHECK RESULT ===\n")
        fw.write("Result type: ${result::class.simpleName}\n")
        if (result is TypeChecker.TypeCheckResult.Failure) {
            fw.write("ERRORS:\n")
            for (e in result.errors) fw.write("  ${e.message}\n")
            fw.close()
            return
        }
        val success = result as TypeChecker.TypeCheckResult.Success
        val typedModule = success.typedModule
        fw.write("TypedModule: ${typedModule != null}\n")
        if (typedModule != null) {
            fw.write("  Hash: ${typedModule.hash}\n")
            fw.write("  Path: ${typedModule.path}\n")
            fw.write("  Decls: ${typedModule.decls.size}\n")
            for (decl in typedModule.decls) {
                when (decl) {
                    is TypedLetDecl -> fw.write("  LET ${decl.name} hash=${decl.hash} params=${(decl.value as? TypedLambda)?.params?.map { it.name }}\n")
                    else -> fw.write("  ${decl::class.simpleName}\n")
                }
            }
            fw.write("  PerformerDecls: ${typedModule.performerDecls.size}\n")
            for (pd in typedModule.performerDecls) {
                fw.write("    ${pd.name} hash=${pd.hash} effectHash=${pd.effectHash} ops=${pd.operations.keys} opHashes=${pd.operationHashes}\n")
            }
            fw.write("  AbilityImpls: ${typedModule.abilityImpls.size}\n")
            fw.write("  EffectDecls: ${typedModule.effectDecls.size}\n")
        }

        // Stage 1.5: Wrap top-level
        val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typedModule!!)
        fw.write("\n=== STAGE 1.5: WRAPPED ===\n")
        fw.write("  Decls: ${wrapped.decls.size}\n")
        for (decl in wrapped.decls) {
            if (decl is TypedLetDecl) fw.write("  LET ${decl.name} hash=${decl.hash}\n")
        }

        // Stage 2: Monomorphize
        val preludeV2 = PrecompiledStdlib.v2Modules
        val allModules = HashMap<Hash, com.mezze.language.typedast.TypedModule>()
        allModules.putAll(preludeV2)
        allModules[wrapped.hash] = wrapped
        // Add loaded modules
        val loadedModules = success.loadedModules
        for ((_, v1Module) in loadedModules) {
            val v2Module = v1Module.v2TypedModule
            if (v2Module != null) allModules[v2Module.hash] = v2Module
        }
        // Add transitive deps
        for ((_, v1Module) in loadedModules) {
            val parsedMod = v1Module.parsedModule ?: continue
            for (decl in parsedMod.decls) {
                val depPath = when (decl) {
                    is com.mezze.language.parsedast.ParsedUseDecl -> when (val p = decl.path) {
                        is com.mezze.language.parsedast.ParsedUsePath.Module -> p.path.toString()
                        is com.mezze.language.parsedast.ParsedUsePath.Selective -> p.modulePath.toString()
                    }
                    is com.mezze.language.parsedast.ParsedPubUseDecl -> when (val p = decl.path) {
                        is com.mezze.language.parsedast.ParsedUsePath.Module -> p.path.toString()
                        is com.mezze.language.parsedast.ParsedUsePath.Selective -> p.modulePath.toString()
                    }
                    else -> null
                }
                if (depPath != null) {
                    val cached = com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()[depPath]
                    val depV2 = cached?.v2TypedModule
                    if (depV2 != null && depV2.hash !in allModules) allModules[depV2.hash] = depV2
                }
            }
        }

        fw.write("\n=== STAGE 2: MONOMORPHIZE ===\n")
        fw.write("allModules: ${allModules.size}\n")
        for ((h, m) in allModules) {
            val declCount = m.decls.size
            val perfCount = m.performerDecls.size
            val implCount = m.abilityImpls.size
            fw.write("  ${h.hex.take(16)}... ${m.path.takeLast(40)} decls=$declCount performers=$perfCount impls=$implCount\n")
        }

        val preloweredHashes = PrecompiledStdlib.preloweredFunctions.keys
        fw.write("preloweredHashes: ${preloweredHashes.size}\n")

        val mono = com.mezze.language.typechecker.mono.Monomorphizer()
            .monomorphize(wrapped, allModules, preloweredHashes)

        fw.write("MonoModule functions: ${mono.functions.size}\n")
        for (fn in mono.functions) {
            fw.write("  ${fn.name} hash=${fn.hash.hex.take(16)}... params=${fn.params.map{it.name}} bodyType=${fn.body::class.simpleName}\n")
        }
        fw.write("MonoModule performers: ${mono.performerDecls.size}\n")
        for (pd in mono.performerDecls) {
            fw.write("  ${pd.name} hash=${pd.hash.hex.take(16)}... ops=${pd.operations.keys}\n")
        }
        fw.write("MonoModule effects: ${mono.effectDecls.size}\n")

        // Stage 3: Resolve
        val resolved = com.mezze.language.typechecker.mono.Resolver().resolve(mono)
        fw.write("\n=== STAGE 3: RESOLVE ===\n")
        fw.write("Functions: ${resolved.functions.size}\n")
        for (fn in resolved.functions) {
            fw.write("  ${fn.name} (${fn.qualifiedName}) hash=${fn.hash.hex.take(16)}... params=${fn.params.size} locals=${fn.locals.size} captures=${fn.captures.size}\n")
        }
        fw.write("EffectHandlers: ${resolved.effectHandlers.size}\n")
        for (h in resolved.effectHandlers) {
            fw.write("  ${h.hash.hex.take(16)}... performer=${h.performerHash.hex.take(16)}... effect=${h.effectHash.hex.take(16)}... ops=${h.operations.size}\n")
            for (op in h.operations) {
                fw.write("    ${op.name} hash=${op.hash.hex.take(16)}... params=${op.params.size}\n")
            }
        }

        // Stage 4: Lower
        val lowered = com.mezze.language.typechecker.mono.Lowerer().lower(resolved)
        fw.write("\n=== STAGE 4: LOWER ===\n")
        fw.write("Functions: ${lowered.functions.size}\n")
        for (fn in lowered.functions) {
            fw.write("  ${fn.name} (${fn.qualifiedName}) hash=${fn.hash.hex.take(16)}... params=${fn.paramNames} locals=${fn.localNames} captures=${fn.captureNames}\n")
        }
        fw.write("PerformerRecords: ${lowered.performerRecords.size}\n")
        for (pr in lowered.performerRecords) {
            fw.write("  performer=${pr.performerHash.take(16)}... effect=${pr.effectHash.take(16)}... opHashes=${pr.operationHashes.map{it.take(16)}} opNames=${pr.operationNames}\n")
        }

        fw.write("\n=== SUMMARY ===\n")
        fw.write("Total functions to compile: ${lowered.functions.size}\n")
        fw.write("Total performer records: ${lowered.performerRecords.size}\n")
        fw.close()

        println("Trace written to /tmp/pipeline_trace.txt")
        println("Functions: ${lowered.functions.size}, Performers: ${lowered.performerRecords.size}")
    }
}
