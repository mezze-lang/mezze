package com.mezze.language.typechecker

import com.mezze.language.ast.MagicParams
import com.mezze.language.typechecker.compiler.TypedASTCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C.4/C.5 — the ratchet on "which functions need a dictionary param".
 *
 * Two answers to that question exist. The emitter SCANS bodies for method calls
 * on a variable-typed receiver. The declared constraints on a function's own type
 * already say it: `with Ord 'a`, `'a` still a variable, means a dict per `Ord`
 * operation. For a well-typed program the second is the authority and the first is
 * a derivation of it, so a dict the scan demands must always be one a declared
 * constraint justifies.
 *
 * Where that fails, the scan is inventing a requirement the types do not support —
 * and each such place is a function whose emitted signature depends on a body walk
 * rather than on what it declares. That is what C.5 has to delete, and this gate
 * is how the remainder stays visible and cannot grow.
 *
 * The gate does NOT assert the converse. A dict param carries one OPERATION, and a
 * constraint names an ability rather than which of its operations a body uses, so
 * the declared answer is deliberately coarser: every `Ord`-constrained function
 * would take four dict params where it uses one. Narrowing the declared set to the
 * operations actually used is the scan's remaining job.
 */
class DictJustificationGateTest {

    /**
     * The known-unjustified demands, by declaration name.
     *
     * All four compute on the result of a native call — `__native_nat_to_int {…} %
     * 2` — and a native's return type comes back as a bare variable, so concrete
     * Int arithmetic reads as polymorphic and the scan asks for an arithmetic dict
     * that nothing in the types calls for. Fixing the native-return hole empties
     * this set; until then it is the exact list, and a fifth entry fails the gate.
     */
    private val unjustifiedByNativeReturnType = setOf(
        "enumerate",
        "zip_with_index",
        "is_even",
        "is_odd",
    )

    /** A decl's gate key: its name, plus enough of its hash that two same-named
     *  operations on different impls stay distinct. */
    private fun key(name: String, hash: com.mezze.language.typedast.Hash): String =
        "$name#${hash.toString().take(8)}"

    private fun nameOfKey(key: String): String = key.substringBefore('#')

    private fun scanStdlib(): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
        val modules = PrecompiledStdlib.stdlibTypedModules.values
        assertTrue(
            modules.isNotEmpty(),
            "no typed stdlib modules loaded — the gate would vacuously pass and hide every finding",
        )
        val allImpls = modules.flatMap { it.abilityImpls }
        val allOpNames = buildMap {
            for (m in modules) {
                for (decl in m.abilityDecls) put(decl.hash, decl.operations.mapTo(HashSet()) { it.name })
            }
        }
        val scanned = HashMap<String, Set<String>>()
        val declared = HashMap<String, Set<String>>()
        for (module in modules) {
            val compiler = TypedASTCompiler(abilityImpls = allImpls, abilityOpNames = allOpNames)
            compiler.prescanOnly(module)
            val names = HashMap<com.mezze.language.typedast.Hash, String>()
            for (d in module.decls) {
                if (d is com.mezze.language.typedast.TypedLetDecl) names[d.hash] = d.name
            }
            for (impl in module.abilityImpls) {
                for (opName in impl.operations.keys) {
                    names[com.mezze.language.typedast.Hash.computeOpHash(impl.hash, opName)] = opName
                }
            }
            for ((h, methods) in compiler.getDictMethodsForHash()) {
                val name = names[h] ?: continue
                // The scan answers in dict SLOTS (`eq@a`, `eq@e` — one operation per
                // type variable, C.2); the declared side answers in operations. Read
                // the operation out of the key so the two are comparable.
                scanned[key(name, h)] = methods.mapTo(HashSet()) { MagicParams.methodOfKey(it) }
            }
            for ((h, methods) in compiler.getDeclaredDictMethodsForHash()) {
                names[h]?.let { declared[key(it, h)] = methods }
            }
        }
        return scanned to declared
    }

    @Test
    fun `every dict the body scan demands is one a declared constraint justifies`() {
        val (scanned, declared) = scanStdlib()
        val unjustified = scanned.entries
            .filter { (key, methods) -> (methods - (declared[key] ?: emptySet())).isNotEmpty() }
            .filter { (key, _) -> nameOfKey(key) !in unjustifiedByNativeReturnType }
            .sortedBy { it.key }
        if (unjustified.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${unjustified.size} function(s) are emitted with a dictionary param that no")
                    appendLine("declared constraint calls for. Each one's signature depends on a walk of its")
                    appendLine("body rather than on its own type. Either the constraint is missing from the")
                    appendLine("type (an inference gap) or the scan is inventing the need.")
                    appendLine()
                    for ((key, methods) in unjustified) {
                        appendLine("  $key: scan wants ${methods.sorted()}, declares ${(declared[key] ?: emptySet<String>()).sorted()}")
                    }
                },
            )
        }
    }

    @Test
    fun `the known-unjustified list has no stale entries`() {
        val (scanned, declared) = scanStdlib()
        val stale = unjustifiedByNativeReturnType.filter { name ->
            scanned.none { (key, methods) ->
                nameOfKey(key) == name && (methods - (declared[key] ?: emptySet())).isNotEmpty()
            }
        }
        if (stale.isNotEmpty()) {
            fail(
                "${stale.size} entr(ies) in unjustifiedByNativeReturnType no longer demand an " +
                    "unjustified dict — delete them, so the list keeps meaning what it says: $stale",
            )
        }
    }
}
