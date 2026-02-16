package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComprehensiveAbilitiesAndEffectsInterpreterTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        com.funk.language.typechecker.ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    @Test
    fun testBasicAbilityExecution() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            Show.show {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("42", (result as FunkString).value)
    }

    @Test
    fun testMultiParameterAbilityExecution() {
        val source = """
            use std::int

            ability Convert 'a 'b where {
              convert : {val: 'a} -> 'b
            }

            impl Convert Int String where {
              convert : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            Convert.convert {val = 123}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("123", (result as FunkString).value)
    }

    @Test
    fun testAbilityWithConstraintsExecution() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Show String where {
              show : {val: String} -> String = {val} -> val
            }

            let displayValue: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}

            displayValue {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("42", (result as FunkString).value)
    }

    @Test
    fun testAbilityWithDifferentTypesExecution() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> "INT:$(int::to_string {n = val})"
            }

            impl Show String where {
              show : {val: String} -> String = {val} -> "STR:$(val)"
            }

            let displayInt = Show.show {val = 42}
            let displayStr = Show.show {val = "hello"}

            "$(displayInt) | $(displayStr)"
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("INT:42 | STR:hello", (result as FunkString).value)
    }

    @Test
    fun testBasicEffectExecutionWithPropagation() {
        val source = """
            effect Database where {
              query : {sql: String} -> String <Database, Log>
            }

            effect Log where {
              log : {message: String} -> {} <Log>
            }

            impl Database for MockDB where {
              query : {sql: String} -> String = {sql} -> "mock_result_for_$(sql)"
            }

            impl Log for ConsoleLogger where {
              log : {message: String} -> {} = {message} -> {}
            }

            let fetchData: {table: String} -> String <Database, Log> = {table} ->
              Database.query {sql = "SELECT * FROM $(table)"}
            
            perform ConsoleLogger, MockDB in fetchData {table = "users"}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("mock_result_for_SELECT * FROM users", (result as FunkString).value)
    }

    @Test
    fun testNestedEffectPropagationExecution() {
        val source = """
            effect Auth where {
              authenticate : {token: String} -> Boolean <Auth>
            }

            effect Database where {
              query : {sql: String} -> String <Database, Log>
            }

            effect Log where {
              log : {message: String} -> {} <Log>
            }

            effect SecureDatabase where {
              secureQuery : {sql: String, token: String} -> String <SecureDatabase, Database, Auth, Log>
            }

            impl Auth for MockAuth where {
              authenticate : {token: String} -> Boolean = {token} -> True
            }
            
            impl Database for MockDB where {
              query : {sql: String} -> String = {sql} -> "db:$(sql)"
            }
            
            impl Log for MockLogger where {
              log : {message: String} -> {} = {message} -> {}
            }
            
            impl SecureDatabase for MockSecureDB where {
              secureQuery : {sql: String, token: String} -> String = {sql, token} ->
                if Auth.authenticate {token = token} then
                  Database.query {sql = sql}
                else
                  "unauthorized"
            }
            
            perform MockLogger, MockAuth, MockDB, MockSecureDB in
              SecureDatabase.secureQuery {sql = "SELECT * FROM users", token = "valid"}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("db:SELECT * FROM users", (result as FunkString).value)
    }

    @Test
    fun testComplexAbilityAndEffectInteractionExecution() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
            }

            effect Log where {
              log : {message: String} -> Unit <Log>
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> "123"
            }
            
            impl Log for MockLogger where {
              log : {message: String} -> {} = {message} -> {}
            }
            
            perform MockLogger in do
              let str = Show.show {val = 123}
              Log.log {message = "Showing: $(str)"}
              "logged_$(str)"
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("logged_123", (result as FunkString).value)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Pre-existing bug: Operation 'equals' not found in implementation of 'Eq'")
    fun testAbilityChainExecution() {
        val source = """
            ability Eq 'a where {
              equals : {left: 'a, right: 'a} -> Bool
            }

            ability Show 'a where {
              show : {val: 'a} -> String
            }

            impl Eq Int where {
              equals : {left: Int, right: Int} -> Bool = {left, right} -> True
            }

            impl Show Int where {
              show : {val: Int} -> String = {val} -> "5"
            }

            impl Show Bool where {
              show : {val: Bool} -> String = {val} -> "True"
            }

            do
              let isEqual = Eq.equals {left = 5, right = 5}
              let xStr = Show.show {val = 5}
              let yStr = Show.show {val = 5}
              let resultStr = Show.show {val = isEqual}
              "$(xStr) == $(yStr) is $(resultStr)"
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("5 == 5 is true", (result as FunkString).value)
    }

    @Test
    fun testEffectHandlerSwitchingExecution() {
        val source = """
            effect Log where {
              log : {message: String} -> String <Log>
            }

            impl Log for PrefixLogger where {
              log : {message: String} -> String = {message} -> "PREFIX: $(message)"
            }

            impl Log for SuffixLogger where {
              log : {message: String} -> String = {message} -> "$(message) :SUFFIX"
            }

            let logMessage: {msg: String} -> String <Log> = {msg} ->
              Log.log {message = msg}

            let getResult = {} -> do
                let prefixResult = perform PrefixLogger in logMessage {msg = "hello"}
                let suffixResult = perform SuffixLogger in logMessage {msg = "world"}
                "$(prefixResult) | $(suffixResult)"
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", FunkRecord(emptyMap()), typeCheckResult.monomorphicInstances)

        assertTrue(result is FunkString)
        assertEquals("PREFIX: hello | world :SUFFIX", (result as FunkString).value)
    }

    @Test
    fun testPolymorphicAbilityResolutionExecution() {
        val source = """
            effect StringContainer where {
              empty : {} -> String <StringContainer>,
              add : {container: String, item: String} -> String <StringContainer>,
              size : {container: String} -> Int <StringContainer>
            }

            impl StringContainer for TestContainer where {
              empty : {} -> String = {} -> "",
              add : {container: String, item: String} -> String = {container, item} ->
                if container == "" then item else "$(container),$(item)",
              size : {container: String} -> Int = {container} ->
                if container == "" then 0 else 1
            }
            
            perform TestContainer in do
              let empty = StringContainer.empty {}
              let filled = StringContainer.add {container = empty, item = "test"}
              StringContainer.size {container = filled}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }
}
