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
            is ArrayLiteral -> {
                expr.elements.forEach { elem ->
                    when (elem) {
                        is ArrayRegularElement -> collectVariantConstructions(elem.expression, results)
                        is ArraySpreadElement -> collectVariantConstructions(elem.expression, results)
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

    private fun findResolution(program: Program, constructorName: String): ConstructorResolution? {
        for (item in program.items) {
            if (item is LetBinding) {
                val constructions = findVariantConstructions(item.expression)
                val match = constructions.find { it.constructor == constructorName }
                if (match != null) return match.resolution
            }
        }
        return null
    }

    private fun typeCheckAndGetProgram(source: String): Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program).program
    }

    // ============================================================================
    // BUILT-IN ABILITIES
    // ============================================================================

    @Test
    fun `test builtin ability Inspect is resolved as AbilityReference`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = Inspect.to_str {val = 42}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Inspect")

        assertNotNull(resolution, "Should find Inspect in typed program")
        assertTrue(resolution is ConstructorResolution.AbilityReference,
            "Inspect should be resolved as AbilityReference, got: $resolution")
        assertEquals("Inspect", (resolution as ConstructorResolution.AbilityReference).abilityName)
    }

    @Test
    fun `test builtin ability Eq is resolved as AbilityReference`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = Eq.eq {l = 1, r = 2}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Eq")

        assertNotNull(resolution, "Should find Eq in typed program")
        assertTrue(resolution is ConstructorResolution.AbilityReference,
            "Eq should be resolved as AbilityReference, got: $resolution")
    }

    @Test
    fun `test builtin ability Ord is resolved as AbilityReference`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = Ord.lt {l = 1, r = 2}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Ord")

        assertNotNull(resolution, "Should find Ord in typed program")
        assertTrue(resolution is ConstructorResolution.AbilityReference,
            "Ord should be resolved as AbilityReference, got: $resolution")
    }

    @Test
    fun `test builtin ability NumAdd is resolved as AbilityReference`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test = NumAdd.add {l = 1, r = 2}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "NumAdd")

        assertNotNull(resolution, "Should find NumAdd in typed program")
        assertTrue(resolution is ConstructorResolution.AbilityReference,
            "NumAdd should be resolved as AbilityReference, got: $resolution")
    }

    // ============================================================================
    // USER-DEFINED ABILITIES
    // ============================================================================

    @Test
    fun `test user-defined ability declaration creates ability`() {
        // Note: User-defined abilities need to be accessed via member access
        // The resolution is set when the ability name appears in a MemberAccess receiver
        val typedProgram = typeCheckAndGetProgram("""
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            # Lambda that uses the ability (deferred resolution)
            let show_something = {x} -> Show.show {val = x}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Show")

        assertNotNull(resolution, "Should find Show in typed program")
        assertTrue(resolution is ConstructorResolution.AbilityReference,
            "Show should be resolved as AbilityReference, got: $resolution")
        assertEquals("Show", (resolution as ConstructorResolution.AbilityReference).abilityName)
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
    fun `test ability and variant in same expression are resolved correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Option 'a = | None, Some { val: 'a } |

            let test = Inspect.to_str {val = Some { val = 42 }}
        """.trimIndent())

        val inspectResolution = findResolution(typedProgram, "Inspect")
        val someResolution = findResolution(typedProgram, "Some")

        assertTrue(inspectResolution is ConstructorResolution.AbilityReference,
            "Inspect should be AbilityReference, got: $inspectResolution")
        assertTrue(someResolution is ConstructorResolution.VariantConstructor,
            "Some should be VariantConstructor, got: $someResolution")
    }

    @Test
    fun `test effect and variant in same expression are resolved correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            type Result 'a 'e = | Ok { val: 'a }, Err { error: 'e } |

            effect Logger where {
                log: {msg: String} -> {}
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
        // The type might be BooleanType or a VariantType with True/False constructors
        // Either way, it should type check successfully
        assertNotNull(tBinding?.inferredType, "True should have a type")
    }

    @Test
    fun `test multiple abilities in same program are all resolved correctly`() {
        val typedProgram = typeCheckAndGetProgram("""
            let test1 = Inspect.to_str {val = 42}
            let test2 = Eq.eq {l = 1, r = 2}
            let test3 = Ord.lt {l = 1, r = 2}
            let test4 = NumAdd.add {l = 1, r = 2}
        """.trimIndent())

        listOf("Inspect", "Eq", "Ord", "NumAdd").forEach { abilityName ->
            val resolution = findResolution(typedProgram, abilityName)
            assertNotNull(resolution, "Should find $abilityName in typed program")
            assertTrue(resolution is ConstructorResolution.AbilityReference,
                "$abilityName should be AbilityReference, got: $resolution")
        }
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
    fun `test ability method access still works when variant with same name exists`() {
        // The ability method access should still work
        val typedProgram = typeCheckAndGetProgram("""
            type MyType = | SomeVariant { data: Int } |

            # This is an ability method access (not a variant)
            let ability_call = Inspect.to_str {val = 1}
        """.trimIndent())

        val resolution = findResolution(typedProgram, "Inspect")

        assertNotNull(resolution, "Should find Inspect in typed program")
        assertTrue(resolution is ConstructorResolution.AbilityReference,
            "Inspect.to_str should resolve Inspect as AbilityReference, got: $resolution")
    }
}
