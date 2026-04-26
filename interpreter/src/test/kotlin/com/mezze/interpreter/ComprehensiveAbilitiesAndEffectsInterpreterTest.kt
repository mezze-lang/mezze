package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeRecord

/**
 * Comprehensive tests for abilities and effects interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class ComprehensiveAbilitiesAndEffectsInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testBasicAbilityExecution() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            42.show {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testMultiParameterAbilityExecution() {
        val source = """
            use std::int

            ability MyConvert 'b for 'a where {
              convert : {it: 'a} -> 'b
            }

            impl MyConvert for Str Int where {
              convert = {it} -> int::to_str {n = it}
            }

            123.convert {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("123", result)
    }

    @Test
    fun testAbilityWithConstraintsExecution() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Show for Str where {
              show = {it} -> it
            }

            let displayValue: {val: 'a} -> Str with Show 'a = {val} ->
              val.show {}

            displayValue {val = 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testAbilityWithDifferentTypesExecution() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> "INT:$(int::to_str {n = it})"
            }

            impl Show for Str where {
              show = {it} -> "STR:$(it)"
            }

            let displayInt = 42.show {}
            let displayStr = "hello".show {}

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
            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            effect Log where {
              log : {message: Str} -> Unit <Log>
            }

            impl Show for Int where {
              show = {it} -> "123"
            }

            impl Log for MockLogger where {
              log : {message: Str} -> {} = {message} -> {}
            }

            perform MockLogger in do
              let str = 123.show {}
              Log.log {message = "Showing: $(str)"}
              "logged_$(str)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("logged_123", result)
    }

    @Test
    fun testAbilityChainExecution() {
        val source = """
            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> "5"
            }

            impl Show for Bool where {
              show = {it} -> "True"
            }

            do
              let xStr = 5.show {}
              let resultStr = True.show {}
              "$(xStr) is $(resultStr)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("5 is True", result)
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

    @Test
    fun testAbilityMethodCallDirect() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            42.show {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testAbilityWithMultipleMethodsDirect() {
        val source = """
            use std::int

            ability Displayable for 'a where {
              show : {it: 'a} -> Str,
              display : {it: 'a, verbose: Bool } -> Str
            }

            impl Displayable for Int where {
              show = {it} -> int::to_str {n = it},
              display = {it, verbose } -> if verbose then "Int($(int::to_str {n = it}))" else int::to_str {n = it}
            }

            let s = 99.show {}
            let d = 99.display { verbose = True }
            "$(s) | $(d)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("99 | Int(99)", result)
    }

    @Test
    fun testAbilityMethodCallChained() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            7.show {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("7", result)
    }
}
