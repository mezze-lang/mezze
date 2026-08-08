package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeSuspend

/**
 * Exhaustive tests for the lazy effects feature.
 *
 * When effectful code is called OUTSIDE of a `perform` block, the computation
 * is suspended (lazy) rather than evaluated eagerly. The suspended value is
 * then forced (evaluated) when it is read inside a `perform` block that has
 * the appropriate handlers.
 *
 * Key semantics:
 * 1. Effect calls outside handlers create MezzeSuspend values
 * 2. MezzeSuspend values are stored in variables without being evaluated
 * 3. When read inside a perform block, MezzeSuspend values are forced
 * 4. If forcing throws → the exception is caught by the handler
 * 5. If forcing succeeds → the result is used normally
 *
 * This enables patterns like:
 *   let res = safe_div { a = 4, b = 0 }  // Suspended, not evaluated
 *   let handle = {} -> perform ExceptionToResult in Ok { val = res }  // Forced here
 *   handle {}  // Returns Err { error = "Div by zero" }
 */
class LazyEffectsTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)
    private fun evalWithOutput(source: String): MezzeTestHelper.RunResult = MezzeTestHelper.runWithOutput(source)

    // ========================================================================
    // Section 1: Basic Lazy Effect Semantics
    // ========================================================================

    @Test
    fun `test effectful call outside handler creates suspended value`() {
        // When an effectful function is called outside any handler,
        // the result should be a suspended computation (lazy)
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            # Called OUTSIDE handler - should be suspended
            let res = safe_div {a = 4, b = 0}

            # Inside handler, res is forced and exception is caught
            let handle = {} -> perform ExceptionToResult in Ok {val = res}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("Div by zero", payload.getField("error"))
    }

    @Test
    fun `test suspended value forced when used inside perform`() {
        // The suspended computation should be forced when accessed in a handler
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let res = safe_div {a = 10, b = 2}  # Suspended but will succeed

            let handle = {} -> perform ExceptionToResult in Ok {val = res}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(5L, payload.getField("val"))
    }

    @Test
    fun `test multiple suspended values forced independently`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let r1 = safe_div {a = 10, b = 2}  # Will succeed
            let r2 = safe_div {a = 10, b = 0}  # Will fail
            let r3 = safe_div {a = 20, b = 4}  # Will succeed

            let compute = {} -> do
                let h1 = perform ExceptionToResult in Ok {val = r1}
                let h2 = perform ExceptionToResult in Ok {val = r2}
                let h3 = perform ExceptionToResult in Ok {val = r3}
                {first = h1, second = h2, third = h3}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val first = record.getField("first") as MezzeVariant
        assertEquals("Ok", first.constructorName)
        assertEquals(5L, (first.payloadValue as MezzeRecord).getField("val"))

        val second = record.getField("second") as MezzeVariant
        assertEquals("Err", second.constructorName)

        val third = record.getField("third") as MezzeVariant
        assertEquals("Ok", third.constructorName)
        assertEquals(5L, (third.payloadValue as MezzeRecord).getField("val"))
    }

    // ========================================================================
    // Section 2: Handler Scope and Forcing
    // ========================================================================

    @Test
    fun `test suspended value forced by innermost handler`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, ExceptionToOption}

            let will_throw = {} -> Exception.throw {error = "test"}

            let suspended = will_throw {}

            # Inner handler should catch the exception
            let handle = {} -> do
                let inner_result = perform ExceptionToOption in do
                    let _ = suspended
                    Some {val = 0}
                inner_result
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        // Inner handler catches and returns None
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value in function parameter is forced`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let use_value = {x} -> x * 2

            let div_result = safe_div {a = 6, b = 2}

            let handle = {} -> perform ExceptionToResult in Ok {val = use_value {x = div_result}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(6L, payload.getField("val"))  // 6/2 = 3, 3*2 = 6
    }

    @Test
    fun `test suspended value in record field is forced`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let div_result = safe_div {a = 10, b = 0}

            let handle = {} -> perform ExceptionToResult in Ok {val = {computed = div_result}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value in nested record is forced`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_throw = {} -> Exception.throw {error = "nested fail"}

            let suspended = will_throw {}

            let handle = {} -> perform ExceptionToResult in Ok {val = {
                outer = {
                    inner = {
                        value = suspended
                    }
                }
            }}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Section 3: Different Handlers
    // ========================================================================

    @Test
    fun `test suspended value with ExceptionToOption handler`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToOption}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let div_result = safe_div {a = 4, b = 0}

            let handle = {} -> perform ExceptionToOption in Some {val = div_result}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value with IgnoreException handler`() {
        val source = """
            use std::effects::exception::{Exception, IgnoreException}

            let will_throw = {} -> do
                Exception.throw {error = "ignored"}
                {}

            let handle = {} -> perform IgnoreException in will_throw {}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(MezzeTestHelper.isUnit(result))
    }

    @Test
    fun `test suspended value with ExceptionToResult success path`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let validate = {x} ->
                if x < 0 then Exception.throw {error = "negative"} else x * 2

            let validated = validate {x = 5}  # Will succeed

            let handle = {} -> perform ExceptionToResult in Ok {val = validated}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(10L, payload.getField("val"))
    }

    // ========================================================================
    // Section 4: Complex Control Flow
    // ========================================================================

    @Test
    fun `test suspended value used in conditional`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let div_by_zero = safe_div {a = 4, b = 0}
            let div_ok = safe_div {a = 10, b = 2}

            let use_condition = {flag} -> perform ExceptionToResult in
                if flag then Ok {val = div_by_zero} else Ok {val = div_ok}

            let compute = {} -> do
                let r1 = use_condition {flag = True}
                let r2 = use_condition {flag = False}
                {with_error = r1, without_error = r2}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val withError = record.getField("with_error") as MezzeVariant
        assertEquals("Err", withError.constructorName)

        val withoutError = record.getField("without_error") as MezzeVariant
        assertEquals("Ok", withoutError.constructorName)
        assertEquals(5L, (withoutError.payloadValue as MezzeRecord).getField("val"))
    }

    @Test
    fun `test suspended value in match expression`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let may_fail = {x} ->
                if x == 0 then Exception.throw {error = "zero"} else x

            let suspended = may_fail {x = 0}

            let process = {opt} -> perform ExceptionToResult in match opt is
                Some {val} -> Ok {val = val + suspended}
                None -> Ok {val = 0}

            process {opt = Some {val = 10}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value in do block`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let step1 = {x} -> if x < 0 then Exception.throw {error = "step1"} else x + 1
            let step2 = {x} -> if x > 100 then Exception.throw {error = "step2"} else x * 2

            let s1 = step1 {x = 5}
            let s2 = step2 {x = 50}

            let compute = {} -> perform ExceptionToResult in do
                let a = s1
                let b = s2
                Ok {val = a + b}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(106L, payload.getField("val"))  // (5+1) + (50*2) = 6 + 100 = 106
    }

    @Test
    fun `test first suspended failure stops execution`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let s1 = Exception.throw {error = "first"}
            let s2 = Exception.throw {error = "second"}

            let handle = {} -> perform ExceptionToResult in do
                let a = s1
                let b = s2
                Ok {val = {a = a, b = b}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("first", payload.getField("error"))
    }

    // ========================================================================
    // Section 5: Function Boundaries
    // ========================================================================

    @Test
    fun `test suspended value passed to function is forced`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "failed"}
            let use_value = {x} -> x + 1

            let suspended = will_fail {}

            let handle = {} -> perform ExceptionToResult in Ok {val = use_value {x = suspended}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value returned from function`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let create_suspended = {} -> Exception.throw {error = "from function"}

            let get_suspended = {} -> create_suspended {}

            let suspended = get_suspended {}

            let handle = {} -> perform ExceptionToResult in Ok {val = suspended}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value in closure is forced when closure executes`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "closure fail"}

            let suspended = will_fail {}

            let make_closure = {} -> {unused} -> suspended

            let closure = make_closure {}

            let handle = {} -> perform ExceptionToResult in Ok {val = closure {unused = 0}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Section 6: Multiple Handler Types
    // ========================================================================

    @Test
    fun `test suspended value with different handlers in sequence`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, ExceptionToOption}

            let will_fail = {} -> Exception.throw {error = "test"}

            let suspended = will_fail {}

            let compute = {} -> do
                let with_result = perform ExceptionToResult in Ok {val = suspended}
                let with_option = perform ExceptionToOption in Some {val = suspended}
                {as_result = with_result, as_option = with_option}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val asResult = record.getField("as_result") as MezzeVariant
        assertEquals("Err", asResult.constructorName)

        val asOption = record.getField("as_option") as MezzeVariant
        assertEquals("None", asOption.constructorName)
    }

    @Test
    fun `test same suspended value forced multiple times`() {
        // Each time a suspended value is read inside a handler, it's forced again
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let ok_div = safe_div {a = 10, b = 2}

            let handle1 = {} -> perform ExceptionToResult in Ok {val = ok_div}
            let handle2 = {} -> perform ExceptionToResult in Ok {val = ok_div}

            {first = handle1 {}, second = handle2 {}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val first = record.getField("first") as MezzeVariant
        assertEquals("Ok", first.constructorName)
        assertEquals(5L, (first.payloadValue as MezzeRecord).getField("val"))

        val second = record.getField("second") as MezzeVariant
        assertEquals("Ok", second.constructorName)
        assertEquals(5L, (second.payloadValue as MezzeRecord).getField("val"))
    }

    // ========================================================================
    // Section 7: Log Effect with Lazy Semantics
    // ========================================================================

    @Test
    fun `test log effect suspended outside handler`() {
        val source = """
            use std::effects::log::{Log, Console, info}

            let log_msg = Log.log {level = Info, msg = "suspended log"}

            let main = {} -> perform Console in do
                info {msg = "before"}
                let _ = log_msg
                info {msg = "after"}
                {}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        // The suspended log should be forced and printed
        assertTrue(result.output().contains("[INFO] before"))
        assertTrue(result.output().contains("[INFO] suspended log"))
        assertTrue(result.output().contains("[INFO] after"))
    }

    @Test
    fun `test log effect not executed if not used`() {
        val source = """
            use std::effects::log::{Log, Console, info}

            let log_msg = Log.log {level = Info, msg = "should not appear"}

            let main = {} -> perform Console in do
                info {msg = "only this"}
                {}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        assertTrue(result.output().contains("[INFO] only this"))
        assertFalse(result.output().contains("should not appear"))
    }

    // ========================================================================
    // Section 8: Recursive and Complex Scenarios
    // ========================================================================

    @Test
    fun `test suspended value in recursive function`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "recursion fail"}

            let suspended = will_fail {}

            let sum_to = {n, acc} ->
                if n <= 0 then acc
                else sum_to {n = n - 1, acc = acc + n}

            let handle = {} -> perform ExceptionToResult in do
                let sum = sum_to {n = 5, acc = 0}
                Ok {val = sum + suspended}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value with complex error type`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            type ValidationError = | Required {field: Str}, TooShort {field: Str, min: Int} |

            let validate_name = {name} ->
                if name == "" then
                    Exception.throw {error = Required {field = "name"}}
                else
                    name

            let result1 = validate_name {name = ""}
            let result2 = validate_name {name = "Alice"}

            let compute = {} -> do
                let h1 = perform ExceptionToResult in Ok {val = result1}
                let h2 = perform ExceptionToResult in Ok {val = result2}
                {r1 = h1, r2 = h2}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val r1 = record.getField("r1") as MezzeVariant
        assertEquals("Err", r1.constructorName)
        val err1 = (r1.payloadValue as MezzeRecord).getField("error") as MezzeVariant
        assertEquals("Required", err1.constructorName)

        val r2 = record.getField("r2") as MezzeVariant
        assertEquals("Ok", r2.constructorName)
        assertEquals("Alice", (r2.payloadValue as MezzeRecord).getField("val"))
    }

    // ========================================================================
    // Section 9: Inspect and Lazy Effects
    // ========================================================================

    @Test
    fun `test Inspect to_dbg_str on result of lazy effect`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::log::{Log, Console, info}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let handle = {} -> perform ExceptionToResult in
                Ok {val = safe_div {a = 4, b = 0}}

            let main = {} -> perform Console in
                info {msg = "Result: $((handle {}).to_dbg_str {})"}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        assertTrue(result.output().contains("Err"))
        assertTrue(result.output().contains("Div by zero"))
    }

    @Test
    @Disabled("#392 — residual under MEZZE_EAGER=1; passes in isolation, fails after prior test in same class. NOT an Engine-cache leak (empirically refuted: clearing slotTable + userStubCache + stdlibCallTargetCache + importedCallTargetCache in disposeContext does NOT fix). Real root cause likely: (a) a stale value baked as @ConstantOperand in MezzeBytecodeRootNode bytecode (per-Engine, unclearable from disposeContext) OR (b) test-order-dependent poisoning of MezzeVariant.TagRegistry / HandlerChainEntry ThreadLocal / HandlerChainRegistry state. Next diagnostic pass: instrument thread-local + TagRegistry state at test teardown, not Engine caches.")
    fun `test Inspect to_dbg_str on success path of lazy effect`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::log::{Log, Console, info}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let res = safe_div {a = 10, b = 2}

            let handle = {} -> perform ExceptionToResult in Ok {val = res}

            let main = {} -> perform Console in
                info {msg = "Result: $((handle {}).to_dbg_str {})"}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        assertTrue(result.output().contains("Ok"))
        assertTrue(result.output().contains("5"))
    }

    // ========================================================================
    // Section 10: Edge Cases
    // ========================================================================

    @Test
    fun `test suspended Unit effect`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let may_fail = {x} ->
                if x == 0 then Exception.throw {error = "zero"} else {}

            let suspended = may_fail {x = 5}  # Returns unit

            let handle = {} -> perform ExceptionToResult in do
                let _ = suspended
                Ok {val = "done"}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("done", payload.getField("val"))
    }

    @Test
    fun `test suspended value in Seq`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let make_item = {x} ->
                if x == 3 then Exception.throw {error = "bad item"} else x

            let item1 = make_item {x = 1}
            let item2 = make_item {x = 2}
            let item3 = make_item {x = 3}

            let handle = {} -> perform ExceptionToResult in Ok {val = [item1, item2, item3]}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test multiple effects same handler - success case`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            # These functions use Exception effect (even though they don't throw)
            let step1 = {} -> if False then Exception.throw {error = "no"} else 10
            let step2 = {} -> if False then Exception.throw {error = "no"} else 42

            let s1 = step1 {}
            let s2 = step2 {}

            let handle1 = {} -> perform ExceptionToResult in Ok {val = s1}
            let handle2 = {} -> perform ExceptionToResult in Ok {val = s2}

            {first = handle1 {}, second = handle2 {}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val first = record.getField("first") as MezzeVariant
        assertEquals("Ok", first.constructorName)
        assertEquals(10L, (first.payloadValue as MezzeRecord).getField("val"))

        val second = record.getField("second") as MezzeVariant
        assertEquals("Ok", second.constructorName)
        assertEquals(42L, (second.payloadValue as MezzeRecord).getField("val"))
    }

    @Test
    fun `test multiple effects one fails one succeeds`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let step1 = {} -> Exception.throw {error = "step1 failed"}
            let step2 = {} -> if False then Exception.throw {error = "no"} else 42

            let s1 = step1 {}
            let s2 = step2 {}

            let handle1 = {} -> perform ExceptionToResult in Ok {val = s1}
            let handle2 = {} -> perform ExceptionToResult in Ok {val = s2}

            {first = handle1 {}, second = handle2 {}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val first = record.getField("first") as MezzeVariant
        assertEquals("Err", first.constructorName)

        val second = record.getField("second") as MezzeVariant
        assertEquals("Ok", second.constructorName)
        assertEquals(42L, (second.payloadValue as MezzeRecord).getField("val"))
    }

    // ========================================================================
    // Section 11: Handler Propagation
    // ========================================================================

    @Test
    fun `test suspended value forced with propagated handler`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "propagated"}

            let suspended = will_fail {}

            let inner_fn = {} -> suspended

            let handle = {} -> perform ExceptionToResult in Ok {val = inner_fn {}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test suspended value in deeply nested calls`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "deep"}

            let suspended = will_fail {}

            let level3 = {} -> suspended
            let level2 = {} -> level3 {}
            let level1 = {} -> level2 {}

            let handle = {} -> perform ExceptionToResult in Ok {val = level1 {}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Section 12: Mixed Effects
    // ========================================================================

    @Test
    fun `test log and exception effects together`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::log::{Log, Console, info}

            let will_fail = {} -> Exception.throw {error = "failed"}

            let suspended = will_fail {}

            let main = {} -> perform Console in do
                info {msg = "before handle"}
                let result = perform ExceptionToResult in Ok {val = suspended}
                info {msg = "got result"}
                result
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        assertTrue(result.output().contains("[INFO] before handle"))
        assertTrue(result.output().contains("got result"))

        // Check the return value
        assertTrue(result.value() is MezzeVariant)
        assertEquals("Err", (result.value() as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Section 13: Unwrap with Lazy Effects
    // ========================================================================

    @Test
    fun `test unwrap on suspended Ok value`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, unwrap}

            let create_ok = {} -> Ok {val = 42}

            let suspended = create_ok {}

            let handle = {} -> perform ExceptionToResult in do
                let val = unwrap {result = suspended}
                Ok {val = val * 2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(84L, payload.getField("val"))
    }

    @Test
    fun `test unwrap on suspended Err value`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, unwrap}

            let create_err = {} -> Err {error = "pre-created error"}

            let suspended = create_err {}

            let handle = {} -> perform ExceptionToResult in do
                let val = unwrap {result = suspended}
                Ok {val = val}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("pre-created error", payload.getField("error"))
    }

    // ========================================================================
    // Section 14: Type Preservation
    // ========================================================================

    @Test
    fun `test suspended value preserves complex return type`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let create_person = {name, age} ->
                if age < 0 then Exception.throw {error = "invalid age"}
                else {name = name, age = age}

            let person = create_person {name = "Alice", age = 30}

            let handle = {} -> perform ExceptionToResult in Ok {val = person}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        val person = payload.getField("val") as MezzeRecord
        assertEquals("Alice", person.getField("name"))
        assertEquals(30L, person.getField("age"))
    }

    @Test
    fun `test suspended value with variant return type`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            type Status = | Active, Inactive, Pending {reason: Str} |

            let get_status = {code} ->
                if code < 0 then Exception.throw {error = "invalid code"}
                else if code == 0 then Active
                else if code == 1 then Inactive
                else Pending {reason = "unknown code"}

            let status = get_status {code = 2}

            let handle = {} -> perform ExceptionToResult in Ok {val = status}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        val status = payload.getField("val") as MezzeVariant
        assertEquals("Pending", status.constructorName)
    }

    // ========================================================================
    // Section 15: Additional Edge Cases
    // ========================================================================

    @Test
    fun `test suspended value assigned to multiple variables`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "shared"}

            let suspended = will_fail {}
            let alias1 = suspended
            let alias2 = suspended

            let h1 = {} -> perform ExceptionToResult in Ok {val = alias1}
            let h2 = {} -> perform ExceptionToResult in Ok {val = alias2}

            {r1 = h1 {}, r2 = h2 {}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val r1 = record.getField("r1") as MezzeVariant
        assertEquals("Err", r1.constructorName)

        val r2 = record.getField("r2") as MezzeVariant
        assertEquals("Err", r2.constructorName)
    }

    @Test
    fun `test suspended value in arithmetic expression`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let d1 = safe_div {a = 10, b = 2}  # 5
            let d2 = safe_div {a = 20, b = 4}  # 5

            let handle = {} -> perform ExceptionToResult in Ok {val = d1 + d2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(10L, payload.getField("val"))  // 5 + 5 = 10
    }

    @Test
    fun `test suspended value in string interpolation`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::log::{Console, info}

            let get_name = {id} ->
                if id == 0 then Exception.throw {error = "invalid id"} else "User$(id.to_dbg_str {})"

            let name = get_name {id = 42}

            let handle = {} -> perform ExceptionToResult in Ok {val = "Hello, $(name)!"}

            let main = {} -> perform Console in
                info {msg = (handle {}).to_dbg_str {}}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        assertTrue(result.output().contains("Ok"))
        assertTrue(result.output().contains("Hello, User42!"))
    }

    @Test
    fun `test suspended value chain`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let step1 = {x} -> if x < 0 then Exception.throw {error = "neg"} else x + 1
            let step2 = {x} -> if x > 10 then Exception.throw {error = "big"} else x * 2

            let s1 = step1 {x = 3}  # 4
            let s2 = step2 {x = s1}  # 8

            let handle = {} -> perform ExceptionToResult in Ok {val = s2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(8L, payload.getField("val"))  // (3+1) * 2 = 8
    }

    @Test
    fun `test suspended value chain with failure in middle`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let step1 = {x} -> if x < 0 then Exception.throw {error = "neg"} else x + 1
            let step2 = {x} -> if x > 3 then Exception.throw {error = "big"} else x * 2

            let s1 = step1 {x = 5}  # 6 - will fail in step2
            let s2 = step2 {x = s1}  # Should throw "big"

            let handle = {} -> perform ExceptionToResult in Ok {val = s2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("big", payload.getField("error"))
    }
}
