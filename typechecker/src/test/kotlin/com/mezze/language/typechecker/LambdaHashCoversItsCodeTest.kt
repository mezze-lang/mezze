package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * One hash, one function — for a lambda too.
 *
 * A lifted lambda is hashed from its typed expression, and that hash does not
 * see types. The code it lowers to does: `{ x } -> x` over `Int`, `Str` and
 * `Float` reads its parameter from a LONG, an OBJECT and a DOUBLE slot. The three
 * shared one hash, so the bytes under that hash depended on which module was
 * compiled first — the opposite of content addressing, and what a store that
 * refuses a second, different function under one hash would trip over.
 *
 * [DedupeCollisionGateTest] holds the same invariant inside one compile. This
 * holds it ACROSS compiles, which is where a store or another machine meets it.
 */
class LambdaHashCoversItsCodeTest {

    private val lambdas = listOf(
        "{ x } -> x",
        "{ x } -> [x]",
        "{ x } -> { v = x }",
        "{ x } -> do\n    let a = x\n    a",
        "{ x } -> if True then x else x",
        "{ x } -> { l = x, r = x }",
        "{ x } -> seq::map { xs = [x], f = { x } -> x }",
    )

    private val elements = listOf("Int", "Nat", "Float", "Bool", "Str", "[Int]", "{ a: Int }", "{ a: Str, b: Float }")

    private fun lowered(source: String): List<FunctionIR> {
        val parsed = MezzeParser().parseParsedModule(source, "app.mz")
        val checked = TypeChecker().checkParsedModule(parsed, Paths.get("."), enablePrelude = true)
            as? TypeChecker.TypeCheckResult.Success ?: error("did not type-check:\n$source")
        return checked.loweredFunctions!!
    }

    private fun content(fn: FunctionIR): String =
        java.util.Base64.getEncoder().encodeToString(StoreSerialization.contentOf(StoreSerialization.serializeFunctionIR(fn)))

    @Test
    fun `the same lambda text at different types never puts two functions under one hash`() {
        // hash -> (content -> where it was first seen)
        val seen = HashMap<String, MutableMap<String, String>>()
        val described = HashMap<String, String>()
        var compiled = 0
        for (lambda in lambdas) for (element in elements) {
            val source = "use std::collections::seq\n\nlet f = { xs: [$element] } -> seq::map { xs = xs, f = $lambda }\n"
            for (fn in lowered(source)) {
                compiled++
                val c = content(fn)
                seen.getOrPut(fn.hash.hex) { LinkedHashMap() }.putIfAbsent(c, "`$lambda` over $element")
                described.putIfAbsent(c, "params=${fn.paramNames}${fn.paramSlots} captures=${fn.captureSlots} locals=${fn.localNames}${fn.localSlots}")
            }
        }
        assertTrue(compiled > lambdas.size * elements.size, "nothing was compiled")
        val collisions = seen.filterValues { it.size > 1 }
        assertTrue(collisions.isEmpty()) {
            "${collisions.size} hash(es) carry more than one function:\n" + collisions.entries.take(6).joinToString("\n") { (hash, byContent) ->
                "  ${hash.take(10)}:\n" + byContent.entries.joinToString("\n") { (c, where) -> "    $where  ${described[c]}" }
            }
        }
    }
}
