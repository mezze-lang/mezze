package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.shapeKey
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Paths

/**
 * A generic wrapper that HOLDS a dictionary forwards it to a callee that needs the
 * same one — it does not build a new dictionary from the constraint's concrete
 * argument.
 *
 * `grab` below is an inherent op whose only job is to call `unbox`, a `let`
 * constrained `with FromJson 'a Str`. The prescan hands `grab` the callee's
 * dictionaries to forward (`__dict_from_json`). At the call, resolving `from_json`
 * concretely from the `Str` in the constraint finds `impl FromJson 'a for Str`,
 * whose op needs `__dict_decode` for the TARGET `'a` — which `grab` leaves
 * generic, so that slot cannot be planned here, and the wrapper `refToDictedOp`
 * would build carries a `from_json` with no `decode`. The dictionary `grab`
 * already holds was built where the target WAS known; it is complete, and it is
 * the one to pass.
 *
 * This is exactly http's inherent `from_json` on `Result Response HttpError` over
 * `from_json_result`. MEASURED once the whole stdlib was in the snapshot: that
 * wrapper's body was `CallDirect(from_json_result, [it, MakeLambdaIR(...)])` — a
 * fresh, incomplete wrapper where its own `ReadLocal(__dict_from_json)` belonged —
 * and `.send {}.from_json {}` died with "no dictionary was supplied for ability
 * operation `decode`". The program below has no decode call site, so no derived
 * decoder is in scope: the condition under which a rebuilt wrapper is necessarily
 * incomplete.
 */
class GenericWrapperForwardsHeldDictTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private val source = """
        use std::codec::json::{ FromJson }
        use std::codec::core::{ DecodeError }

        type Box = { s: Str }

        let unbox: { b: Box } with FromJson 'a Str -> Result 'a DecodeError = { b } -> b.s.from_json {}

        impl Box where {
          grab = { it } -> unbox { b = it },
        }
    """.trimIndent()

    private fun lower(): Map<String, FunctionIR> {
        val parsed = MezzeParser().parseParsedModule(source, file = "<forward-held>")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val result = TypeChecker().checkParsedModule(parsed, projectRoot, enablePrelude = true)
        if (result is TypeChecker.TypeCheckResult.Failure) {
            throw AssertionError("typecheck failed: ${result.errors.map { it.message }}")
        }
        val typed = (result as TypeChecker.TypeCheckResult.Success).typedModule!!
        val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typed)
        val modules = HashMap<Hash, com.mezze.language.typedast.TypedModule>(PrecompiledStdlib.stdlibTypedModules)
        for (cached in ModuleTypeChecker.getAllCachedModules().values) {
            val m = cached.typedModule ?: continue
            modules.putIfAbsent(m.hash, m)
        }
        modules[wrapped.hash] = wrapped
        val opNames = buildMap<Hash, Set<String>> {
            for (m in modules.values) for (d in m.abilityDecls) put(d.hash, d.operations.mapTo(HashSet()) { it.name })
        }
        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
            abilityImpls = modules.values.flatMap { it.abilityImpls },
            stdlibHashes = PrecompiledStdlib.preloweredFunctions.keys,
            preloweredFunctions = PrecompiledStdlib.preloweredFunctions,
            preloweredDictMethods = PrecompiledStdlib.preloweredDictMethods,
            abilityOpNames = opNames,
        )
        // As `TypedModuleEmitter` does — entry-point promotion reads the lets.
        compiler.setDeclIndex(wrapped.decls.filterIsInstance<TypedLetDecl>().associateBy { it.hash })
        return compiler.compile(wrapped).functions.associateBy { it.name }
    }

    @Test
    fun `the wrapper op is handed the callee's dictionary to forward`() {
        val fns = lower()
        val grab = fns["grab"] ?: throw AssertionError("no compiled op 'grab' — found ${fns.keys}")
        assertTrue(
            "__dict_from_json" in grab.paramNames,
            "grab should take __dict_from_json for its callee; params were ${grab.paramNames}",
        )
    }

    @Test
    fun `the wrapper forwards the dictionary it holds instead of rebuilding it`() {
        val grab = lower()["grab"] ?: throw AssertionError("no compiled op 'grab'")
        val dictSlot = grab.paramNames.indexOf("__dict_from_json")
        val shape = grab.shapeKey()
        assertFalse(
            shape.contains("MakeLambdaIR("),
            "grab rebuilt a from_json dictionary (a wrapper lambda) — one that cannot carry the " +
                "decode dictionary for a target this generic op does not know. Body: $shape",
        )
        assertTrue(
            shape.contains("ReadLocalIR(slot=$dictSlot,"),
            "grab should forward its own __dict_from_json (slot $dictSlot) to unbox. Body: $shape",
        )
    }
}
