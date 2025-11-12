package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityDependencyParserTest {

    private val parser = FunkParser()

    @Test
    fun testAbilityWithSingleDependency() {
        val source = """
            ability Ord 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> String
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Ord", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(1, ability.dependencies.size)

        val dependency = ability.dependencies[0]
        assertEquals("Eq", dependency.abilityName)
        assertEquals(1, dependency.typeArgs.size)
        assertTrue(dependency.typeArgs[0] is IdentifierTypeAnnotation)
        assertEquals("'a", (dependency.typeArgs[0] as IdentifierTypeAnnotation).name)

        assertEquals(1, ability.operations.size)
        assertEquals("compare", ability.operations[0].name)
    }

    @Test
    fun testAbilityWithMultipleDependencies() {
        val source = """
            ability Serializable 'a with Show 'a, Eq 'a where {
                serialize : {val: 'a} -> String
                deserialize : {str: String} -> 'a
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Serializable", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(2, ability.dependencies.size)

        val showDep = ability.dependencies[0]
        assertEquals("Show", showDep.abilityName)
        assertEquals(1, showDep.typeArgs.size)
        assertEquals("'a", (showDep.typeArgs[0] as IdentifierTypeAnnotation).name)

        val eqDep = ability.dependencies[1]
        assertEquals("Eq", eqDep.abilityName)
        assertEquals(1, eqDep.typeArgs.size)
        assertEquals("'a", (eqDep.typeArgs[0] as IdentifierTypeAnnotation).name)

        assertEquals(2, ability.operations.size)
    }

    @Test
    fun testAbilityWithMultiParameterDependency() {
        val source = """
            ability ComplexStore 'a 'b with KVStore 'a 'b, Show 'a where {
                complexStore : {key: 'a, value: 'b, metadata: String} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("ComplexStore", ability.name)
        assertEquals(listOf("'a", "'b"), ability.typeParams)
        assertEquals(2, ability.dependencies.size)

        val kvStoreDep = ability.dependencies[0]
        assertEquals("KVStore", kvStoreDep.abilityName)
        assertEquals(2, kvStoreDep.typeArgs.size)
        assertEquals("'a", (kvStoreDep.typeArgs[0] as IdentifierTypeAnnotation).name)
        assertEquals("'b", (kvStoreDep.typeArgs[1] as IdentifierTypeAnnotation).name)

        val showDep = ability.dependencies[1]
        assertEquals("Show", showDep.abilityName)
        assertEquals(1, showDep.typeArgs.size)
        assertEquals("'a", (showDep.typeArgs[0] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testAbilityWithConcreteDependency() {
        val source = """
            ability Logger 'a with Show String where {
                log : {val: 'a, message: String} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Logger", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(1, ability.dependencies.size)

        val dependency = ability.dependencies[0]
        assertEquals("Show", dependency.abilityName)
        assertEquals(1, dependency.typeArgs.size)
        assertTrue(dependency.typeArgs[0] is IdentifierTypeAnnotation)
        assertEquals("String", (dependency.typeArgs[0] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testAbilityWithoutDependencies() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Show", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(0, ability.dependencies.size)
        assertEquals(1, ability.operations.size)
    }

    @org.junit.jupiter.api.Disabled("Type application in dependencies - needs parser optimization")
    @Test
    fun testAbilityWithTypeApplicationDependency() {
        val source = """
            ability Container 'a with Show (Maybe 'a) where {
                wrap : {val: 'a} -> (Maybe 'a)
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Container", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(1, ability.dependencies.size)

        val dependency = ability.dependencies[0]
        assertEquals("Show", dependency.abilityName)
        assertEquals(1, dependency.typeArgs.size)

        // The type argument should be a type application (Maybe 'a)
        assertTrue(dependency.typeArgs[0] is TypeApplicationAnnotation)
        val typeApp = dependency.typeArgs[0] as TypeApplicationAnnotation
        assertEquals("Maybe", typeApp.constructor)
        assertEquals(1, typeApp.args.size)
        assertEquals("'a", (typeApp.args[0] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testMultipleAbilitiesWithDependencies() {
        val source = """
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Boolean
            }

            ability Ord 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> String
            }

            ability Bounded 'a with Ord 'a where {
                minBound : {} -> 'a
                maxBound : {} -> 'a
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(3, program.items.size)

        val eq = program.items[0] as AbilityDeclaration
        assertEquals("Eq", eq.name)
        assertEquals(0, eq.dependencies.size)

        val ord = program.items[1] as AbilityDeclaration
        assertEquals("Ord", ord.name)
        assertEquals(1, ord.dependencies.size)
        assertEquals("Eq", ord.dependencies[0].abilityName)

        val bounded = program.items[2] as AbilityDeclaration
        assertEquals("Bounded", bounded.name)
        assertEquals(1, bounded.dependencies.size)
        assertEquals("Ord", bounded.dependencies[0].abilityName)
    }
}
