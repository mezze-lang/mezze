package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import kotlin.test.assertFailsWith

class AbilityDependencyTypeCheckerTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }
    
    init {
        // Enable strict validation for typechecker tests
        System.setProperty("funk.typecheck.strict", "true")
    }

    @Test
    fun testSimpleAbilityDependency() {
        val source = """
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }
            
            ability Ord 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val eq = typedProgram.items[0] as AbilityDeclaration
        assertNotNull(eq.inferredType)
        assertTrue(eq.inferredType is FunkType.RecordType)
        
        val ord = typedProgram.items[1] as AbilityDeclaration
        assertNotNull(ord.inferredType)
        assertTrue(ord.inferredType is FunkType.RecordType)
        assertEquals(1, ord.dependencies.size)
        assertEquals("Eq", ord.dependencies[0].abilityName)
    }

    @Test
    fun testMultipleDependencies() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }
            
            ability Serializable 'a with Show 'a, Eq 'a where {
                serialize : {val: 'a} -> String
                deserialize : {str: String} -> 'a
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(3, typedProgram.items.size)
        
        val serializable = typedProgram.items[2] as AbilityDeclaration
        assertNotNull(serializable.inferredType)
        assertEquals(2, serializable.dependencies.size)
        
        val deps = serializable.dependencies.map { it.abilityName }.toSet()
        assertTrue(deps.contains("Show"))
        assertTrue(deps.contains("Eq"))
    }

    @Test
    fun testTransitiveDependencies() {
        val source = """
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Bool
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
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(3, typedProgram.items.size)
        
        val eq = typedProgram.items[0] as AbilityDeclaration
        val ord = typedProgram.items[1] as AbilityDeclaration
        val bounded = typedProgram.items[2] as AbilityDeclaration
        
        // All should type check successfully
        assertNotNull(eq.inferredType)
        assertNotNull(ord.inferredType)
        assertNotNull(bounded.inferredType)
        
        // Check dependency structure
        assertEquals(0, eq.dependencies.size)
        assertEquals(1, ord.dependencies.size)
        assertEquals("Eq", ord.dependencies[0].abilityName)
        assertEquals(1, bounded.dependencies.size)
        assertEquals("Ord", bounded.dependencies[0].abilityName)
    }

    @Test
    fun testCircularDependencyDetection() {
        val source = """
            ability A 'a with B 'a where {
                opA : {val: 'a} -> String
            }
            
            ability B 'a with A 'a where {
                opB : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // The type checker should detect and reject circular dependencies
        assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun testSelfDependencyDetection() {
        val source = """
            ability SelfRef 'a with SelfRef 'a where {
                op : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // The type checker should detect and reject self-dependencies
        assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun testMultiParameterAbilityDependencies() {
        val source = """
            ability KVStore 'a 'b where {
                store : {key: 'a, value: 'b} -> {}
                retrieve : {key: 'a} -> 'b
            }
            
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            ability ComplexStore 'a 'b with KVStore 'a 'b, Show 'a where {
                complexStore : {key: 'a, value: 'b, metadata: String} -> {}
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(3, typedProgram.items.size)
        
        val complexStore = typedProgram.items[2] as AbilityDeclaration
        assertNotNull(complexStore.inferredType)
        assertEquals(2, complexStore.dependencies.size)
        
        val kvStoreDep = complexStore.dependencies.find { it.abilityName == "KVStore" }
        assertNotNull(kvStoreDep)
        assertEquals(2, kvStoreDep!!.typeArgs.size)
        
        val showDep = complexStore.dependencies.find { it.abilityName == "Show" }
        assertNotNull(showDep)
        assertEquals(1, showDep!!.typeArgs.size)
    }

    @Test
    fun testAbilityImplementationWithDependencies() {
        val source = """
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }
            
            ability Ord 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> String
            }
            
            impl Eq Int where {
                equals : {left: Int, right: Int} -> Bool = {left, right} -> true
            }
            
            impl Ord Int where {
                compare : {left: Int, right: Int} -> String = {left, right} -> "equal"
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(4, typedProgram.items.size)
        
        // All items should type check successfully
        typedProgram.items.forEach { item ->
            when (item) {
                is AbilityDeclaration -> assertNotNull(item.inferredType)
                is ImplAbilityDeclaration -> assertNotNull(item.inferredType)
                else -> {} // Other item types not relevant for this test
            }
        }
    }

    @Test
    fun testComplexDependencyChain() {
        val source = """
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }
            
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            ability Ord 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> String
            }
            
            ability Debug 'a with Show 'a, Eq 'a where {
                debug : {val: 'a} -> String
            }
            
            ability Serializable 'a with Debug 'a, Ord 'a where {
                serialize : {val: 'a} -> String
                deserialize : {str: String} -> 'a
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(5, typedProgram.items.size)
        
        // All abilities should type check successfully
        typedProgram.items.forEach { item ->
            assertTrue(item is AbilityDeclaration)
            val ability = item as AbilityDeclaration
            assertNotNull(ability.inferredType)
        }
        
        val serializable = typedProgram.items[4] as AbilityDeclaration
        assertEquals(2, serializable.dependencies.size)
        
        val deps = serializable.dependencies.map { it.abilityName }.toSet()
        assertTrue(deps.contains("Debug"))
        assertTrue(deps.contains("Ord"))
    }

    @Test
    fun testAbilityWithoutDependencies() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(1, typedProgram.items.size)
        
        val show = typedProgram.items[0] as AbilityDeclaration
        assertNotNull(show.inferredType)
        assertEquals(0, show.dependencies.size)
    }
}
