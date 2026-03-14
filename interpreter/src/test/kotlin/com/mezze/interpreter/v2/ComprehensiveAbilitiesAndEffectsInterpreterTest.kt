package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Comprehensive tests for abilities and effects interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class ComprehensiveAbilitiesAndEffectsInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testBasicAbilityExecution() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> Str
            }

            impl Show Int where {
              show : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            Show.show {val = 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testMultiParameterAbilityExecution() {
        val source = """
            use std::int

            ability Convert 'a 'b where {
              convert : {val: 'a} -> 'b
            }

            impl Convert Int Str where {
              convert : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            Convert.convert {val = 123}
        """.trimIndent()

        val result = eval(source)
        assertEquals("123", result)
    }

    @Test
    fun testAbilityWithConstraintsExecution() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> Str
            }

            impl Show Int where {
              show : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Show Str where {
              show : {val: Str} -> Str = {val} -> val
            }

            let displayValue: {val: 'a} -> Str with Show 'a = {val} ->
              Show.show {val = val}

            displayValue {val = 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testAbilityWithDifferentTypesExecution() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> Str
            }

            impl Show Int where {
              show : {val: Int} -> Str = {val} -> "INT:$(int::to_str {n = val})"
            }

            impl Show Str where {
              show : {val: Str} -> Str = {val} -> "STR:$(val)"
            }

            let displayInt = Show.show {val = 42}
            let displayStr = Show.show {val = "hello"}

            "$(displayInt) | $(displayStr)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("INT:42 | STR:hello", result)
    }

    @Test
    fun testBasicEffectExecutionWithPropagation() {
        val source = """
            effect Database where {
              query : {sql: Str} -> Str <Database, Log>
            }

            effect Log where {
              log : {message: Str} -> {} <Log>
            }

            impl Database for MockDB where {
              query : {sql: Str} -> Str = {sql} -> "mock_result_for_$(sql)"
            }

            impl Log for ConsoleLogger where {
              log : {message: Str} -> {} = {message} -> {}
            }

            let fetchData: {table: Str} -> Str <Database, Log> = {table} ->
              Database.query {sql = "SELECT * FROM $(table)"}

            perform ConsoleLogger, MockDB in fetchData {table = "users"}
        """.trimIndent()

        val result = eval(source)
        assertEquals("mock_result_for_SELECT * FROM users", result)
    }

    @Test
    fun testNestedEffectPropagationExecution() {
        val source = """
            effect Auth where {
              authenticate : {token: Str} -> Bool <Auth>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database, Log>
            }

            effect Log where {
              log : {message: Str} -> {} <Log>
            }

            effect SecureDatabase where {
              secureQuery : {sql: Str, token: Str} -> Str <SecureDatabase, Database, Auth, Log>
            }

            impl Auth for MockAuth where {
              authenticate : {token: Str} -> Bool = {token} -> True
            }

            impl Database for MockDB where {
              query : {sql: Str} -> Str = {sql} -> "db:$(sql)"
            }

            impl Log for MockLogger where {
              log : {message: Str} -> {} = {message} -> {}
            }

            impl SecureDatabase for MockSecureDB where {
              secureQuery : {sql: Str, token: Str} -> Str = {sql, token} ->
                if Auth.authenticate {token = token} then
                  Database.query {sql = sql}
                else
                  "unauthorized"
            }

            perform MockLogger, MockAuth, MockDB, MockSecureDB in
              SecureDatabase.secureQuery {sql = "SELECT * FROM users", token = "valid"}
        """.trimIndent()

        val result = eval(source)
        assertEquals("db:SELECT * FROM users", result)
    }

    @Test
    fun testComplexAbilityAndEffectInteractionExecution() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> Str
            }

            effect Log where {
              log : {message: Str} -> Unit <Log>
            }

            impl Show Int where {
              show : {val: Int} -> Str = {val} -> "123"
            }

            impl Log for MockLogger where {
              log : {message: Str} -> {} = {message} -> {}
            }

            perform MockLogger in do
              let str = Show.show {val = 123}
              Log.log {message = "Showing: $(str)"}
              "logged_$(str)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("logged_123", result)
    }

    @Test
    fun testAbilityChainExecution() {
        val source = """
            ability Eq 'a where {
              equals : {left: 'a, right: 'a} -> Bool
            }

            ability Show 'a where {
              show : {val: 'a} -> Str
            }

            impl Eq Int where {
              equals : {left: Int, right: Int} -> Bool = {left, right} -> True
            }

            impl Show Int where {
              show : {val: Int} -> Str = {val} -> "5"
            }

            impl Show Bool where {
              show : {val: Bool} -> Str = {val} -> "True"
            }

            do
              let isEqual = Eq.equals {left = 5, right = 5}
              let xStr = Show.show {val = 5}
              let yStr = Show.show {val = 5}
              let resultStr = Show.show {val = isEqual}
              "$(xStr) == $(yStr) is $(resultStr)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("5 == 5 is True", result)
    }

    @Test
    fun testEffectHandlerSwitchingExecution() {
        val source = """
            effect Log where {
              log : {message: Str} -> Str <Log>
            }

            impl Log for PrefixLogger where {
              log : {message: Str} -> Str = {message} -> "PREFIX: $(message)"
            }

            impl Log for SuffixLogger where {
              log : {message: Str} -> Str = {message} -> "$(message) :SUFFIX"
            }

            let logMessage: {msg: Str} -> Str <Log> = {msg} ->
              Log.log {message = msg}

            let getResult = {} -> do
                let prefixResult = perform PrefixLogger in logMessage {msg = "hello"}
                let suffixResult = perform SuffixLogger in logMessage {msg = "world"}
                "$(prefixResult) | $(suffixResult)"

            getResult {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("PREFIX: hello | world :SUFFIX", result)
    }

    @Test
    fun testPolymorphicAbilityResolutionExecution() {
        val source = """
            effect StringContainer where {
              empty : {} -> Str <StringContainer>,
              add : {container: Str, item: Str} -> Str <StringContainer>,
              size : {container: Str} -> Int <StringContainer>
            }

            impl StringContainer for TestContainer where {
              empty : {} -> Str = {} -> "",
              add : {container: Str, item: Str} -> Str = {container, item} ->
                if container == "" then item else "$(container),$(item)",
              size : {container: Str} -> Int = {container} ->
                if container == "" then 0 else 1
            }

            perform TestContainer in do
              let empty = StringContainer.empty {}
              let filled = StringContainer.add {container = empty, item = "test"}
              StringContainer.size {container = filled}
        """.trimIndent()

        val result = eval(source)
        assertEquals(1L, result)
    }
}
