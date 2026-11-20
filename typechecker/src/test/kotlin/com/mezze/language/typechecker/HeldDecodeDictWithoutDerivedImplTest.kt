package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.shapeKey
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A decode-flavored op called on a CONCRETE receiver, inside a function that
 * HOLDS that op's dictionary, dispatches through the dictionary — whether or
 * not a DERIVED decoder happens to be in the compiler's impl index.
 *
 * This is the shape of `std::codec::query`'s `from_query` for `Str`:
 *
 *     { it } -> parse_pairs { s = it }.decode { config = query_default }
 *
 * The receiver is a concrete seq, so the receiver cannot choose the impl — a
 * return-driven ability's impl is chosen by the TARGET type, and the enclosing
 * function was handed that choice as `__dict_decode`. The gate that routes a
 * concrete-receiver call through the held dictionary (`viaEnclosingDecodeDict`)
 * used to demand a WITNESS: some derived decoder in `abilityOpIndex["decode"]`.
 * Derived decoders are generated per concrete decode target, so the witness
 * exists only in a compile that also contains a call site decoding into a
 * concrete type. It never exists while compiling the stdlib, a dependency on
 * its own (PrecompiledDeps), or a library module with no such call site — and
 * there `.decode` fell through to the ORDINARY field-access path and lowered as
 * `CallIndirect(ReadField(<the pairs>, "decode"))`, a field read named `decode`
 * off a seq.
 *
 * MEASURED, compiling every stdlib module into the snapshot: `from_query` was
 * lowered with `params=[it, __dict_decode]` — the dictionary delivered — and a
 * body that never read it; `"q=mezze&page=3".from_query {}` then died with
 * `I0004: Expected record for field 'decode', got: MezzeSeq`. The gate's
 * inputs at that site, once per module compiler: frame holds `__dict_decode`,
 * `abilityOpIndex["decode"]` empty, node `abilityHash` null (the emitter leaves
 * it null when only one ability in scope declares the name). `main` is spared
 * only because `codec/query` is not reachable from the prelude and so is never
 * precompiled; it is compiled in the user's program, where the witness exists.
 *
 * The fix asks the question the witness was a proxy for: is the op's ABILITY
 * decode-flavored? Read off the ability DECLARATIONS (`abilityOpNames`), which
 * every compile has, never off which impls are in scope.
 *
 * The assertions are on the lowered IR, not on a run: the program below has no
 * call site, so no derived decoder exists, which is the condition under test.
 */
class HeldDecodeDictWithoutDerivedImplTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    /** A user codec's own entry op over the real `Decode` ability — the stdlib
     *  `from_query` shape, with nothing in the program decoding into a concrete
     *  target. */
    private val source = """
        use std::codec::core::{ Decode, DecodeError }
        use std::codec::query::{ QueryC, parse_pairs, query_default }

        ability Grab 'a for 'in where {
          grab: { it: 'in } -> Result 'a DecodeError,
        }

        impl Grab 'a for Str where {
          grab: { it: Str } with Decode QueryC 'a [{ k: Str, v: Str }] -> Result 'a DecodeError =
            { it } -> parse_pairs { s = it }.decode { config = query_default },
        }
    """.trimIndent()

    /** Lower [source] the way a user compile does — same module set for
     *  `abilityImpls` and `abilityOpNames` as `TypedModuleEmitter` — and return
     *  the compiled `grab` op. */
    private fun lowerGrab(): FunctionIR {
        val parsed = MezzeParser().parseParsedModule(source, file = "<held-dict>")
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

        val impls = modules.values.flatMap { it.abilityImpls }
        val opNames = buildMap<Hash, Set<String>> {
            for (m in modules.values) {
                for (d in m.abilityDecls) put(d.hash, d.operations.mapTo(HashSet()) { it.name })
            }
        }
        // The condition under test: no derived decoder anywhere in the index.
        assertTrue(
            impls.none { com.mezze.language.typechecker.emit.DecodeTargetResolver.isCodecDecodeImpl(it) },
            "a derived decoder is in scope — this program must not decode into any concrete target",
        )

        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
            abilityImpls = impls,
            stdlibHashes = PrecompiledStdlib.preloweredFunctions.keys,
            abilityOpNames = opNames,
        )
        val compiled = compiler.compile(wrapped)
        return compiled.functions.firstOrNull { it.name == "grab" }
            ?: throw AssertionError("no compiled op named 'grab' — found: ${compiled.functions.map { it.name }}")
    }

    @Test
    fun `the entry op is handed the decode dictionary`() {
        val grab = lowerGrab()
        assertTrue(
            "__dict_decode" in grab.paramNames,
            "grab should take `__dict_decode` for its `with Decode …` constraint; params were ${grab.paramNames}",
        )
    }

    @Test
    fun `decode on the concrete receiver dispatches through the held dictionary`() {
        val shape = lowerGrab().shapeKey()
        assertFalse(
            shape.contains("fieldName=decode,"),
            "`.decode` was lowered as a FIELD READ off the pairs seq — the dynamic receiver " +
                "path — instead of through the `__dict_decode` the frame holds. At runtime this " +
                "is `I0004: Expected record for field 'decode', got: MezzeSeq`.",
        )
        assertTrue(
            shape.contains("DictMethodCallIR("),
            "`.decode` should be a DictMethodCallIR through the held `__dict_decode`; body was: $shape",
        )
    }
}
