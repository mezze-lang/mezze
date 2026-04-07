package com.mezze.language.typechecker

import java.nio.file.Paths
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

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            let displayValue: {val: 'a} -> Str with Show 'a = {val} -> val.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify ability declaration is processed (from parsed AST)
        val abilityDecl = parsedModule.decls.find { it is com.mezze.language.parsedast.ParsedAbilityDecl } as com.mezze.language.parsedast.ParsedAbilityDecl?
        assertNotNull(abilityDecl)
        assertEquals("Show", abilityDecl.name)
        assertEquals(listOf("'a"), abilityDecl.typeParams)

        // Verify implementation is processed (from parsed AST)
        val implDecl = parsedModule.decls.find { it is com.mezze.language.parsedast.ParsedImplAbilityDecl } as com.mezze.language.parsedast.ParsedImplAbilityDecl?
        assertNotNull(implDecl)
        assertEquals("Show", implDecl.abilityName)

        // Verify the displayValue binding has correct type
        val displayValueType = result.getVariableType("displayValue")
        assertNotNull(displayValueType)
        assertTrue(displayValueType is MezzeType.FunctionType)
    }

    @Test
    fun testAbilityWithMethodLevelConstraints() {
        // Note: Using MyEq/MyOrd to avoid conflict with built-in Eq ability
        val source = """
            ability MyEq for 'a where {
              equals : {it: 'a, right: 'a} -> Bool
            }

            ability MyOrd for 'a where {
              compare : {it: 'a, right: 'a} with MyEq 'a -> Int
            }

            impl MyEq for Int where {
              equals = {it, right} -> it == right
            }

            impl MyOrd for Int where {
              compare = {it, right} ->
                if it.equals {right = right} then 0 else 1
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify MyOrd ability has method-level constraint (not ability-level dependency) — from parsed AST
        val ordDecl = parsedModule.decls.find {
            it is com.mezze.language.parsedast.ParsedAbilityDecl && it.name == "MyOrd"
        } as com.mezze.language.parsedast.ParsedAbilityDecl?
        assertNotNull(ordDecl)
        assertTrue(ordDecl.dependencies.isEmpty())
        assertEquals(1, ordDecl.operations.first().abilityConstraints.size)
        assertEquals("MyEq", ordDecl.operations.first().abilityConstraints.first().abilityName)
    }

    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            let displayValue: {val: 'a} -> Str with Show 'a = {val} -> val.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Should not throw
        assertTrue(true)
    }

    @Test
    fun testFunctionWithAbilityConstraints() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            let displayValue: {val: 'a} -> Str with Show 'a = {val} ->
              val.show {}

            let result = displayValue {val = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify displayValue has ability constraints
        val functionType = result.getVariableType("displayValue") as? MezzeType.FunctionType
        assertNotNull(functionType)
        assertTrue(functionType.abilityConstraints.isNotEmpty())
        assertEquals("Show", functionType.abilityConstraints.first().abilityName)
    }

    @Test
    fun testMultiParameterAbilities() {
        val source = """
            use std::int

            ability Convert 'b for 'a where {
              convert : {it: 'a} -> 'b
            }

            impl Convert for Str Int where {
              convert = {it} -> int::to_str {n = it}
            }

            let result = 42.convert {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify multi-parameter ability declaration — from parsed AST
        val convertDecl = parsedModule.decls.find {
            it is com.mezze.language.parsedast.ParsedAbilityDecl && it.name == "Convert"
        } as com.mezze.language.parsedast.ParsedAbilityDecl?
        assertNotNull(convertDecl)
        assertEquals(listOf("'b", "'a"), convertDecl.typeParams)

        // Verify implementation with multiple target types — from parsed AST
        val implDecl = parsedModule.decls.find { it is com.mezze.language.parsedast.ParsedImplAbilityDecl } as com.mezze.language.parsedast.ParsedImplAbilityDecl?
        assertNotNull(implDecl)
        assertEquals(2, implDecl.targetTypes.size)
    }

    @Test
    fun testBasicEffectPropagation() {
        val source = """
            use std::int

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            effect Log where {
              log : {message: Str} -> Unit <Log>
            }

            let fetchUser: {id: Int} -> Str <Database> = {id} ->
              Database.query {sql = "SELECT * FROM users WHERE id = $(int::to_str {n = id})"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify fetchUser function has only Database effect
        val functionType = result.getVariableType("fetchUser") as? MezzeType.FunctionType
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
              authenticate : {token: Str} -> Boolean <Auth>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            effect Log where {
              log : {message: Str} -> Unit <Log>
            }

            effect SecureDatabase where {
              secureQuery : {sql: Str, token: Str} -> Str <SecureDatabase>
            }

            let secureUserFetch: {id: Int, token: Str} -> Str <SecureDatabase> = {id, token} ->
              SecureDatabase.secureQuery {sql = "SELECT * FROM users WHERE id = $(int::to_str {n = id})", token = token}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify only SecureDatabase effect is used
        val functionType = result.getVariableType("secureUserFetch") as? MezzeType.FunctionType
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
              log : {message: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              log : {message: Str} -> {} = {message} -> {}
            }

            let loggedOperation: {value: Int} -> Int <Log> = {value} -> do
              Log.log {message = int::to_str{n = value * 2 }}
              value * 2

            # Wrap in function to avoid top-level <*> restriction
            let getResult = {} -> perform ConsoleLogger in loggedOperation {value = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify loggedOperation has Log effect
        val functionType = result.getVariableType("loggedOperation") as? MezzeType.FunctionType
        assertNotNull(functionType)
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))

        // Verify getResult is a function with performed marker but no Log effect
        val getResultType = result.getVariableType("getResult") as MezzeType.FunctionType
        assertNotNull(getResultType.effects)
        assertTrue(getResultType.effects!!.performed, "Should have performed marker")
        assertFalse(getResultType.effects!!.effects.contains("Log"), "Log should be handled")
        assertEquals(MezzeType.IntType, getResultType.returnType)
    }

    @Test
    fun testAbilityConstraintValidation() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Show for Bool where {
              show = {it} -> if it then "True" else "False"
            }

            let showValue: {val: 'a} -> Str with Show 'a = {val} ->
              val.show {}

            let result = showValue {val = True}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // With strict validation, all ability constraints must have implementations
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun testComplexAbilityAndEffectInteraction() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            effect Log where {
              log : {message: Str} -> Unit <Log>
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Log for ConsoleLogger where {
              log : {message: Str} -> Unit = {message} -> {}
            }

            let loggedShow: {val: 'a} -> Str <Log> with Show 'a = {val} -> do
              let str = val.show {}
              Log.log {message = "Showing value: $(str)"}
              str

            # Wrap in function to avoid top-level <*> restriction
            let getResult = {} -> perform ConsoleLogger in loggedShow {val = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify loggedShow has both ability constraints and effects
        val functionType = result.getVariableType("loggedShow") as? MezzeType.FunctionType
        assertNotNull(functionType)

        // Check ability constraints
        assertTrue(functionType.abilityConstraints.isNotEmpty())
        assertEquals("Show", functionType.abilityConstraints.first().abilityName)

        // Check effects
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))

        // Verify getResult is a function with performed marker
        val getResultType = result.getVariableType("getResult") as MezzeType.FunctionType
        assertNotNull(getResultType.effects)
        assertTrue(getResultType.effects!!.performed, "Should have performed marker")
        assertEquals(MezzeType.StringType, getResultType.returnType)
    }
}
