package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Comprehensive tests for string interpolation type checking.
 * Tests cover integration with various language features:
 * - Abilities (Show, NumAdd, etc.)
 * - Pattern matching
 * - Match expressions
 * - Do-expressions
 * - Effects
 * - Nested function calls
 * - Binary operations
 */
class StringInterpolationTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun assertTypeChecks(source: String) {
        val program = parser.parse(source)
        assertDoesNotThrow { typeChecker.typeCheck(program).program }
    }

    private fun assertTypeCheckFails(source: String, expectedError: String? = null) {
        val program = parser.parse(source)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
        if (expectedError != null) {
            assertTrue(
                exception.message?.contains(expectedError, ignoreCase = true) == true,
                "Expected error containing '$expectedError' but got: ${exception.message}"
            )
        }
    }

    // ==================== Basic String Interpolation ====================

    @Test
    fun `test basic string variable interpolation`() {
        assertTypeChecks("""
            let name = "World"
            let greeting = "Hello $(name)!"
            greeting
        """.trimIndent())
    }

    @Test
    fun `test multiple interpolations in one string`() {
        assertTypeChecks("""
            let first = "Hello"
            let second = "World"
            let result = "$(first) $(second)!"
            result
        """.trimIndent())
    }

    @Test
    fun `test interpolation with string returning function`() {
        assertTypeChecks("""
            let greet = {name: Str} -> "Hello $(name)"
            let result = "Message: $(greet {name = "Alice"})"
            result
        """.trimIndent())
    }

    @Test
    fun `test non-string type in interpolation fails`() {
        assertTypeCheckFails("""
            let num = 42
            let msg = "Number: $(num)"
            msg
        """.trimIndent(), "Str")
    }

    // ==================== Abilities with String Interpolation ====================

    @Test
    fun `test Show ability used in interpolation`() {
        assertTypeChecks("""
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            let display = {x: Int} -> "Value: $(x.show {})"
            display {x = 42}
        """.trimIndent())
    }

    @Test
    fun `test polymorphic function with Show constraint using interpolation`() {
        assertTypeChecks("""
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            let display: {val: 'a} -> Str with Show 'a = {val} ->
                val.show {}

            let format: {x: 'a} -> Str with Show 'a = {x} ->
                "[$(display {val = x})]"

            format {x = 42}
        """.trimIndent())
    }

    @Test
    fun `test nested ability calls in interpolation`() {
        assertTypeChecks("""
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            impl Show for Str where {
                show = {} -> "\"$(it)\""
            }

            let showBoth: {a: 'x, b: 'y} -> Str with Show 'x, Show 'y = {a, b} ->
                "$(a.show {}), $(b.show {})"

            showBoth {a = 42, b = "hello"}
        """.trimIndent())
    }

    @Test
    fun `test ability constraint propagation through interpolation`() {
        assertTypeChecks("""
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            let wrap: {val: 'a} -> Str with Show 'a = {val} ->
                "<$(val.show {})>"

            let doubleWrap: {val: 'a} -> Str with Show 'a = {val} ->
                "[[$(wrap {val = val})]]"

            doubleWrap {val = 42}
        """.trimIndent())
    }

    // ==================== Pattern Matching with String Interpolation ====================

    @Test
    fun `test match expression inside interpolation`() {
        assertTypeChecks("""
            type Status = | Active {}, Inactive {} |

            let statusToString = {s: Status} -> match s is
                Active {} -> "active"
                Inactive {} -> "inactive"

            let describe = {s: Status} -> "Status is: $(statusToString {s = s})"
            describe {s = Active {}}
        """.trimIndent())
    }

    @Test
    fun `test match with data extraction in interpolation context`() {
        assertTypeChecks("""
            type Result = | Success { message: Str }, Failure { error: Str } |

            let getMessage = {r: Result} -> match r is
                Success { message } -> message
                Failure { error } -> error

            let format = {r: Result} -> "Result: $(getMessage {r = r})"
            format {r = Success {message = "OK"}}
        """.trimIndent())
    }

    @Test
    fun `test or-pattern in match with interpolation`() {
        assertTypeChecks("""
            type Temperature = | Hot { degrees: Int }, Cold { degrees: Int }, Mild {} |

            let describe = {temp: Temperature} -> match temp is
                Hot { ... } | Cold { ... } -> "extreme"
                Mild {} -> "comfortable"

            let report = {temp: Temperature} -> "Weather is $(describe {temp = temp})"
            report {temp = Hot {degrees = 100}}
        """.trimIndent())
    }

    @Test
    fun `test wildcard pattern in match with interpolation`() {
        assertTypeChecks("""
            type Color = | Red {}, Green {}, Blue {}, Custom { name: Str } |

            let colorName = {c: Color} -> match c is
                Red {} -> "red"
                Green {} -> "green"
                Blue {} -> "blue"
                _ -> "custom"

            let display = {c: Color} -> "Color: $(colorName {c = c})"
            display {c = Custom {name = "purple"}}
        """.trimIndent())
    }

    // ==================== Do-Expressions with String Interpolation ====================

    @Test
    fun `test do expression in interpolation context`() {
        assertTypeChecks("""
            let compute = {} -> do
                let a = "Hello"
                let b = "World"
                "$(a) $(b)"

            let result = compute {}
            result
        """.trimIndent())
    }

    @Test
    fun `test do expression with intermediate string building`() {
        assertTypeChecks("""
            let buildMessage = {name: Str, action: Str} -> do
                let greeting = "Hello $(name)"
                let message = "$(greeting), you should $(action)"
                message

            buildMessage {name = "Alice", action = "run"}
        """.trimIndent())
    }

    // ==================== Effects with String Interpolation ====================

    @Test
    fun `test effect call result in interpolation`() {
        assertTypeChecks("""
            effect FileSystem where {
                readFile : {path: Str} -> Str <FileSystem>
            }

            impl FileSystem for MockFS where {
                readFile : {path: Str} -> Str = {path} -> "file content"
            }

            let displayFile: {path: Str} -> Str <FileSystem> = {path} ->
                "Content: $(FileSystem.readFile {path = path})"

            perform MockFS in displayFile {path = "/test.txt"}
        """.trimIndent())
    }

    @Test
    fun `test log effect with interpolated message`() {
        assertTypeChecks("""
            effect Log where {
                print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print : {msg: Str} -> {} = {msg} -> {}
            }

            let logMessage: {prefix: Str, message: Str} -> {} <Log> = {prefix, message} ->
                Log.print {msg = "$(prefix): $(message)"}

            perform Console in logMessage {prefix = "INFO", message = "Hello"}
        """.trimIndent())
    }

    @Test
    fun `test effect call in interpolation without annotation`() {
        assertTypeChecks("""
            effect FileSystem where {
                readFile : {path: Str} -> Str <FileSystem>
            }

            impl FileSystem for MockFS where {
                readFile : {path: Str} -> Str = {path} -> "file content"
            }

            let displayFile = {path: Str} ->
                "Content: $(FileSystem.readFile {path = path})"

            perform MockFS in displayFile {path = "/test.txt"}
        """.trimIndent())
    }

    @Test
    fun `test log effect in interpolation without annotation`() {
        assertTypeChecks("""
            effect Log where {
                print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print : {msg: Str} -> {} = {msg} -> {}
            }

            let logMessage = {prefix: Str, message: Str} ->
                Log.print {msg = "$(prefix): $(message)"}

            perform Console in logMessage {prefix = "INFO", message = "Hello"}
        """.trimIndent())
    }

    // ==================== Record Field Access in String Interpolation ====================

    @Test
    fun `test record field access in interpolation`() {
        assertTypeChecks("""
            let person = {name = "Alice", title = "Engineer"}
            let intro = "$(person.name) is a $(person.title)"
            intro
        """.trimIndent())
    }

    @Test
    fun `test nested record field access in interpolation`() {
        assertTypeChecks("""
            let company = {
                ceo = {name = "Bob", title = "CEO"},
                name = "TechCorp"
            }
            let desc = "$(company.ceo.name) leads $(company.name)"
            desc
        """.trimIndent())
    }

    // ==================== If-Expressions in String Interpolation ====================

    @Test
    fun `test if expression inside interpolation`() {
        assertTypeChecks("""
            let result = "$(if True then "yes" else "no")"
            result
        """.trimIndent())
    }

    @Test
    fun `test complex condition in interpolated if`() {
        assertTypeChecks("""
            let x = 10
            let y = 5
            let comparison = "$(if x > y then "greater" else "not greater")"
            comparison
        """.trimIndent())
    }

    @Test
    fun `test nested if expressions in interpolation`() {
        assertTypeChecks("""
            let x = 5
            let result = "$(if x > 10 then "big" else if x > 0 then "small" else "zero or negative")"
            result
        """.trimIndent())
    }

    // ==================== Lambda in String Interpolation Context ====================

    @Test
    fun `test lambda returning interpolated string`() {
        assertTypeChecks("""
            let makeGreeting = {name: Str} -> "Hello, $(name)!"
            let greet = makeGreeting {name = "World"}
            greet
        """.trimIndent())
    }

    @Test
    fun `test higher-order function with interpolation`() {
        assertTypeChecks("""
            let apply = {f: {x: Str} -> Str, x: Str} -> f {x = x}
            let wrapper = {x: Str} -> "[$(x)]"
            let result = apply {f = wrapper, x = "content"}
            result
        """.trimIndent())
    }

    // ==================== Type Aliases with String Interpolation ====================

    @Test
    fun `test type alias record used in interpolation`() {
        assertTypeChecks("""
            type Person = { name: Str, title: Str }

            let greet = {person: Person} -> "Hello $(person.name), $(person.title)"
            greet {person = {name = "Alice", title = "Engineer"}}
        """.trimIndent())
    }

    // ==================== Polymorphic Functions with String Interpolation ====================

    @Test
    fun `test polymorphic function body using interpolation`() {
        assertTypeChecks("""
            let wrap: {prefix: Str, suffix: Str, middle: Str} -> Str =
                {prefix, suffix, middle} -> "$(prefix)$(middle)$(suffix)"

            wrap {prefix = "<", suffix = ">", middle = "content"}
        """.trimIndent())
    }

    @Test
    fun `test constrained polymorphic with interpolation and Inspect`() {
        assertTypeChecks("""
            let debug: {label: Str, val: 'a} -> Str with Inspect 'a = {label, val} ->
                "$(label): $(val.to_dbg_str {})"

            debug {label = "number", val = 42}
        """.trimIndent())
    }

    // ==================== Binary Operations with String Interpolation ====================

    @Test
    fun `test arithmetic result converted to string in interpolation`() {
        assertTypeChecks("""
            let x = 10
            let y = 5
            let sum = x + y
            let result = "Sum: $(int::to_str {n = sum})"
            result
        """.trimIndent())
    }

    @Test
    fun `test boolean result used with conditional in interpolation`() {
        assertTypeChecks("""
            let a = 10
            let b = 5
            let result = "$(if a > b then "a is greater" else "b is greater or equal")"
            result
        """.trimIndent())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `test empty interpolation parts`() {
        assertTypeChecks("""
            let a = "X"
            let b = "Y"
            let result = "$(a)$(b)"
            result
        """.trimIndent())
    }

    @Test
    fun `test only interpolation no literal text`() {
        assertTypeChecks("""
            let content = "hello"
            let result = "$(content)"
            result
        """.trimIndent())
    }

    @Test
    fun `test escaped dollar sign not interpolated`() {
        assertTypeChecks("""
            let result = "Price: \$100"
            result
        """.trimIndent())
    }

    @Test
    fun `test deeply nested string interpolation`() {
        assertTypeChecks("""
            let a = "A"
            let b = "$(a)B"
            let c = "$(b)C"
            let d = "$(c)D"
            d
        """.trimIndent())
    }

    @Test
    fun `test string interpolation inside nested string literal`() {
        assertTypeChecks("""
            let name = "World"
            let result = "outer: $("inner $(name)")"
            result
        """.trimIndent())
    }

    // ==================== Error Cases ====================

    @Test
    fun `test Int in interpolation fails`() {
        assertTypeCheckFails("""
            let x = 42
            let msg = "Value: $(x)"
            msg
        """.trimIndent())
    }

    @Test
    fun `test Bool in interpolation fails`() {
        assertTypeCheckFails("""
            let flag = True
            let msg = "Flag: $(flag)"
            msg
        """.trimIndent())
    }

    @Test
    fun `test Record in interpolation fails`() {
        assertTypeCheckFails("""
            let person = {name = "Alice"}
            let msg = "Person: $(person)"
            msg
        """.trimIndent())
    }

    @Test
    fun `test Variant in interpolation fails`() {
        assertTypeCheckFails("""
            type Status = | Active {}, Inactive {} |
            let s = Active {}
            let msg = "Status: $(s)"
            msg
        """.trimIndent())
    }

    @Test
    fun `test function type in interpolation fails`() {
        assertTypeCheckFails("""
            let f = {x: Int} -> x
            let msg = "Function: $(f)"
            msg
        """.trimIndent())
    }

    // ==================== Suspended/Effectful String Types ====================

    @Test
    fun `test effectful string in interpolation`() {
        assertTypeChecks("""
            effect FileSystem where {
                readFile : {path: Str} -> Str <FileSystem>
            }

            impl FileSystem for MockFS where {
                readFile : {path: Str} -> Str = {path} -> "content"
            }

            let getContent: {path: Str} -> Str <FileSystem> = {path} ->
                "File: $(FileSystem.readFile {path = path})"

            perform MockFS in getContent {path = "/test"}
        """.trimIndent())
    }

    // ==================== Integration Tests ====================

    @Test
    fun `test complex scenario with abilities effects and pattern matching`() {
        assertTypeChecks("""
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            type Result = | Ok { value: Int }, Err { message: Str } |

            effect Log where {
                print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print : {msg: Str} -> {} = {msg} -> {}
            }

            let processResult: {r: Result} -> Str = {r} -> match r is
                Ok { value } -> "Success: $(value.show {})"
                Err { message } -> "Error: $(message)"

            let logAndProcess: {r: Result} -> Str <Log> = {r} -> do
                let msg = processResult {r = r}
                Log.print {msg = "Processing: $(msg)"}
                msg

            perform Console in logAndProcess {r = Ok {value = 42}}
        """.trimIndent())
    }

    @Test
    fun `test multiple ability constraints with interpolation in lambda chain`() {
        assertTypeChecks("""
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            impl Show for Str where {
                show = {} -> "\"$(it)\""
            }

            let showPair: {a: 'x, b: 'y} -> Str with Show 'x, Show 'y = {a, b} ->
                "($(a.show {}), $(b.show {}))"

            let formatPairs: {p1a: 'x, p1b: 'y, p2a: 'x, p2b: 'y} -> Str with Show 'x, Show 'y = {p1a, p1b, p2a, p2b} ->
                "Pair1: $(showPair {a = p1a, b = p1b}), Pair2: $(showPair {a = p2a, b = p2b})"

            formatPairs {p1a = 1, p1b = "hello", p2a = 2, p2b = "world"}
        """.trimIndent())
    }
}
