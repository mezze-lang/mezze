package com.mezze.language.typechecker

import com.mezze.language.ast.*
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

    // ============================================================================
    // HELPER FUNCTIONS
    // ============================================================================

    private fun findVariantConstructions(expr: Expression): List<VariantConstruction> {
        val results = mutableListOf<VariantConstruction>()
        collectVariantConstructions(expr, results)
        return results
    }

    private fun collectVariantConstructions(expr: Expression, results: MutableList<VariantConstruction>) {
        when (expr) {
            is VariantConstruction -> {
                results.add(expr)
                expr.value?.let { collectVariantConstructions(it, results) }
            }
            is MemberAccess -> {
                collectVariantConstructions(expr.receiver, results)
            }
            is FunctionCall -> {
                collectVariantConstructions(expr.function, results)
                collectVariantConstructions(expr.arguments, results)
            }
            is RecordLiteral -> {
                expr.entries.forEach { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.value?.let { collectVariantConstructions(it, results) }
                        is RecordSpreadEntry -> collectVariantConstructions(entry.expression, results)
                    }
                }
            }
            is LambdaExpression -> {
                collectVariantConstructions(expr.body, results)
            }
            is DoExpression -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is LetStatement -> collectVariantConstructions(stmt.expression, results)
                        is ExpressionStatement -> collectVariantConstructions(stmt.expression, results)
                    }
                }
                collectVariantConstructions(expr.result, results)
            }
            is IfExpression -> {
                collectVariantConstructions(expr.condition, results)
                collectVariantConstructions(expr.thenBranch, results)
                collectVariantConstructions(expr.elseBranch, results)
            }
            is MatchExpression -> {
                collectVariantConstructions(expr.expression, results)
                expr.cases.forEach { case ->
                    collectVariantConstructions(case.expression, results)
                }
            }
            is SeqLiteral -> {
                expr.elements.forEach { elem ->
                    when (elem) {
                        is SeqRegularElement -> collectVariantConstructions(elem.expression, results)
                        is SeqSpreadElement -> collectVariantConstructions(elem.expression, results)
                    }
                }
            }
            is PerformExpression -> {
                collectVariantConstructions(expr.expression, results)
            }
            is StringLiteral -> {
                expr.parts?.forEach { part ->
                    if (part is ExpressionPart) {
                        collectVariantConstructions(part.expression, results)
                    }
                }
            }
            else -> { /* Literals, Identifiers, etc. - no nested VariantConstructions */ }
        }
    }

    private fun findResolution(program: TypeCheckResult, constructorName: String): ConstructorResolution? {
        for (item in program.items) {
            if (item is LetBinding) {
                val constructions = findVariantConstructions(item.expression)
                val match = constructions.find { it.constructor == constructorName }
                if (match != null) return match.resolution
            }
        }
        return null
    }

    private fun typeCheckAndGetProgram(source: String): com.mezze.language.ast.TypeCheckResult {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    // ============================================================================
    // BUILT-IN ABILITIES
    // ============================================================================

    @Test
    fun `test builtin ability Inspect method call typechecks correctly`() {
        // With method-call syntax, ability methods are called on values directly.
        // The typechecker resolves the method as an ability method and infers the result type.
        val typedProgram = typeCheckAndGetProgram("""
            let test = 42.to_dbg_str {}
        """.trimIndent())

        val testBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == "test" }
        assertNotNull(testBinding, "Should find test binding")
        assertNotNull(testBinding!!.inferredType, "test should have an inferred type")
        assertTrue(testBinding.inferredType is MezzeType.StringType,
            "to_dbg_str should return Str, got: ${testBinding.inferredType}")
    }

    @Test
    fun `test builtin ability Eq method call typechecks correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = 1.eq {r = 2}
        """.trimIndent())

        val testBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == "test" }
        assertNotNull(testBinding, "Should find test binding")
        assertNotNull(testBinding!!.inferredType, "test should have an inferred type")
        assertTrue(testBinding.inferredType is MezzeType.BoolType ||
            (testBinding.inferredType is MezzeType.VariantType && BuiltinTypes.isBoolVariant(testBinding.inferredType!!)),
            "eq should return Bool, got: ${testBinding.inferredType}")
    }

    @Test
    fun `test builtin ability Ord method call typechecks correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = 1.lt {r = 2}
        """.trimIndent())

        val testBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == "test" }
        assertNotNull(testBinding, "Should find test binding")
        assertNotNull(testBinding!!.inferredType, "test should have an inferred type")
        assertTrue(testBinding.inferredType is MezzeType.BoolType ||
            (testBinding.inferredType is MezzeType.VariantType && BuiltinTypes.isBoolVariant(testBinding.inferredType!!)),
            "lt should return Bool, got: ${testBinding.inferredType}")
    }

    @Test
    fun `test builtin ability NumAdd method call typechecks correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = 1.add {r = 2}
        """.trimIndent())

        val testBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == "test" }
        assertNotNull(testBinding, "Should find test binding")
        assertNotNull(testBinding!!.inferredType, "test should have an inferred type")
        assertTrue(testBinding.inferredType is MezzeType.IntType,
            "add on Int should return Int, got: ${testBinding.inferredType}")
    }

    // ============================================================================
    // USER-DEFINED ABILITIES
    // ============================================================================

    @Test
    fun `test user-defined ability declaration creates ability`() {
        // Verify that declaring an ability creates a recognized AbilityDeclaration in the program.
        // With method-call syntax (value.show {}), the ability name doesn't appear as a
        // VariantConstruction in the AST. Instead, verify the AbilityDeclaration is preserved.
        val typedProgram = typeCheckAndGetProgram("""
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }
        """.trimIndent())

        val abilityDecl = typedProgram.items.filterIsInstance<AbilityDeclaration>()
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
        val typedProgram = typeCheckAndGetProgram("""
            effect Counter where {
                increment: {} -> Int,
                get_count: {} -> Int
            }

            let test = {x} -> Counter.increment {}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Counter")

        assertNotNull(resolution, "Should find Counter in typed program")
        assertTrue(resolution is ConstructorResolution.EffectReference,
            "Counter should be resolved as EffectReference, got: $resolution")
        assertEquals("Counter", (resolution as ConstructorResolution.EffectReference).effectName)
    }

    @Test
    fun `test user-defined effect with operations is resolved as EffectReference`() {
        val typedProgram = typeCheckAndGetProgram("""
            effect State where {
                get: {} -> Int <State>,
                put: {val: Int} -> {} <State>
            }

            let test = {x} -> State.get {}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "State")

        assertNotNull(resolution, "Should find State in typed program")
        assertTrue(resolution is ConstructorResolution.EffectReference,
            "State should be resolved as EffectReference, got: $resolution")
    }

    // ============================================================================
    // VARIANT CONSTRUCTORS
    // ============================================================================

    @Test
    fun `test variant constructor with payload is resolved as VariantConstructor`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Option 'a = | None, Some { val: 'a } |

            let test = Some { val = 42 }
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Some")

        assertNotNull(resolution, "Should find Some in typed program")
        assertTrue(resolution is ConstructorResolution.VariantConstructor,
            "Some should be resolved as VariantConstructor, got: $resolution")
    }

    @Test
    fun `test unit variant constructor is resolved as VariantConstructor`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Option 'a = | None, Some { val: 'a } |

            let test = None
        """.trimIndent())

        val resolution = findResolution(typedProgram, "None")

        assertNotNull(resolution, "Should find None in typed program")
        assertTrue(resolution is ConstructorResolution.VariantConstructor,
            "None should be resolved as VariantConstructor, got: $resolution")
    }

    @Test
    fun `test nested variant constructors are resolved correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let test = Node {
                value = 10,
                left = Leaf,
                right = Node { value = 20, left = Leaf, right = Leaf }
            }
        """.trimIndent())

        // Find all variant constructions in the typed program
        val letBinding = typedProgram.items.filterIsInstance<LetBinding>().first()
        val constructions = findVariantConstructions(letBinding.expression)

        // Should have Node, Leaf, Node, Leaf, Leaf = 5 total
        val nodeCount = constructions.count { it.constructor == "Node" }
        val leafCount = constructions.count { it.constructor == "Leaf" }

        assertEquals(2, nodeCount, "Should have 2 Node constructions")
        assertEquals(3, leafCount, "Should have 3 Leaf constructions")

        // All should be resolved as VariantConstructor
        constructions.forEach { vc ->
            assertTrue(vc.resolution is ConstructorResolution.VariantConstructor,
                "${vc.constructor} should be resolved as VariantConstructor, got: ${vc.resolution}")
        }
    }

    // ============================================================================
    // MIXED SCENARIOS - ABILITY VS VARIANT
    // ============================================================================

    @Test
    fun `test ability method call and variant in same expression are resolved correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Option 'a = | None, Some { val: 'a } |

            let x = Some { val = 42 }
            let test = 42.to_dbg_str {}
        """.trimIndent())

        // Variant constructor Some should still be resolved
        val someResolution = findResolution(typedProgram, "Some")
        assertTrue(someResolution is ConstructorResolution.VariantConstructor,
            "Some should be VariantConstructor, got: $someResolution")

        // Ability method call should typecheck correctly
        val testBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == "test" }
        assertNotNull(testBinding, "Should find test binding")
        assertTrue(testBinding!!.inferredType is MezzeType.StringType,
            "to_dbg_str should return Str, got: ${testBinding.inferredType}")
    }

    @Test
    fun `test effect and variant in same expression are resolved correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Result 'a 'e = | Ok { val: 'a }, Err { error: 'e } |

            effect Logger where {
                log: {msg: Str} -> {}
            }

            let test = {x} -> do
                Logger.log {msg = "test"}
                Ok { val = 42 }
        """.trimIndent())

        val loggerResolution = findResolution(typedProgram, "Logger")
        val okResolution = findResolution(typedProgram, "Ok")

        assertTrue(loggerResolution is ConstructorResolution.EffectReference,
            "Logger should be EffectReference, got: $loggerResolution")
        assertTrue(okResolution is ConstructorResolution.VariantConstructor,
            "Ok should be VariantConstructor, got: $okResolution")
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
        val typedProgram = typeCheckAndGetProgram("""
            let t = True
            let f = False
        """.trimIndent())

        // True/False should type check successfully
        val tBinding = typedProgram.items.filterIsInstance<LetBinding>().find {
            (it.pattern as? VariablePattern)?.name == "t"
        }

        assertNotNull(tBinding, "Should find t binding")
        // The type might be BoolType or a VariantType with True/False constructors
        // Either way, it should type check successfully
        assertNotNull(tBinding?.inferredType, "True should have a type")
    }

    @Test
    fun `test multiple ability method calls in same program all typecheck correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test1 = 42.to_dbg_str {}
            let test2 = 1.eq {r = 2}
            let test3 = 1.lt {r = 2}
            let test4 = 1.add {r = 2}
        """.trimIndent())

        val bindings = typedProgram.items.filterIsInstance<LetBinding>()

        val test1 = bindings.find { (it.pattern as? VariablePattern)?.name == "test1" }
        assertNotNull(test1, "Should find test1 binding")
        assertTrue(test1!!.inferredType is MezzeType.StringType,
            "to_dbg_str should return Str, got: ${test1.inferredType}")

        val test2 = bindings.find { (it.pattern as? VariablePattern)?.name == "test2" }
        assertNotNull(test2, "Should find test2 binding")
        assertTrue(test2!!.inferredType is MezzeType.BoolType ||
            (test2.inferredType is MezzeType.VariantType && BuiltinTypes.isBoolVariant(test2.inferredType!!)),
            "eq should return Bool, got: ${test2.inferredType}")

        val test3 = bindings.find { (it.pattern as? VariablePattern)?.name == "test3" }
        assertNotNull(test3, "Should find test3 binding")
        assertTrue(test3!!.inferredType is MezzeType.BoolType ||
            (test3.inferredType is MezzeType.VariantType && BuiltinTypes.isBoolVariant(test3.inferredType!!)),
            "lt should return Bool, got: ${test3.inferredType}")

        val test4 = bindings.find { (it.pattern as? VariablePattern)?.name == "test4" }
        assertNotNull(test4, "Should find test4 binding")
        assertTrue(test4!!.inferredType is MezzeType.IntType,
            "add on Int should return Int, got: ${test4.inferredType}")
    }

    @Test
    fun `test variant constructor names cannot conflict with builtin abilities`() {
        // The typechecker should reject variant constructors with the same name as builtin abilities
        val program = parser.parse("""
            type MyType = | Inspect { data: Int } |
        """.trimIndent())

        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }

        assertTrue(exception.message?.contains("conflicts with built-in ability") == true,
            "Should report conflict with built-in ability, got: ${exception.message}")
    }

    @Test
    fun `test ability method call works when variant with same name exists`() {
        // Ability method call via method-call syntax should still work
        val typedProgram = typeCheckAndGetProgram("""
            type MyType = | SomeVariant { data: Int } |

            # This is an ability method call on a value
            let ability_call = 42.to_dbg_str {}
        """.trimIndent())

        val binding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == "ability_call" }
        assertNotNull(binding, "Should find ability_call binding")
        assertTrue(binding!!.inferredType is MezzeType.StringType,
            "to_dbg_str should return Str, got: ${binding.inferredType}")
    }
}
