package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import com.mezze.language.parsedast.ParsedModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for #274-followup B: `<...>` in an annotated effect row should
 * absorb effects performed by the body. Concretely, if a function
 * annotated `<Async, ...>` performs Time internally, the annotated
 * function's inferred type should carry `<Async, Time>` — the row var
 * `...` gets bound to the missing effect(s).
 *
 * Without this fix, the annotation is used verbatim: `<Async, ...>`
 * means "some open row starting with Async" but the body-performed
 * Time is silently dropped from the caller's row inference.
 */
class RowVarEffectPropagationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun functionEffects(module: ParsedModule, varName: String): Pair<Set<String>, String> {
        val result = typeChecker.typeCheck(module, Paths.get("."))
        val fnType = result.getVariableType(varName) as? MezzeType.FunctionType
        assertNotNull(fnType, "$varName should be a FunctionType, got: ${result.getVariableType(varName)}")
        val effects = fnType!!.effects?.effects ?: emptySet()
        return effects to "$fnType"
    }

    @Test
    fun `annotated open row absorbs body-performed effect`() {
        // Function annotated with `<Foo, ...>` performs both Foo and Bar
        // in the body. The `...` should absorb Bar so the function's
        // effect row is {Foo, Bar}, NOT just {Foo}.
        val source = """
            effect Foo where { foo: {} -> {} <Foo> }
            effect Bar where { bar: {} -> {} <Bar> }

            impl Foo for FooImpl where { foo = {} -> {} }
            impl Bar for BarImpl where { bar = {} -> {} }

            let use_both : {} -> {} <Foo, ...> = {} -> do
              Foo.foo {}
              Bar.bar {}
        """.trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val (effects, fullType) = functionEffects(module, "use_both")
        assertTrue(effects.contains("Foo"),
            "Should contain Foo (declared in annotation). Got: $effects. Full type: $fullType")
        assertTrue(effects.contains("Bar"),
            "Should contain Bar (absorbed by `...`). Got: $effects. Full type: $fullType")
    }

    @Test
    fun `caller inherits absorbed effect from callee`() {
        // If `use_both` has effect row `<Foo, Bar>` after absorption,
        // a caller of `use_both` should see BOTH effects in its own row.
        val source = """
            effect Foo where { foo: {} -> {} <Foo> }
            effect Bar where { bar: {} -> {} <Bar> }

            impl Foo for FooImpl where { foo = {} -> {} }
            impl Bar for BarImpl where { bar = {} -> {} }

            let use_both : {} -> {} <Foo, ...> = {} -> do
              Foo.foo {}
              Bar.bar {}

            let caller = {} -> use_both {}
        """.trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val (effects, fullType) = functionEffects(module, "caller")
        assertTrue(effects.contains("Foo"), "Caller should inherit Foo. Got: $effects. Full type: $fullType")
        assertTrue(effects.contains("Bar"),
            "Caller should inherit Bar via the propagated `...`. Got: $effects. Full type: $fullType")
    }

    @Test
    fun `closed row annotation rejects body performing extra effect`() {
        // If the annotation is `<Foo>` (closed, no `...`), and the body
        // performs Foo AND Bar, this SHOULD be a typecheck error —
        // the annotation lies about what the function does.
        val source = """
            effect Foo where { foo: {} -> {} <Foo> }
            effect Bar where { bar: {} -> {} <Bar> }

            impl Foo for FooImpl where { foo = {} -> {} }
            impl Bar for BarImpl where { bar = {} -> {} }

            let lies : {} -> {} <Foo> = {} -> do
              Foo.foo {}
              Bar.bar {}
        """.trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(module, Paths.get("."))
        }
    }

    @Test
    fun `probe — no annotation, body infers both`() {
        val source = """
            effect Foo where { foo: {} -> {} <Foo> }
            effect Bar where { bar: {} -> {} <Bar> }

            impl Foo for FooImpl where { foo = {} -> {} }
            impl Bar for BarImpl where { bar = {} -> {} }

            let use_both = {} -> do
              Foo.foo {}
              Bar.bar {}
        """.trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val (effects, fullType) = functionEffects(module, "use_both")
        assertTrue(effects.contains("Foo"), "Foo expected. Got: $effects. Type: $fullType")
        assertTrue(effects.contains("Bar"), "Bar expected. Got: $effects. Type: $fullType")
    }

    @Test
    fun `both rows open unify without loss`() {
        // Two-arg composition — both annotated with polymorphic tails —
        // combined effects should end up in the final row.
        val source = """
            effect Foo where { foo: {} -> {} <Foo> }
            effect Bar where { bar: {} -> {} <Bar> }

            impl Foo for FooImpl where { foo = {} -> {} }
            impl Bar for BarImpl where { bar = {} -> {} }

            let do_foo : {} -> {} <Foo, ...> = {} -> Foo.foo {}
            let do_bar : {} -> {} <Bar, ...> = {} -> Bar.bar {}

            let combined = {} -> do
              do_foo {}
              do_bar {}
        """.trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val (effects, fullType) = functionEffects(module, "combined")
        assertTrue(effects.contains("Foo"), "Should contain Foo. Got: $effects. Full type: $fullType")
        assertTrue(effects.contains("Bar"), "Should contain Bar. Got: $effects. Full type: $fullType")
    }
}
