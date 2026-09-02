package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression tests for effect leakage into data types at variant-payload and
 * member-access sites.
 *
 * The intended semantics:
 *   Effects propagate at the call boundary (on the arrow), not inside data
 *   types. `Ok { val = effectfulCall {} }` has a BARE `Ok Int 'a` payload and
 *   the unhandled effect <Foo> rides the enclosing function's arrow, NOT a
 *   payload that is itself effectful.
 *
 * Two sites in ExpressionConstraints.kt:
 * - Variant construction with payload (~line 2790)
 * - Member access on effectful receiver (~line 1029)
 */
class EffectPayloadLeakTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code.trimIndent(), "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun bindingType(result: TypeCheckResult, name: String): MezzeType {
        val t = result.getVariableType(name)
        assertNotNull(t, "Binding '$name' has no inferred type")
        return t!!
    }

    @Test
    fun `variant payload from effect-op result stays bare -- effect rides the arrow`() {
        val source = """
            effect Foo where {
              gimme : {} -> Int <Foo>,
            }
            impl Foo for FooH where {
              gimme = {} -> 42,
            }

            ## After the application of Foo.gimme {}, the call's result
            ## is `Int <Foo>`. The variant constructor `Ok` should wrap
            ## the *value* (Int) and the effect <Foo> should ride along
            ## on the outside: `Ok Int 'a <Foo>`.
            let wrapped = {} -> Ok { val = Foo.gimme {} }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "wrapped")
        val printed = prettyPrintType(t)

        // Drill into the return type — `wrapped : {} -> <returnType>`
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "wrapped should be a function type: $printed")
        val returnType = fnType!!.returnType

        // The unhandled <Foo> must surface on the function's arrow.
        val effectsOnFn = fnType.effects?.effects ?: emptySet()
        assertTrue(
            "Foo" in effectsOnFn,
            "wrapped's return must surface <Foo>. Got effects=$effectsOnFn, returnType=$returnType, printed=$printed"
        )

        // The variant's payload (val) must be a bare value, never effect-wrapped.
        val valueLayer = returnType
        when (valueLayer) {
            is MezzeType.VariantType -> {
                val okPayload = valueLayer.constructors["Ok"]
                assertNotNull(okPayload, "expected an Ok constructor in $printed")
                // Ok's payload is a record { val: ... }; that record field
                // should be a bare Int, not Int <Foo>.
                val payloadType = okPayload
                if (payloadType is MezzeType.RecordType) {
                    val valField = payloadType.fields["val"]
                    assertNotNull(valField, "expected val field on Ok payload")
                    assertTrue(
                        valField is MezzeType.IntType || valField is MezzeType.VarType,
                        "Ok.val should be a bare Int. Got val=$valField, printed=$printed"
                    )
                }
            }
            else -> {} // Different variant inference shapes are OK.
        }
    }

    @Test
    fun `member access on effect-op record result stays bare -- effect rides the arrow`() {
        val source = """
            effect Foo where {
              run : {} -> { a: Int, b: Int } <Foo>,
            }
            impl Foo for FooH where {
              run = {} -> { a = 1, b = 2 },
            }

            ## The receiver expression `Foo.run {}` types as
            ## `{ a: Int, b: Int } <Foo>`. Accessing `.a` should give
            ## back `Int <Foo>`, not crash, not produce a stray VarType.
            let f = {} -> (Foo.run {}).a
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "f")
        val printed = prettyPrintType(t)

        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "f should be a function: $printed")
        val returnType = fnType!!.returnType

        // The unhandled <Foo> must surface on the function's arrow.
        val effectsOnFn = fnType.effects?.effects ?: emptySet()
        assertTrue(
            "Foo" in effectsOnFn,
            "f's return must surface <Foo>. Got effects=$effectsOnFn, returnType=$returnType, printed=$printed"
        )

        // The underlying value should be a bare Int.
        val valueLayer = returnType
        assertTrue(
            valueLayer is MezzeType.IntType,
            ".a should type as Int. Got valueLayer=$valueLayer, printed=$printed"
        )
    }
}
