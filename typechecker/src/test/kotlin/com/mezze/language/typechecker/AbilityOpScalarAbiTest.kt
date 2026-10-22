package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * An ability-impl operation is callable through the SCALAR ABI.
 *
 * This is not a micro-optimisation, which is why it is pinned here. The record
 * ABI forces a call through the callee's trampoline — unpacking a record into
 * flat parameter slots is what `TrampolineRootNode.normalizeArgs` is FOR, and
 * only a scalar call may bypass it. Ability-impl ops were never registered in
 * `hashToParamNames`, so `paramNamesForHash` answered null for every one of
 * them, every ability-op call took the record ABI, and each paid a
 * LazyStub + Trampoline hop that the same function called as a plain `let` did
 * not. Measured at ~2.1x on a hot lambda (W-P1): 30.9s to 14.9s.
 *
 * So the assertion is on the lowered IR, not on a timing: a call site that
 * silently falls back to the record ABI is still CORRECT, which is exactly why
 * it went unnoticed.
 */
class AbilityOpScalarAbiTest {

    private class Lowered(
        val functions: List<FunctionIR>,
        val calls: List<CallDirectIR>,
    ) {
        /** The compiled function with this name, failing loudly if absent. */
        fun fn(name: String): FunctionIR = functions.firstOrNull { it.name == name }
            ?: throw AssertionError(
                "no compiled function named '$name' — found: ${functions.map { it.name }}",
            )

        /**
         * Calls targeting [name]'s hash.
         *
         * BY HASH, never by arity. An earlier version of this test asserted
         * "some 2-arg scalar call exists", which the program's own
         * `go { c = …, x = … }` satisfied — so it passed with the fix REMOVED.
         * The callee's hash is the only thing that identifies the op's call site.
         */
        fun callsTo(name: String): List<CallDirectIR> {
            val target = fn(name).hash
            return calls.filter { it.targetHash == target }
        }
    }

    /** Lower [source] through typecheck + TypedASTCompiler. */
    private fun lower(source: String): Lowered {
        val parsed = MezzeParser().parseParsedModule(source, file = "<scalar-abi>")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val result = TypeChecker().checkParsedModule(parsed, projectRoot, enablePrelude = true)
        if (result is TypeChecker.TypeCheckResult.Failure) {
            throw AssertionError("typecheck failed: ${result.errors.map { it.message }}")
        }
        val typedModule = (result as TypeChecker.TypeCheckResult.Success).typedModule!!
        val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typedModule)

        val allModules = HashMap<Hash, com.mezze.language.typedast.TypedModule>()
        allModules.putAll(PrecompiledStdlib.stdlibTypedModules)
        allModules[wrapped.hash] = wrapped
        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
            abilityImpls = allModules.values.flatMap { it.abilityImpls },
            stdlibHashes = PrecompiledStdlib.preloweredFunctions.keys,
        )
        val compiled = compiler.compile(wrapped)

        val calls = mutableListOf<CallDirectIR>()
        for (fn in compiled.functions) collectCalls(fn.body, calls)
        return Lowered(compiled.functions, calls)
    }

    private fun collectCalls(node: NodeIR?, out: MutableList<CallDirectIR>) {
        if (node == null) return
        if (node is CallDirectIR) out.add(node)
        node.forEachChildIR { collectCalls(it, out) }
    }

    @Test
    fun `an ability op call lowers to the scalar ABI`() {
        val lowered = lower(
            """
            type Cfg = { tag: Int }
            ability Enc for 'c where { enc: { it: 'c, v: Int } -> Int }
            impl Enc for Cfg where { enc = { it, v } -> do
              let a = v + it.tag
              let b = (a * 3) - it.tag
              let d = (b + a) * 2
              d + it.tag }

            let go: { c: Cfg, x: Int } -> Int = { c, x } -> c.enc { v = x }
            let main = {} -> go { c = { tag = 1 }, x = 2 }
            """.trimIndent(),
        )
        val encCalls = lowered.callsTo("enc")
        assertTrue(encCalls.isNotEmpty(), "the call to 'enc' was optimised away entirely — nothing to assert")
        assertTrue(
            encCalls.all { it.isScalarABI },
            "the call to 'enc' must use the scalar ABI, got: " +
                encCalls.joinToString { "args=${it.args.size} scalar=${it.isScalarABI}" },
        )
        assertTrue(
            encCalls.all { it.args.size == 2 },
            "a scalar call to enc passes the receiver and v as two flat args, got: " +
                encCalls.joinToString { "args=${it.args.size}" },
        )
    }

    @Test
    fun `a receiver read from a parameter is still scalar`() {
        // The case W-P1 is about: the receiver is not a compile-time constant.
        val lowered = lower(
            """
            type Cfg = { tag: Int }
            ability Enc for 'c where { enc: { it: 'c, v: Int } -> Int }
            impl Enc for Cfg where { enc = { it, v } -> do
              let a = v + it.tag
              let b = (a * 3) - it.tag
              let d = (b + a) * 2
              d + it.tag }
            use std::collections::seq

            let once: { xs: [Int], c: Cfg } -> [Int] = { xs, c } ->
              seq::map { xs = xs, f = { x } -> c.enc { v = x } }
            let main = {} -> once { xs = [1, 2], c = { tag = 1 } }
            """.trimIndent(),
        )
        val encCalls = lowered.callsTo("enc")
        assertTrue(encCalls.isNotEmpty(), "the call to 'enc' was optimised away entirely — nothing to assert")
        assertTrue(
            encCalls.all { it.isScalarABI },
            "an ability op whose receiver is captured from a parameter must still be scalar, got: " +
                encCalls.joinToString { "args=${it.args.size} scalar=${it.isScalarABI}" },
        )
    }

    @Test
    fun `a spread argument lowers through the record ABI, not the scalar path`() {
        // A spread hides the field set, so the scalar ABI cannot apply — but the call is
        // still CORRECT: emitCallDirect sees the `__spread__` field and falls back to the
        // record ABI (which handles spread). The receiver is merged in as `it` during emit,
        // so the op is fully applied. A spread-only call record used to be rejected; it is
        // now allowed in every call, scalar-eligible or not.
        val lowered = lower(
            """
            type Cfg = { tag: Int }
            ability Enc for 'c where { enc: { it: 'c, v: Int } -> Int }
            impl Enc for Cfg where { enc = { it, v } -> do
              let a = v + it.tag
              let b = (a * 3) - it.tag
              let d = (b + a) * 2
              d + it.tag }

            let main = {} -> do
              let args = { v = 2 }
              let c: Cfg = { tag = 1 }
              c.enc { ...args }
            """.trimIndent(),
        )
        val encCalls = lowered.callsTo("enc")
        assertTrue(encCalls.isNotEmpty(), "the call to 'enc' was optimised away entirely — nothing to assert")
        assertTrue(
            encCalls.none { it.isScalarABI },
            "a spread call to enc must fall back to the record ABI (isScalarABI=false), got: " +
                encCalls.joinToString { "args=${it.args.size} scalar=${it.isScalarABI}" },
        )
    }

    @Test
    fun `the op's registered param names match its compiled function exactly`() {
        // The one way this change could corrupt a program: register names in an
        // order that differs from the compiled function's own paramNames, and
        // every scalar call hands the callee its arguments transposed. Assert the
        // two agree rather than trusting that they do.
        val source = """
            type Cfg = { tag: Int }
            ability Enc for 'c where { enc: { it: 'c, a: Int, b: Int } -> Int }
            impl Enc for Cfg where { enc = { it, a, b } -> (a * 100) + b + it.tag }

            let main = {} -> do
              let c: Cfg = { tag = 0 }
              c.enc { b = 2, a = 1 }
        """.trimIndent()
        val parsed = MezzeParser().parseParsedModule(source, file = "<scalar-abi>")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val result = TypeChecker().checkParsedModule(parsed, projectRoot, enablePrelude = true)
        val typedModule = (result as TypeChecker.TypeCheckResult.Success).typedModule!!
        val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typedModule)
        val allModules = HashMap<Hash, com.mezze.language.typedast.TypedModule>()
        allModules.putAll(PrecompiledStdlib.stdlibTypedModules)
        allModules[wrapped.hash] = wrapped
        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
            abilityImpls = allModules.values.flatMap { it.abilityImpls },
            stdlibHashes = PrecompiledStdlib.preloweredFunctions.keys,
        )
        val compiled = compiler.compile(wrapped)

        val encFn = compiled.functions.firstOrNull { it.name == "enc" }
            ?: throw AssertionError(
                "no compiled function named 'enc' — found: ${compiled.functions.map { it.name }}",
            )
        assertEquals(
            listOf("it", "a", "b"), encFn.paramNames,
            "the compiled op's param order is what a scalar call site must order its args by",
        )
    }
}
