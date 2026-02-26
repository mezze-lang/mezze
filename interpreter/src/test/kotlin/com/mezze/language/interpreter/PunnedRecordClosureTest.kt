package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.ModuleResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for punned record syntax in closures, especially in effect implementations.
 *
 * Punned syntax like {path} means {path = path}, where the field name is also
 * a variable reference. This must be properly captured in closures.
 *
 * These tests prevent regression of the bug where performer parameters weren't
 * captured when used with punned syntax in impl bodies.
 */
class PunnedRecordClosureTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
    }

    private fun runProgram(source: String): MezzeValue {
        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        return interpreter.execute(typedProgram)
    }

    // ========================================================================
    // BASIC PUNNED SYNTAX TESTS
    // ========================================================================

    @Test
    fun `test punned record in simple function`() {
        val source = """
            let makeRecord = {x, y} -> {x, y}
            let result = makeRecord {x = 1, y = 2}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(1), record.fields["x"])
        assertEquals(MezzeInt(2), record.fields["y"])
    }

    @Test
    fun `test punned record in nested lambda`() {
        val source = """
            let outer = {x} -> do
                let inner = {} -> {x}
                inner {}
            let result = outer {x = 42}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(42), record.fields["x"])
    }

    @Test
    fun `test punned record captures outer scope variable`() {
        val source = """
            let x = 10
            let makeRecord = {y} -> {x, y}
            let result = makeRecord {y = 20}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(10), record.fields["x"])
        assertEquals(MezzeInt(20), record.fields["y"])
    }

    @Test
    fun `test mixed punned and explicit fields`() {
        val source = """
            let x = 1
            let makeRecord = {y} -> {x, y, z = x + y}
            let result = makeRecord {y = 2}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(1), record.fields["x"])
        assertEquals(MezzeInt(2), record.fields["y"])
        assertEquals(MezzeInt(3), record.fields["z"])
    }

    // ========================================================================
    // EFFECT IMPL WITH PUNNED SYNTAX TESTS
    // ========================================================================

    @Test
    fun `test effect impl with punned performer parameter`() {
        val source = """
            effect Store where {
                get: {} -> Int <Store>,
                set: {value: Int} -> {} <Store>
            }

            impl Store for ConstStore {value: Int} where {
                get = {} -> value,
                set = {value} -> {}
            }

            let runTest = {} ->
                perform ConstStore {value = 42} in
                Store.get {}
        """.trimIndent()

        val result = runProgram(source)
        // runTest is a function - just verify it compiled and the impl has correct structure
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction,
            "Expected a function, got: ${result::class.simpleName}")
    }

    @Test
    fun `test effect impl with multiple punned parameters`() {
        val source = """
            effect Math where {
                compute: {} -> Int <Math>
            }

            impl Math for Adder {a: Int, b: Int} where {
                compute = {} -> a + b
            }

            let runTest = {} ->
                perform Adder {a = 10, b = 20} in
                Math.compute {}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test effect impl with punned parameter used in nested expression`() {
        val source = """
            effect Multiplier where {
                multiply: {x: Int} -> Int <Multiplier>
            }

            impl Multiplier for FactorMultiplier {factor: Int} where {
                multiply = {x} -> x * factor
            }

            let runTest = {} ->
                perform FactorMultiplier {factor = 5} in
                Multiplier.multiply {x = 7}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    // ========================================================================
    // COMPLEX CLOSURE CAPTURE TESTS
    // ========================================================================

    @Test
    fun `test deeply nested punned records`() {
        val source = """
            let outer = {a} -> do
                let middle = {b} -> do
                    let inner = {c} -> {a, b, c}
                    inner {c = 3}
                middle {b = 2}
            let result = outer {a = 1}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(1), record.fields["a"])
        assertEquals(MezzeInt(2), record.fields["b"])
        assertEquals(MezzeInt(3), record.fields["c"])
    }

    @Test
    fun `test punned record in function passed to higher order function`() {
        val source = """
            let apply = {f, x} -> f {arg = x}
            let capture = {y} -> do
                let inner = {arg} -> {y, result = arg + y}
                inner
            let makeFunc = capture {y = 10}
            let result = apply {f = makeFunc, x = 5}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(10), record.fields["y"])
        assertEquals(MezzeInt(15), record.fields["result"])
    }

    @Test
    fun `test punned record with shadowed variable`() {
        val source = """
            let x = 100
            let outer = {x} -> do
                let inner = {} -> {x}
                inner {}
            let result = outer {x = 42}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        // Should use the parameter x (42), not the outer x (100)
        assertEquals(MezzeInt(42), record.fields["x"])
    }

    // ========================================================================
    // EFFECT IMPL WITH COMPLEX BODIES
    // ========================================================================

    @Test
    fun `test effect impl with do block using punned params`() {
        val source = """
            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Counter for StartCounter {start: Int, step: Int} where {
                increment = {} -> do
                    let result = start + step
                    result
            }

            let runTest = {} ->
                perform StartCounter {start = 10, step = 5} in
                Counter.increment {}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test effect impl with conditional using punned params`() {
        val source = """
            effect Checker where {
                check: {value: Int} -> Bool <Checker>
            }

            impl Checker for ThresholdChecker {threshold: Int} where {
                check = {value} -> if value > threshold then True else False
            }

            let runTest = {} ->
                perform ThresholdChecker {threshold = 50} in do
                let r1 = Checker.check {value = 60}
                let r2 = Checker.check {value = 40}
                {above = r1, below = r2}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test effect impl with match expression using punned params`() {
        val source = """
            type Status = | Active, Inactive, Pending |

            effect StatusChecker where {
                getStatus: {} -> Status <StatusChecker>
            }

            impl StatusChecker for FixedStatus {status: Status} where {
                getStatus = {} -> status
            }

            let runTest = {} ->
                perform FixedStatus {status = Active} in
                match StatusChecker.getStatus {} is
                    Active -> 1
                    Inactive -> 2
                    Pending -> 3
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    // ========================================================================
    // REGRESSION TESTS FOR SPECIFIC BUGS
    // ========================================================================

    @Test
    fun `test FileLogger pattern - punned path in record passed to effect op`() {
        // This is the exact pattern that was failing:
        // impl Log for FileLogger {path: String} where {
        //     info = { msg } -> FileSystem.write_file {path, content = msg}
        // }
        val source = """
            effect Writer where {
                write: {path: String, content: String} -> {} <Writer>
            }

            effect Logger where {
                log: {msg: String} -> {} <Logger>
            }

            impl Writer for MemoryWriter where {
                write = {path, content} -> {}
            }

            impl Logger for PathLogger {path: String} where {
                log = {msg} -> Writer.write {path, content = msg}
            }

            let runTest = {} ->
                perform MemoryWriter, PathLogger {path = "/var/log/app.log"} in
                Logger.log {msg = "Hello"}
        """.trimIndent()

        val result = runProgram(source)
        // Should compile without "Undefined variable 'path'" error
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test multiple performer params all captured via punning`() {
        val source = """
            effect Formatter where {
                format: {value: Int} -> String <Formatter>
            }

            impl Formatter for PrefixSuffixFormatter {prefix: String, suffix: String} where {
                format = {value} -> do
                    let mid = Inspect.to_str {val = value}
                    "$(prefix)$(mid)$(suffix)"
            }

            let runTest = {} ->
                perform PrefixSuffixFormatter {prefix = "[", suffix = "]"} in
                Formatter.format {value = 42}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test punned param used multiple times in impl body`() {
        val source = """
            effect Repeater where {
                repeat: {} -> String <Repeater>
            }

            impl Repeater for DoubleRepeater {text: String} where {
                repeat = {} -> "$(text)$(text)"
            }

            let runTest = {} ->
                perform DoubleRepeater {text = "ab"} in
                Repeater.repeat {}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test punned string param with interpolation in impl body`() {
        val source = """
            effect Greeter where {
                greet: {name: String} -> String <Greeter>
            }

            impl Greeter for PrefixGreeter {prefix: String} where {
                greet = {name} -> "$(prefix) $(name)!"
            }

            let runTest = {} ->
                perform PrefixGreeter {prefix = "Hello"} in
                Greeter.greet {name = "World"}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    @Test
    fun `test nested effects with punned params`() {
        val source = """
            effect Inner where {
                getValue: {} -> Int <Inner>
            }

            effect Outer where {
                compute: {} -> Int <Outer>
            }

            impl Inner for InnerImpl {value: Int} where {
                getValue = {} -> value
            }

            impl Outer for OuterImpl {multiplier: Int} where {
                compute = {} -> do
                    let v = Inner.getValue {}
                    v * multiplier
            }

            let runTest = {} ->
                perform InnerImpl {value = 10}, OuterImpl {multiplier = 3} in
                Outer.compute {}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeTruffleFunction || result is NativeMezzeFunction)
    }

    // ========================================================================
    // PURE CLOSURE TESTS (no effects - can test actual values)
    // ========================================================================

    @Test
    fun `test pure function with punned closure variable`() {
        val source = """
            let multiplier = 10
            let scale = {x} -> {scaled = x * multiplier, multiplier}
            let result = scale {x = 5}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(50), record.fields["scaled"])
        assertEquals(MezzeInt(10), record.fields["multiplier"])
    }

    @Test
    fun `test pure nested function with punned params`() {
        val source = """
            let createAdder = {base} -> do
                let add = {x} -> {sum = x + base, base}
                add
            let addTen = createAdder {base = 10}
            let result = addTen {x = 5}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(15), record.fields["sum"])
        assertEquals(MezzeInt(10), record.fields["base"])
    }

    @Test
    fun `test multiple punned variables from different scopes`() {
        val source = """
            let a = 1
            let outer = {b} -> do
                let middle = {c} -> do
                    let inner = {} -> {a, b, c}
                    inner {}
                middle {c = 3}
            let result = outer {b = 2}
        """.trimIndent()

        val result = runProgram(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(MezzeInt(1), record.fields["a"])
        assertEquals(MezzeInt(2), record.fields["b"])
        assertEquals(MezzeInt(3), record.fields["c"])
    }
}
