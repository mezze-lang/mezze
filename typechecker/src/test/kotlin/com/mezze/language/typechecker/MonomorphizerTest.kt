package com.mezze.language.typechecker

import com.mezze.language.monoast.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.mono.Monomorphizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MonomorphizerTest {

    private val parser = MezzeParser()
    private val checker = TypeChecker()
    private val mono = Monomorphizer()

    private fun monoSource(source: String): MonoModule {
        val parsed = parser.parseParsedModule(source, file = "test.mz")
        val typedModule = checker.checkParsedModule(parsed)
        return mono.monomorphize(typedModule, mapOf(typedModule.hash to typedModule))
    }

    @Test
    fun `simple function monomorphized`() {
        val module = monoSource("let x = 42")
        assertTrue(module.functions.isNotEmpty())
    }

    @Test
    fun `function with body`() {
        val module = monoSource("let double = { x: Int } -> x + x")
        val fn = module.functions.find { it.name == "double" }
        assertNotNull(fn)
        assertTrue(fn!!.body is MonoLambda)
    }

    @Test
    fun `no ability declarations in output`() {
        val source = """
ability Eq for 'a where {
    eq: {it: 'a, r: 'a} -> Bool,
    neq: {it: 'a, r: 'a} -> Bool
}
let x = 42
""".trim()
        val module = monoSource(source)
        // MonoModule has no abilityDecls field — abilities are GONE
        // Just verify it doesn't crash and produces functions
        assertTrue(module.functions.isNotEmpty())
    }

    @Test
    fun `effects survive monomorphization`() {
        val source = """
effect Log where {
    println: { msg: Str } -> {} <Log>
}
let x = 42
""".trim()
        val module = monoSource(source)
        assertTrue(module.effectDecls.isNotEmpty())
        assertEquals("Log", module.effectDecls[0].name)
    }

    @Test
    fun `all functions have hashes`() {
        val module = monoSource("""
let a = 1
let b = 2
let c = 3
""".trimIndent())
        for (fn in module.functions) {
            assertNotEquals(Hash.ZERO, fn.hash, "Function ${fn.name} should have a hash")
        }
    }

    @Test
    fun `mono function refs are ByHash only`() {
        val source = """
let helper = { x: Int } -> x + 1
let main = { } -> helper { x = 42 }
""".trim()
        val module = monoSource(source)
        // Walk all bindings and verify no string-based refs
        for (fn in module.functions) {
            walkMonoExpr(fn.body) { expr ->
                if (expr is MonoVar) {
                    val binding = expr.binding
                    if (binding is MonoBinding.Function) {
                        val ref = binding.ref
                        assertTrue(
                            ref is MonoFunctionRef.ByHash || ref is MonoFunctionRef.Native,
                            "Expected ByHash or Native, got $ref"
                        )
                    }
                }
            }
        }
    }

    private fun walkMonoExpr(expr: MonoExpr, visitor: (MonoExpr) -> Unit) {
        visitor(expr)
        when (expr) {
            is MonoCall -> { walkMonoExpr(expr.function, visitor); walkMonoExpr(expr.arg, visitor) }
            is MonoLambda -> walkMonoExpr(expr.body, visitor)
            is MonoIf -> { walkMonoExpr(expr.condition, visitor); walkMonoExpr(expr.thenBranch, visitor); walkMonoExpr(expr.elseBranch, visitor) }
            is MonoBlock -> { expr.statements.forEach { s -> when(s) { is MonoStatement.Let -> walkMonoExpr(s.value, visitor); is MonoStatement.Expr -> walkMonoExpr(s.expr, visitor) } }; walkMonoExpr(expr.result, visitor) }
            is MonoMatch -> { walkMonoExpr(expr.scrutinee, visitor); expr.cases.forEach { c -> walkMonoExpr(c.body, visitor) } }
            is MonoRecord -> expr.entries.forEach { e -> when(e) { is MonoRecordEntry.Field -> walkMonoExpr(e.value, visitor); is MonoRecordEntry.Spread -> walkMonoExpr(e.expr, visitor) } }
            is MonoFieldAccess -> walkMonoExpr(expr.record, visitor)
            is MonoRecordUpdate -> { walkMonoExpr(expr.record, visitor); expr.updates.forEach { e -> when(e) { is MonoRecordEntry.Field -> walkMonoExpr(e.value, visitor); is MonoRecordEntry.Spread -> walkMonoExpr(e.expr, visitor) } } }
            is MonoVariantConstruct -> expr.payload?.let { walkMonoExpr(it, visitor) }
            is MonoSeq -> expr.elements.forEach { e -> when(e) { is MonoSeqElement.Regular -> walkMonoExpr(e.expr, visitor); is MonoSeqElement.Spread -> walkMonoExpr(e.expr, visitor) } }
            is MonoPerform -> walkMonoExpr(expr.body, visitor)
            is MonoPerformOp -> walkMonoExpr(expr.arg, visitor)
            is MonoStringLit -> expr.parts.forEach { p -> if (p is MonoStringPart.Interpolation) walkMonoExpr(p.expr, visitor) }
            else -> {} // Literals, Var
        }
    }
}
