package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import kotlin.test.assertFailsWith
import kotlin.test.assertContains

/**
 * Tests for ability constraint validation.
 *
 * Key rule: Ability-level constraints are NOT allowed.
 * All constraints must be on individual methods (method-level constraints).
 *
 * OLD (rejected): ability Ord for 'a with Eq 'a where { compare: ... }
 * NEW (accepted): ability Ord for 'a where { compare: {...} with Eq 'a -> ... }
 */
class AbilityDependencyTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    init {
        // Enable strict validation for typechecker tests
        System.setProperty("mezze.typecheck.strict", "true")
    }

    // ========================================================================
    // TESTS: Ability-level constraints are rejected
    // ========================================================================

    @Test
    fun `ability-level constraint is rejected with helpful error`() {
        val source = """
            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        assertContains(error.message!!, "ability-level constraints")
        assertContains(error.message!!, "method-level constraints")
    }

    @Test
    fun `multiple ability-level constraints are rejected`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }

            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Serializable for 'a with Show 'a, Eq 'a where {
                serialize : {val: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        assertContains(error.message!!, "ability-level constraints")
    }

    @Test
    fun `circular ability-level constraints are rejected`() {
        val source = """
            ability A for 'a with B 'a where {
                opA : {val: 'a} -> Str
            }

            ability B for 'a with A 'a where {
                opB : {val: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)

        // Both abilities have ability-level constraints, so the first one should fail
        assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `self-referential ability-level constraint is rejected`() {
        val source = """
            ability SelfRef for 'a with SelfRef 'a where {
                op : {val: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)

        assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    // ========================================================================
    // TESTS: Method-level constraints work correctly
    // ========================================================================

    @Test
    fun `method-level constraint is accepted`() {
        val source = """
            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare : {left: 'a, right: 'a} with Eq 'a -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertEquals(2, typedProgram.items.size)

        val eq = typedProgram.items[0] as AbilityDeclaration
        assertNotNull(eq.inferredType)

        val ord = typedProgram.items[1] as AbilityDeclaration
        assertNotNull(ord.inferredType)
        // No ability-level dependencies
        assertEquals(0, ord.dependencies.size)
        // But method has constraint
        assertEquals(1, ord.operations[0].abilityConstraints.size)
        assertEquals("Eq", ord.operations[0].abilityConstraints[0].abilityName)
    }

    @Test
    fun `multiple method-level constraints are accepted`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }

            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Serializable for 'a where {
                serialize : {val: 'a} with Show 'a, Eq 'a -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertEquals(3, typedProgram.items.size)

        val serializable = typedProgram.items[2] as AbilityDeclaration
        assertNotNull(serializable.inferredType)
        assertEquals(0, serializable.dependencies.size)
        assertEquals(2, serializable.operations[0].abilityConstraints.size)
    }

    @Test
    fun `ability without constraints works`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertEquals(1, typedProgram.items.size)

        val show = typedProgram.items[0] as AbilityDeclaration
        assertNotNull(show.inferredType)
        assertEquals(0, show.dependencies.size)
        assertEquals(0, show.operations[0].abilityConstraints.size)
    }

    @Test
    fun `method-level constraint on different type param works`() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            impl Show for Int where {
                show = {} -> "Int"
            }

            ability Printer for 'a where {
                print : {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            let test = {} -> "Value".print {item = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Should type check without errors
        assertTrue(typedProgram.items.isNotEmpty())
    }

    @Test
    fun `method-level constraint fails when not satisfied`() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            impl Show for Int where {
                show = {} -> "Int"
            }

            ability Printer for 'a where {
                print : {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            # Float does NOT implement Show - should fail
            let test = {} -> "Value".print {item = 3.14}
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        assertContains(error.message!!, "Show")
        assertContains(error.message!!, "Float")
    }

    @Test
    fun `impl with method-level constraints works`() {
        val source = """
            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare : {left: 'a, right: 'a} with Eq 'a -> Str
            }

            impl Eq for Int where {
                equals = {left, right} -> True
            }

            impl Ord for Int where {
                compare = {left, right} -> "equal"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertEquals(4, typedProgram.items.size)

        // All items should type check successfully
        typedProgram.items.forEach { item ->
            when (item) {
                is AbilityDeclaration -> assertNotNull(item.inferredType)
                is ImplAbilityDeclaration -> assertNotNull(item.inferredType)
                else -> {}
            }
        }
    }

    @Test
    fun `different methods can have different constraints`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }

            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Collection for 'c where {
                length : {col: 'c} -> Int,
                contains : {col: 'c, item: 'a} with Eq 'a -> Bool,
                show_all : {col: 'c, items: 'a} with Show 'a -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val collection = typedProgram.items[2] as AbilityDeclaration
        assertNotNull(collection.inferredType)

        // length has no constraints
        assertEquals(0, collection.operations[0].abilityConstraints.size)
        // contains has Eq constraint
        assertEquals(1, collection.operations[1].abilityConstraints.size)
        assertEquals("Eq", collection.operations[1].abilityConstraints[0].abilityName)
        // show_all has Show constraint
        assertEquals(1, collection.operations[2].abilityConstraints.size)
        assertEquals("Show", collection.operations[2].abilityConstraints[0].abilityName)
    }
}
