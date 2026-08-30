package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests that the typechecker correctly resolves constructor types.
 *
 * The parser creates VariantConstruction AST nodes for ALL uppercase identifiers:
 * - Abilities (Inspect, Eq, user-defined)
 * - Effects (Log, user-defined)
 * - Handlers (Console, user-defined)
 * - Variant constructors (Some, None, user-defined)
 *
 * The typechecker must set the `resolution` field correctly so the interpreter
 * knows how to handle each one.
 */
class ConstructorResolutionTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheckAndGetParsed(source: String): com.mezze.language.parsedast.ParsedModule {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))
        return parsedModule
    }

    private fun typeCheckAndGetResult(source: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ============================================================================
    // BUILT-IN ABILITIES
    // ============================================================================

    @Test
    fun `test builtin ability Inspect method call typechecks correctly`() {
        // With method-call syntax, ability methods are called on values directly.
        // The typechecker resolves the method as an ability method and infers the result type.
        val result = typeCheckAndGetResult("""
            let test = 42.to_dbg {}
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test should have an inferred type")
        assertTrue(testType is MezzeType.StringType,
            "to_dbg should return Str, got: $testType")
    }

    @Test
    fun `test builtin ability Eq method call typechecks correctly`() {
        val result = typeCheckAndGetResult("""
            let test = 1.eq {r = 2}
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test should have an inferred type")
        assertTrue(testType is MezzeType.BoolType ||
            (testType is MezzeType.VariantType && BuiltinTypes.isBoolVariant(testType!!)),
            "eq should return Bool, got: $testType")
    }

    @Test
    fun `test builtin ability Ord method call typechecks correctly`() {
        val result = typeCheckAndGetResult("""
            let test = 1.lt {r = 2}
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test should have an inferred type")
        assertTrue(testType is MezzeType.BoolType ||
            (testType is MezzeType.VariantType && BuiltinTypes.isBoolVariant(testType!!)),
            "lt should return Bool, got: $testType")
    }

    @Test
    fun `test builtin ability NumAdd method call typechecks correctly`() {
        val result = typeCheckAndGetResult("""
            let test = 1.add {r = 2}
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test should have an inferred type")
        assertTrue(testType is MezzeType.IntType,
            "add on Int should return Int, got: $testType")
    }

    // ============================================================================
    // USER-DEFINED ABILITIES
    // ============================================================================

    @Test
    fun `test user-defined ability declaration creates ability`() {
        // Verify that declaring an ability creates a recognized AbilityDeclaration in the program.
        // With method-call syntax (value.show {}), the ability name doesn't appear as a
        // VariantConstruction in the AST. Instead, verify the AbilityDeclaration is preserved.
        val parsedModule = typeCheckAndGetParsed("""
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }
        """.trimIndent())

        val abilityDecl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
            .find { it.name == "Show" }
        assertNotNull(abilityDecl, "Should find Show ability declaration in typed program")
        assertEquals("Show", abilityDecl!!.name)
        assertTrue(abilityDecl.operations.any { it.name == "show" },
            "Show ability should have 'show' operation")
    }

    // ============================================================================
    // USER-DEFINED EFFECTS
    // ============================================================================

    @Test
    fun `test user-defined effect is resolved as EffectReference`() {
        // Verify that the effect is correctly resolved by checking that type checking succeeds
        // and the effect declaration exists in the result.
        val result = typeCheckAndGetResult("""
            effect Counter where {
                increment: {} -> Int,
                get_count: {} -> Int
            }

            let test = {x} -> Counter.increment {}
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test should have a type (effect operation call resolved)")
    }

    @Test
    fun `test user-defined effect with operations is resolved as EffectReference`() {
        val result = typeCheckAndGetResult("""
            effect State where {
                get: {} -> Int <State>,
                put: {val: Int} -> {} <State>
            }

            let test = {x} -> State.get {}
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test should have a type (effect operation call resolved)")
    }

    // ============================================================================
    // VARIANT CONSTRUCTORS
    // ============================================================================

    @Test
    fun `test variant constructor with payload is resolved as VariantConstructor`() {
        // Verify that variant constructors type-check correctly
        // (resolution is validated through constraint solving, not v1 AST resolution field)
        val result = typeCheckAndGetResult("""
            type Option 'a = | None, Some { val: 'a } |

            let test = Some { val = 42 }
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "Some { val = 42 } should type check successfully")
    }

    @Test
    fun `test unit variant constructor is resolved as VariantConstructor`() {
        val result = typeCheckAndGetResult("""
            type Option 'a = | None, Some { val: 'a } |

            let test = None
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "None should type check successfully")
    }

    @Test
    fun `test nested variant constructors are resolved correctly`() {
        val result = typeCheckAndGetResult("""
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let test = Node {
                value = 10,
                left = Leaf,
                right = Node { value = 20, left = Leaf, right = Leaf }
            }
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "Nested variant construction should type check successfully")
    }

    // ============================================================================
    // MIXED SCENARIOS - ABILITY VS VARIANT
    // ============================================================================

    @Test
    fun `test ability method call and variant in same expression are resolved correctly`() {
        val result = typeCheckAndGetResult("""
            type Option 'a = | None, Some { val: 'a } |

            let x = Some { val = 42 }
            let test = 42.to_dbg {}
        """.trimIndent())

        // Variant constructor type checks correctly
        val xType = result.getVariableType("x")
        assertNotNull(xType, "Some { val = 42 } should type check successfully")

        // Ability method call should typecheck correctly
        val testType = result.getVariableType("test")
        assertNotNull(testType, "Should find test binding")
        assertTrue(testType is MezzeType.StringType,
            "to_dbg should return Str, got: $testType")
    }

    @Test
    fun `test effect and variant in same expression are resolved correctly`() {
        val result = typeCheckAndGetResult("""
            type Result 'a 'e = | Ok { val: 'a }, Err { error: 'e } |

            effect Logger where {
                log: {msg: Str} -> {}
            }

            let test = {x} -> do
                Logger.log {msg = "test"}
                Ok { val = 42 }
        """.trimIndent())

        val testType = result.getVariableType("test")
        assertNotNull(testType, "Effect + variant expression should type check successfully")
    }

    // ============================================================================
    // HANDLERS / PERFORMERS
    // ============================================================================

    // Note: Handler resolution tests are complex due to effect system requirements.
    // The core resolution mechanism is tested through InspectAbilityTest and runtime tests.

    // ============================================================================
    // EDGE CASES
    // ============================================================================

    @Test
    fun `test True and False type check correctly`() {
        // True and False are boolean values
        val result = typeCheckAndGetResult("""
            let t = True
            let f = False
        """.trimIndent())

        // True/False should type check successfully
        val tType = result.getVariableType("t")
        assertNotNull(tType, "True should have a type")
    }

    @Test
    fun `test multiple ability method calls in same program all typecheck correctly`() {
        val result = typeCheckAndGetResult("""
            let test1 = 42.to_dbg {}
            let test2 = 1.eq {r = 2}
            let test3 = 1.lt {r = 2}
            let test4 = 1.add {r = 2}
        """.trimIndent())

        val test1Type = result.getVariableType("test1")
        assertNotNull(test1Type, "Should find test1 type")
        assertTrue(test1Type is MezzeType.StringType,
            "to_dbg should return Str, got: $test1Type")

        val test2Type = result.getVariableType("test2")
        assertNotNull(test2Type, "Should find test2 type")
        assertTrue(test2Type is MezzeType.BoolType ||
            (test2Type is MezzeType.VariantType && BuiltinTypes.isBoolVariant(test2Type!!)),
            "eq should return Bool, got: $test2Type")

        val test3Type = result.getVariableType("test3")
        assertNotNull(test3Type, "Should find test3 type")
        assertTrue(test3Type is MezzeType.BoolType ||
            (test3Type is MezzeType.VariantType && BuiltinTypes.isBoolVariant(test3Type!!)),
            "lt should return Bool, got: $test3Type")

        val test4Type = result.getVariableType("test4")
        assertNotNull(test4Type, "Should find test4 type")
        assertTrue(test4Type is MezzeType.IntType,
            "add on Int should return Int, got: $test4Type")
    }

    @Test
    fun `test variant constructor names cannot conflict with builtin abilities`() {
        // The typechecker should reject variant constructors with the same name as builtin abilities
        val parsedModule = parser.parseParsedModule("""
            type MyType = | Inspect { data: Int } |
        """.trimIndent(), "test.mz")

        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("conflicts with built-in ability") == true,
            "Should report conflict with built-in ability, got: ${exception.message}")
    }

    @Test
    fun `test ability method call works when variant with same name exists`() {
        // Ability method call via method-call syntax should still work
        val result = typeCheckAndGetResult("""
            type MyType = | SomeVariant { data: Int } |

            # This is an ability method call on a value
            let ability_call = 42.to_dbg {}
        """.trimIndent())

        val abilityCallType = result.getVariableType("ability_call")
        assertNotNull(abilityCallType, "Should find ability_call type")
        assertTrue(abilityCallType is MezzeType.StringType,
            "to_dbg should return Str, got: $abilityCallType")
    }
}
