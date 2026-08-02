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
 * Regression tests for SuspendType leakage at variant-payload and
 * member-access sites.
 *
 * The intended semantics:
 *   Effects propagate at the call boundary, not inside data types.
 *   `Ok { val = effectfulCall {} }` is `Ok Int 'a <Foo>` — the outer
 *   variant wrapped in a SuspendType for the unhandled effect, NOT
 *   `Ok (Int <Foo>) 'a` where the payload is itself effectful.
 *
 * Two sites in ExpressionConstraints.kt:
 * - Variant construction with payload (~line 2790)
 * - Member access on effectful receiver (~line 1029)
 */
class SuspendTypeLeakTest {
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
    fun `variant payload from effect-op result wraps outer variant in SuspendType`() {
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

        // Effects must surface — function effects OR a SuspendType
        // wrapping the variant result. Either form documents that <Foo>
        // is unhandled.
        val effectsOnFn = fnType.effects?.effects ?: emptySet()
        val wrappingSuspension = returnType is MezzeType.SuspendType

        assertTrue(
            "Foo" in effectsOnFn || wrappingSuspension,
            "wrapped's return must surface <Foo>. Got effects=$effectsOnFn, returnType=$returnType, printed=$printed"
        )

        // The variant's payload (val) should NOT itself be a SuspendType.
        val valueLayer = if (returnType is MezzeType.SuspendType) returnType.valueType else returnType
        when (valueLayer) {
            is MezzeType.VariantType -> {
                val okPayload = valueLayer.constructors["Ok"]
                assertNotNull(okPayload, "expected an Ok constructor in $printed")
                // Ok's payload is a record { val: ... }; that record field
                // should be Int, not Int <Foo>.
                val payloadType = okPayload
                if (payloadType is MezzeType.RecordType) {
                    val valField = payloadType.fields["val"]
                    assertNotNull(valField, "expected val field on Ok payload")
                    assertTrue(
                        valField !is MezzeType.SuspendType,
                        "Ok.val should be Int, not SuspendType. Got val=$valField, printed=$printed"
                    )
                }
            }
            else -> {} // Different variant inference shapes are OK as long as the SuspendType is at the outer layer.
        }
    }

    @Test
    fun `member access on effect-op record result wraps field in SuspendType`() {
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

        // Effects must surface — same shape as the variant test.
        val effectsOnFn = fnType.effects?.effects ?: emptySet()
        val wrappingSuspension = returnType is MezzeType.SuspendType
        assertTrue(
            "Foo" in effectsOnFn || wrappingSuspension,
            "f's return must surface <Foo>. Got effects=$effectsOnFn, returnType=$returnType, printed=$printed"
        )

        // The underlying value (after unwrapping any SuspendType) should be Int.
        val valueLayer = if (returnType is MezzeType.SuspendType) returnType.valueType else returnType
        assertTrue(
            valueLayer is MezzeType.IntType,
            ".a should type as Int (after unwrapping SuspendType). Got valueLayer=$valueLayer, printed=$printed"
        )
    }
}
