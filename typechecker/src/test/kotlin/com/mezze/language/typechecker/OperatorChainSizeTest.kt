package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An operator chain grows its typed tree linearly.
 *
 * `a && b` is emitted as the method call `a.and { r = b }`. The left operand used
 * to be built twice — once into the call's argument as `it`, once more inside the
 * callee `a.and` — and in `x1 && x2 && … && xk` the left operand of each `&&` is
 * the whole chain before it, so the tree doubled per term: 16 terms made 163,837
 * nodes, a 78 MB stored blob, and a 13-term predicate in the stdlib took the
 * precompiled database to 1.1 GB.
 *
 * Counted as a TREE walk (every reference, the way serialization writes it), not
 * as distinct objects: sharing one built receiver between the two places would
 * fix memory and leave every walk, and every stored blob, exponential.
 */
class OperatorChainSizeTest {

    private fun declValue(source: String): TypedExpr {
        val module = TypeChecker().checkParsedModuleToTyped(MezzeParser().parseParsedModule(source, "chain.mz"))
        return module.decls.filterIsInstance<TypedLetDecl>().single { it.name == "chain" }.value
    }

    /** Every typed node reachable, counting a node once per reference to it. */
    private fun treeSize(root: Any?): Int {
        if (root == null) return 0
        if (root is List<*>) return root.sumOf { treeSize(it) }
        if (root !is TypedExpr && root !is TypedRecordEntry && root !is TypedStringPart) return 0
        var n = if (root is TypedExpr) 1 else 0
        var c: Class<*>? = root.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                n += treeSize(f.get(root))
            }
            c = c.superclass
        }
        return n
    }

    private fun sizes(op: String, ty: String): List<Int> = listOf(4, 8, 16).map { k ->
        val params = (1..k).joinToString(", ") { "x$it: $ty" }
        treeSize(declValue("let chain = { $params } -> ${(1..k).joinToString(" $op ") { "x$it" }}"))
    }

    private fun assertLinear(label: String, s: List<Int>) {
        val (k4, k8, k16) = s
        // Linear: doubling the terms roughly doubles the tree. Exponential: 4 → 8
        // terms multiplies it by ~16 and 8 → 16 by ~256.
        assertTrue(k16 < 3 * k8 && k8 < 3 * k4, "$label tree sizes for 4/8/16 terms: $s — not linear")
    }

    @Test
    fun `an and-chain grows linearly`() = assertLinear("&&", sizes("&&", "Bool"))

    @Test
    fun `an addition chain grows linearly`() = assertLinear("+", sizes("+", "Int"))

    @Test
    fun `an equality on a chain grows linearly`() {
        val s = listOf(4, 8, 16).map { k ->
            val params = (1..k).joinToString(", ") { "x$it: Int" }
            treeSize(declValue("let chain = { $params } -> ${(1..k).joinToString(" + ") { "x$it" }} == 0"))
        }
        assertLinear("== over +", s)
    }
}
