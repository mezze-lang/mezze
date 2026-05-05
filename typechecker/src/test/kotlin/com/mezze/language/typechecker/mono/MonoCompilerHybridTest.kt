package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.typechecker.compiler.TypedASTCompiler
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.ExportKind
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeInstantiation
import com.mezze.language.typedast.TypeKind
import com.mezze.language.typedast.TypeParam
import com.mezze.language.typedast.TypeScheme
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedParam
import com.mezze.language.typedast.TypedVar
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1.4 wire-up smoke test.
 *
 * Builds a TypedModule with one polymorphic decl (`id`) and one
 * monomorphic caller (`use_id`) that calls `id` with `Int`. Runs the
 * Monomorphizer to produce a specialization, hands the result to
 * TypedASTCompiler, and verifies that the caller's compiled IR contains
 * `CallDirectIR(specializedHash)` — not `CallDirectIR(genericHash)`.
 */
class MonoCompilerHybridTest {

    private val loc = Loc("t.mz", 1, 1, 1, 1)

    @Test
    fun `compileCall rewrites BindingRef ByHash to specialized hash when in rewrites map`() {
        // ── Generic decl: let id = {x: 'a} -> x ──────────────────────
        val genericHash = Hash("a".repeat(64))
        val tv = TypeVar("'a", 0, 1)
        val varT = MezzeType.VarType(tv)
        val genericLambda = TypedLambda(
            params = listOf(TypedParam("x", varT, loc)),
            body = TypedVar("x", BindingRef.Param(0), varT, loc),
            captures = emptyList(),
            type = MezzeType.FunctionType(varT, varT),
            loc = loc
        )
        val genericDecl = TypedLetDecl(
            hash = genericHash,
            name = "id",
            typeParams = listOf(TypeParam("'a", TypeKind.Star)),
            value = genericLambda,
            scheme = TypeScheme(
                typeParams = listOf(TypeParam("'a", TypeKind.Star)),
                abilityConstraints = emptyList(),
                type = MezzeType.FunctionType(varT, varT)
            ),
            isPublic = true,
            loc = loc
        )

        // ── Caller: let use_id = {n: Int} -> id<Int>(n) ──────────────
        // Caller is monomorphic. Its body has one TypedCall to id with
        // typeArgs=[Int].
        val callerHash = Hash("b".repeat(64))
        val intT = MezzeType.IntType
        val callerBody = TypedCall(
            function = TypedVar(
                "id",
                BindingRef.ByHash(genericHash),
                MezzeType.FunctionType(intT, intT),
                loc
            ),
            arg = TypedVar("n", BindingRef.Param(0), intT, loc),
            instantiation = TypeInstantiation(typeArgs = listOf(intT), abilityImpls = emptyList()),
            isTailCall = false,
            type = intT,
            loc = loc
        )
        val callerDecl = TypedLetDecl(
            hash = callerHash,
            name = "use_id",
            typeParams = emptyList(),
            value = TypedLambda(
                params = listOf(TypedParam("n", intT, loc)),
                body = callerBody,
                captures = emptyList(),
                type = MezzeType.FunctionType(intT, intT),
                loc = loc
            ),
            scheme = TypeScheme(emptyList(), emptyList(), MezzeType.FunctionType(intT, intT)),
            isPublic = true,
            loc = loc
        )

        // ── Run the Monomorphizer ────────────────────────────────────
        val mono = Monomorphizer(resolveDecl = {
            when (it) { genericHash -> genericDecl; else -> null }
        })
        val req = Monomorphizer.SpecRequest(genericHash, listOf(intT), emptyList())
        val result = mono.run(listOf(req))
        val specHash = result.callSiteRewrites[req]
            ?: error("monomorphizer should have produced a specialization")

        // ── Compile with the rewrites map ────────────────────────────
        val module = TypedModule(
            path = "t",
            hash = Hash("c".repeat(64)),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(genericDecl, callerDecl),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )
        // The compiler now keys mono rewrites by call-site loc instead of
        // SpecRequest tuples — matches the per-loc lookup the runtime
        // pipeline uses.
        val compiler = TypedASTCompiler(
            abilityImpls = emptyList(),
            stdlibHashes = emptySet(),
            monoCallSiteRewrites = mapOf(callerBody.loc to specHash)
        )
        val compileResult = compiler.compile(
            module = module,
            monoSpecializations = result.specializations.values.toList()
        )

        // ── Verify ───────────────────────────────────────────────────
        // The caller's compiled IR should reference the SPECIALIZED hash,
        // never the generic hash.
        val callerFn = compileResult.functions.first { it.hash == callerHash }
        val calls = collectCallDirects(callerFn.body)
        assertTrue(calls.any { it.targetHash == specHash }, "expected CallDirectIR($specHash) in caller body")
        assertTrue(calls.none { it.targetHash == genericHash }, "caller body should NOT reference generic hash $genericHash")

        // The specialization itself was compiled into a separate FunctionIR.
        assertTrue(compileResult.functions.any { it.hash == specHash }, "specialization should be in compiled output")
    }

    @Test
    fun `compile without rewrites map keeps generic CallDirectIR (back-compat)`() {
        // Same module as above — but we don't pass any rewrites or
        // specializations. Caller should still compile cleanly to
        // CallDirectIR(genericHash) — proves the default code path is
        // unchanged when mono is off.
        val genericHash = Hash("a".repeat(64))
        val intT = MezzeType.IntType
        val callerHash = Hash("b".repeat(64))
        val tv = TypeVar("'a", 0, 1)
        val varT = MezzeType.VarType(tv)

        val genericDecl = TypedLetDecl(
            hash = genericHash, name = "id",
            typeParams = listOf(TypeParam("'a", TypeKind.Star)),
            value = TypedLambda(
                params = listOf(TypedParam("x", varT, loc)),
                body = TypedVar("x", BindingRef.Param(0), varT, loc),
                captures = emptyList(),
                type = MezzeType.FunctionType(varT, varT),
                loc = loc
            ),
            scheme = TypeScheme(
                typeParams = listOf(TypeParam("'a", TypeKind.Star)),
                abilityConstraints = emptyList(),
                type = MezzeType.FunctionType(varT, varT)
            ),
            isPublic = true, loc = loc
        )
        val callerDecl = TypedLetDecl(
            hash = callerHash, name = "use_id",
            typeParams = emptyList(),
            value = TypedLambda(
                params = listOf(TypedParam("n", intT, loc)),
                body = TypedCall(
                    function = TypedVar("id", BindingRef.ByHash(genericHash), MezzeType.FunctionType(intT, intT), loc),
                    arg = TypedVar("n", BindingRef.Param(0), intT, loc),
                    instantiation = TypeInstantiation(typeArgs = listOf(intT), abilityImpls = emptyList()),
                    isTailCall = false, type = intT, loc = loc
                ),
                captures = emptyList(),
                type = MezzeType.FunctionType(intT, intT),
                loc = loc
            ),
            scheme = TypeScheme(emptyList(), emptyList(), MezzeType.FunctionType(intT, intT)),
            isPublic = true, loc = loc
        )
        val module = TypedModule(
            path = "t", hash = Hash("c".repeat(64)),
            imports = emptyList(), exports = emptyMap(),
            decls = listOf(genericDecl, callerDecl),
            abilityDecls = emptyList(), abilityImpls = emptyList(),
            effectDecls = emptyList(), performerDecls = emptyList(),
            dependencies = emptyList()
        )

        val compiler = TypedASTCompiler(abilityImpls = emptyList())
        val compileResult = compiler.compile(module = module)
        val callerFn = compileResult.functions.first { it.hash == callerHash }
        val calls = collectCallDirects(callerFn.body)
        // Without mono, the caller still references the generic hash directly.
        assertTrue(calls.any { it.targetHash == genericHash }, "caller body should reference generic hash")
    }

    /** Walk a NodeIR tree and collect every CallDirectIR. */
    private fun collectCallDirects(node: com.mezze.language.truffleir.NodeIR): List<CallDirectIR> {
        val out = mutableListOf<CallDirectIR>()
        walk(node, out)
        return out
    }

    private fun walk(node: com.mezze.language.truffleir.NodeIR, out: MutableList<CallDirectIR>) {
        if (node is CallDirectIR) {
            out.add(node)
            walk(node.arg, out)
            return
        }
        when (node) {
            is com.mezze.language.truffleir.MakeRecordIR -> for (f in node.fields) walk(f.value, out)
            is com.mezze.language.truffleir.CallIndirectIR -> { walk(node.function, out); walk(node.arg, out) }
            else -> { /* leaves and other variants are fine for this test */ }
        }
    }
}
