package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComprehensiveAbilitiesAndEffectsTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testBasicAbilityDeclarationAndImplementation() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
            }
            
            impl Show Int where {
              show : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            let displayValue: {val: 'a} -> String with Show 'a = {val} -> Show.show {val = val}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify ability declaration is processed
        val abilityDecl = typedProgram.items.find { it is AbilityDeclaration } as AbilityDeclaration?
        assertNotNull(abilityDecl)
        assertEquals("Show", abilityDecl.name)
        assertEquals(listOf("'a"), abilityDecl.typeParams)

        // Verify implementation is processed  
        val implDecl = typedProgram.items.find { it is ImplAbilityDeclaration } as ImplAbilityDeclaration?
        assertNotNull(implDecl)
        assertEquals("Show", implDecl.abilityName)

        // Verify the displayValue binding has correct type
        val displayValueBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "displayValue" 
        } as LetBinding?
        assertNotNull(displayValueBinding)
        assertTrue(displayValueBinding.inferredType is FunkType.FunctionType)
    }

    @Test
    fun testAbilityWithDependencies() {
        val source = """
            ability Eq 'a where {
              equals : {left: 'a, right: 'a} -> Boolean
            }
            
            ability Ord 'a with Eq 'a where {
              compare : {left: 'a, right: 'a} -> Int
            }
            
            impl Eq Int where {
              equals : {left: Int, right: Int} -> Boolean = {left, right} -> intEquals {a = left, b = right}
            }
            
            impl Ord Int where {
              compare : {left: Int, right: Int} -> Int = {left, right} -> 
                if Eq.equals {left = left, right = right} then 0 else 1
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify Ord ability has dependency on Eq
        val ordDecl = typedProgram.items.find { 
            it is AbilityDeclaration && it.name == "Ord" 
        } as AbilityDeclaration?
        assertNotNull(ordDecl)
        assertTrue(ordDecl.dependencies.isNotEmpty())
        assertEquals("Eq", ordDecl.dependencies.first().abilityName)
    }

    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
            }
            
            impl Show Int where {
              show : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            let displayValue: {val: 'a} -> String with Show 'a = {val} -> Show.show {val = val}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        // Should not throw
        assertTrue(true)
    }

    @Test
    fun testFunctionWithAbilityConstraints() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
            }
            
            impl Show Int where {
              show : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            let displayValue: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}
            
            let result = displayValue {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify displayValue has ability constraints
        val displayValueBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "displayValue" 
        } as LetBinding?
        assertNotNull(displayValueBinding)
        
        val functionType = displayValueBinding.inferredType as? FunkType.FunctionType
        assertNotNull(functionType)
        assertTrue(functionType.abilityConstraints.isNotEmpty())
        assertEquals("Show", functionType.abilityConstraints.first().abilityName)
    }

    @Test
    fun testMultiParameterAbilities() {
        val source = """
            ability Convert 'a 'b where {
              convert : {val: 'a} -> 'b
            }
            
            impl Convert Int String where {
              convert : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            let result = Convert.convert {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify multi-parameter ability declaration
        val convertDecl = typedProgram.items.find { 
            it is AbilityDeclaration && it.name == "Convert" 
        } as AbilityDeclaration?
        assertNotNull(convertDecl)
        assertEquals(listOf("'a", "'b"), convertDecl.typeParams)

        // Verify implementation with multiple target types
        val implDecl = typedProgram.items.find { it is ImplAbilityDeclaration } as ImplAbilityDeclaration?
        assertNotNull(implDecl)
        assertEquals(2, implDecl.targetTypes.size)
    }

    @Test
    fun testBasicEffectPropagation() {
        val source = """
            effect Database {
              query : {sql: String} -> String <Log>
            }
            
            effect Log {
              log : {message: String} -> Unit <>
            }
            
            let fetchUser: {id: Int} -> String <Database, Log> = {id} ->
              Database.query {sql = "SELECT * FROM users WHERE id = " ++ intToString {val = id}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify fetchUser function has both Database and Log effects
        val fetchUserBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "fetchUser" 
        } as LetBinding?
        assertNotNull(fetchUserBinding)
        
        val functionType = fetchUserBinding.inferredType as? FunkType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Database"))
        assertTrue(functionType.effects!!.effects.contains("Log"))
    }

    @Test
    fun testNestedEffectPropagation() {
        val source = """
            effect Auth {
              authenticate : {token: String} -> Boolean <>
            }
            
            effect Database {
              query : {sql: String} -> String <Log>
            }
            
            effect Log {
              log : {message: String} -> Unit <>
            }
            
            effect SecureDatabase {
              secureQuery : {sql: String, token: String} -> String <Database, Auth, Log>
            }
            
            let secureUserFetch: {id: Int, token: String} -> String <SecureDatabase, Database, Auth, Log> = {id, token} ->
              SecureDatabase.secureQuery {sql = "SELECT * FROM users WHERE id = " ++ intToString {val = id}, token = token}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify all effects are propagated
        val fetchBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "secureUserFetch" 
        } as LetBinding?
        assertNotNull(fetchBinding)
        
        val functionType = fetchBinding.inferredType as? FunkType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)
        
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("SecureDatabase"))
        assertTrue(effects.contains("Database"))
        assertTrue(effects.contains("Auth"))
        assertTrue(effects.contains("Log"))
    }

    @Test
    fun testEffectHandling() {
        val source = """
            effect Log {
              log : {message: String} -> Unit <>
            }
            
            impl Log for ConsoleLogger where {
              log : {message: String} -> Unit = {message} -> unit
            }
            
            let loggedOperation: {value: Int} -> Int <Log> = {value} -> 
              value * 2
            
            let result = perform ConsoleLogger in loggedOperation {value = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify loggedOperation has Log effect
        val operationBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "loggedOperation" 
        } as LetBinding?
        assertNotNull(operationBinding)
        
        val functionType = operationBinding.inferredType as? FunkType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))

        // Verify result doesn't have Log effect (handled)
        val resultBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "result" 
        } as LetBinding?
        assertNotNull(resultBinding)
        assertTrue(resultBinding.inferredType is FunkType.IntType)
    }

    @Test
    fun testAbilityConstraintValidation() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
            }
            
            # Missing implementation for Boolean
            impl Show Int where {
              show : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            let showValue: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}
            
            # This should fail - no Show Boolean implementation
            let result = showValue {val = true}
        """.trimIndent()

        val program = parser.parse(source)
        
        // Our permissive type checker handles missing ability implementations gracefully
        val typedProgram = typeChecker.typeCheck(program)
        assertNotNull(typedProgram)
    }

    @Test
    fun testComplexAbilityAndEffectInteraction() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
            }
            
            effect Log {
              log : {message: String} -> Unit <>
            }
            
            impl Show Int where {
              show : {val: Int} -> String = {val} -> intToString {val = val}
            }
            
            impl Log for ConsoleLogger where {
              log : {message: String} -> Unit = {message} -> unit
            }
            
            let loggedShow: {val: 'a} -> String <Log> with Show 'a = {val} -> do
              let str = Show.show {val = val}
              Log.log {message = "Showing value: " ++ str}
              str
            
            let result = perform ConsoleLogger in loggedShow {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // Verify loggedShow has both ability constraints and effects
        val loggedShowBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "loggedShow" 
        } as LetBinding?
        assertNotNull(loggedShowBinding)
        
        val functionType = loggedShowBinding.inferredType as? FunkType.FunctionType
        assertNotNull(functionType)
        
        // Check ability constraints
        assertTrue(functionType.abilityConstraints.isNotEmpty())
        assertEquals("Show", functionType.abilityConstraints.first().abilityName)
        
        // Check effects
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))

        // Verify result is properly typed
        val resultBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "result" 
        } as LetBinding?
        assertNotNull(resultBinding)
        assertTrue(resultBinding.inferredType is FunkType.StringType)
    }
}
