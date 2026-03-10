package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComprehensiveAbilitiesAndEffectsTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testBasicAbilityDeclarationAndImplementation() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let displayValue: {val: 'a} -> String with Show 'a = {val} -> Show.show {val = val}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify ability declaration is processed
        val abilityDecl = typedProgram.items.find { it is AbilityDeclaration } as AbilityDeclaration?
        assertNotNull(abilityDecl)
        assertEquals("Show", abilityDecl.name)
        assertEquals(listOf("'a"), abilityDecl.typeParams)

        // Verify implementation is processed  
        val implDecl = typedProgram.items.find { it is ImplAbilityDeclaration } as ImplAbilityDeclaration?
        assertNotNull(implDecl)
        assertEquals("Show", implDecl.ability.localName)

        // Verify the displayValue binding has correct type
        val displayValueBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "displayValue" 
        } as LetBinding?
        assertNotNull(displayValueBinding)
        assertTrue(displayValueBinding.inferredType is MezzeType.FunctionType)
    }

    @Test
    fun testAbilityWithDependencies() {
        // Note: Using MyEq/MyOrd to avoid conflict with built-in Eq ability
        val source = """
            ability MyEq 'a where {
              equals : {left: 'a, right: 'a} -> Bool
            }

            ability MyOrd 'a with MyEq 'a where {
              compare : {left: 'a, right: 'a} -> Int
            }

            impl MyEq Int where {
              equals : {left: Int, right: Int} -> Bool = {left, right} -> left == right
            }

            impl MyOrd Int where {
              compare : {left: Int, right: Int} -> Int = {left, right} ->
                if MyEq.equals {left = left, right = right} then 0 else 1
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify MyOrd ability has dependency on MyEq
        val ordDecl = typedProgram.items.find {
            it is AbilityDeclaration && it.name == "MyOrd"
        } as AbilityDeclaration?
        assertNotNull(ordDecl)
        assertTrue(ordDecl.dependencies.isNotEmpty())
        assertEquals("MyEq", ordDecl.dependencies.first().abilityName)
    }

    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let displayValue: {val: 'a} -> String with Show 'a = {val} -> Show.show {val = val}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Should not throw
        assertTrue(true)
    }

    @Test
    fun testFunctionWithAbilityConstraints() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let displayValue: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}

            let result = displayValue {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify displayValue has ability constraints
        val displayValueBinding = typedProgram.items.find { 
            it is LetBinding && it.variableName == "displayValue" 
        } as LetBinding?
        assertNotNull(displayValueBinding)
        
        val functionType = displayValueBinding.inferredType as? MezzeType.FunctionType
        assertNotNull(functionType)
        assertTrue(functionType.abilityConstraints.isNotEmpty())
        assertEquals("Show", functionType.abilityConstraints.first().abilityName)
    }

    @Test
    fun testMultiParameterAbilities() {
        val source = """
            use std::int

            ability Convert 'a 'b where {
              convert : {val: 'a} -> 'b
            }

            impl Convert Int String where {
              convert : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let result = Convert.convert {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

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
            use std::int

            effect Database where {
              query : {sql: String} -> String <Database>
            }

            effect Log where {
              log : {message: String} -> Unit <Log>
            }

            let fetchUser: {id: Int} -> String <Database> = {id} ->
              Database.query {sql = "SELECT * FROM users WHERE id = $(int::to_string {n = id})"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify fetchUser function has only Database effect
        val fetchUserBinding = typedProgram.items.find {
            it is LetBinding && it.variableName == "fetchUser"
        } as LetBinding?
        assertNotNull(fetchUserBinding)

        val functionType = fetchUserBinding.inferredType as? MezzeType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Database"))
        assertEquals(1, functionType.effects!!.effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testNestedEffectPropagation() {
        val source = """
            use std::int

            effect Auth where {
              authenticate : {token: String} -> Boolean <Auth>
            }

            effect Database where {
              query : {sql: String} -> String <Database>
            }

            effect Log where {
              log : {message: String} -> Unit <Log>
            }

            effect SecureDatabase where {
              secureQuery : {sql: String, token: String} -> String <SecureDatabase>
            }

            let secureUserFetch: {id: Int, token: String} -> String <SecureDatabase> = {id, token} ->
              SecureDatabase.secureQuery {sql = "SELECT * FROM users WHERE id = $(int::to_string {n = id})", token = token}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify only SecureDatabase effect is used
        val fetchBinding = typedProgram.items.find {
            it is LetBinding && it.variableName == "secureUserFetch"
        } as LetBinding?
        assertNotNull(fetchBinding)

        val functionType = fetchBinding.inferredType as? MezzeType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("SecureDatabase"))
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testEffectHandling() {
        val source = """
            use std::int

            effect Log where {
              log : {message: String} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              log : {message: String} -> {} = {message} -> {}
            }

            let loggedOperation: {value: Int} -> Int <Log> = {value} -> do
              Log.log {message = int::to_string{n = value * 2 }}
              value * 2

            # Wrap in function to avoid top-level <*> restriction
            let getResult = {} -> perform ConsoleLogger in loggedOperation {value = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify loggedOperation has Log effect
        val operationBinding = typedProgram.items.find {
            it is LetBinding && it.variableName == "loggedOperation"
        } as LetBinding?
        assertNotNull(operationBinding)

        val functionType = operationBinding.inferredType as? MezzeType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))

        // Verify getResult is a function with performed marker but no Log effect
        val getResultBinding = typedProgram.items.find {
            it is LetBinding && it.variableName == "getResult"
        } as LetBinding?
        assertNotNull(getResultBinding)
        val getResultType = getResultBinding.inferredType as MezzeType.FunctionType
        assertNotNull(getResultType.effects)
        assertTrue(getResultType.effects!!.performed, "Should have performed marker")
        assertFalse(getResultType.effects!!.effects.contains("Log"), "Log should be handled")
        assertEquals(MezzeType.IntType, getResultType.returnType)
    }

    @Test
    fun testAbilityConstraintValidation() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Show Bool where {
              show : {val: Bool} -> String = {val} -> if val then "True" else "False"
            }

            let showValue: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}

            let result = showValue {val = True}
        """.trimIndent()

        val program = parser.parse(source)

        // With strict validation, all ability constraints must have implementations
        val typedProgram = typeChecker.typeCheck(program).program
        assertNotNull(typedProgram)
    }

    @Test
    fun testComplexAbilityAndEffectInteraction() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            effect Log where {
              log : {message: String} -> Unit <Log>
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Log for ConsoleLogger where {
              log : {message: String} -> Unit = {message} -> {}
            }

            let loggedShow: {val: 'a} -> String <Log> with Show 'a = {val} -> do
              let str = Show.show {val = val}
              Log.log {message = "Showing value: $(str)"}
              str

            # Wrap in function to avoid top-level <*> restriction
            let getResult = {} -> perform ConsoleLogger in loggedShow {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify loggedShow has both ability constraints and effects
        val loggedShowBinding = typedProgram.items.find {
            it is LetBinding && it.variableName == "loggedShow"
        } as LetBinding?
        assertNotNull(loggedShowBinding)

        val functionType = loggedShowBinding.inferredType as? MezzeType.FunctionType
        assertNotNull(functionType)

        // Check ability constraints
        assertTrue(functionType.abilityConstraints.isNotEmpty())
        assertEquals("Show", functionType.abilityConstraints.first().abilityName)

        // Check effects
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))

        // Verify getResult is a function with performed marker
        val getResultBinding = typedProgram.items.find {
            it is LetBinding && it.variableName == "getResult"
        } as LetBinding?
        assertNotNull(getResultBinding)
        val getResultType = getResultBinding.inferredType as MezzeType.FunctionType
        assertNotNull(getResultType.effects)
        assertTrue(getResultType.effects!!.performed, "Should have performed marker")
        assertEquals(MezzeType.StringType, getResultType.returnType)
    }
}
