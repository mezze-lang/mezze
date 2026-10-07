package com.mezze.language.cli

import com.mezze.language.ast.MezzeType
import com.mezze.language.cli.commands.SampleBranches
import com.mezze.language.cli.commands.SampleScripts
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Scripted performers: running a function whose effects nothing handles, with
 * every operation's result drawn by the row and shown — and branches on those
 * results read like branches on arguments.
 */
class SampleScriptsTest {

    private val config = """
        type Mode = | Strict, Lenient |
        effect Config where {
          threshold: {} -> Int <Config>,
          mode: {} -> Mode <Config>,
          note: { msg: Str } -> {} <Config>
        }
    """.trimIndent()

    private class Checked(val success: TypeChecker.TypeCheckResult.Success, val decls: List<TypedLetDecl>) {
        fun decl(name: String) = decls.single { it.name == name }
    }

    private fun check(body: String): Checked {
        val parsed = MezzeParser().parseParsedModule("$config\n${body.trimIndent()}", "test.mz")
        val success = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
            as? TypeChecker.TypeCheckResult.Success ?: error("typecheck failed")
        return Checked(success, success.typedModule!!.decls.filterIsInstance<TypedLetDecl>())
    }

    private fun plan(c: Checked, fn: String): SampleScripts.Plan {
        val residual = (c.decl(fn).scheme.type as MezzeType.FunctionType).residualEffects!!
        return SampleScripts.plan(residual, c.success.typedModule!!.effectDecls, emptyMap())
    }

    @Test
    fun `a monomorphic effect is scripted, its result fields drawn by the row`() {
        val c = check("pub let f: { n: Int } -> Bool <Config> = { n } -> n > Config.threshold {}")
        val scripts = (plan(c, "f") as SampleScripts.Plan.Scripted).scripts
        assertEquals(listOf("Config"), scripts.map { it.effect })
        val impl = SampleScripts.implDecl(scripts.single()) { com.mezze.language.ast.prettyPrintType(it) }
        // Values arrive as FIELDS, so the row draws them where it can show them.
        assertTrue(impl.startsWith("impl Config for MezSampleScriptConfig { r_threshold: Int, r_mode: Mode } where {"), impl)
        assertTrue("  threshold = {} -> r_threshold" in impl, impl)
        // An operation returning `{}` is answered with `{}` and gets no field.
        assertTrue("  note = { msg } -> {}" in impl, impl)
    }

    /** No type to draw at: a polymorphic operation is still refused, naming why. */
    @Test
    fun `an effect with a polymorphic operation is refused`() {
        val c = check("""
            effect Ask where { ask: { x: 'a } -> 'a <Ask> }
            pub let f: { n: Int } -> Int <Ask> = { n } -> Ask.ask { x = n }
        """)
        val refused = plan(c, "f") as SampleScripts.Plan.Refused
        assertEquals("Ask", refused.effect)
        assertTrue("polymorphic" in refused.reason, refused.reason)
    }

    private fun rows(c: Checked, fn: String): List<String> {
        val scripts = (plan(c, fn) as SampleScripts.Plan.Scripted).scripts
        val decl = c.decl(fn)
        val fields = ((decl.scheme.type as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields
        return SampleBranches.inventory(decl, fields, c.decls, context = SampleScripts.context(scripts)).map { r ->
            r.pins.entries.joinToString(", ") { (k, v) -> "${if (k.startsWith("ctx:")) "ctx" else k} = ${v.source}" } + " | " + r.why
        }
    }

    /**
     * `n > t`, `t` from an operation: two INPUTS compared, so the same
     * less/equal/greater pairs as `a < b` — one side scripted.
     */
    @Test
    fun `a comparison with an operation result pins both inputs together`() {
        val c = check("""
            pub let f: { n: Int } -> Bool <Config> = { n } -> do
              let t = Config.threshold {}
              n > t
        """)
        assertEquals(
            listOf(
                "n = 0, ctx = 1 | if n > ⟨Config.threshold⟩ — else (<)",
                "n = 1, ctx = 1 | if n > ⟨Config.threshold⟩ — else (==)",
                "n = 1, ctx = 0 | if n > ⟨Config.threshold⟩ — then (>)",
            ),
            rows(c, "f"),
        )
    }

    /** `match Config.mode {}` — the script returns each constructor in turn. */
    @Test
    fun `a match on an operation result pins its arms`() {
        val c = check("""
            pub let f: { n: Int } -> Int <Config> = { n } -> match Config.mode {} is
              Strict -> 0
              Lenient -> n
        """)
        assertEquals(
            listOf("ctx = Strict | match ⟨Config.mode⟩ — `Strict` arm", "ctx = Lenient | match ⟨Config.mode⟩ — `Lenient` arm"),
            rows(c, "f"),
        )
    }
}
