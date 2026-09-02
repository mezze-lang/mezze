package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for effectful calls handled by `perform`, under Mezze's eager effect model.
 *
 * Effects are eager and attach to arrows. An effectful call's effect is raised to
 * the enclosing function's arrow, and the call itself evaluates to a plain bare
 * value (e.g. `Int`, not a suspended `Int <Exception>`). There is no first-class
 * "suspended computation" that you bind outside a handler and force later.
 *
 * `perform H in <body>` handles the effects performed *inside its body*. It does
 * NOT reach back and force a value that was already computed elsewhere. So the
 * effectful call must appear textually within the perform body: the body then
 * statically performs the handled effect. If it does not, the type checker rejects
 * the program with "the `perform` body performs no effect — so the handler can
 * never fire." This is why the old lazy pattern no longer works:
 *
 *   // REJECTED — `res` is a plain value; the perform body performs nothing:
 *   let res = safe_div {a = 4, b = 0}
 *   let handle = {} -> perform ExceptionToResult in Ok {val = res}
 *
 *   // CORRECT — the throw happens inside the handler:
 *   let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 4, b = 0}}
 *   handle {}   // Err {error = "Div by zero"}
 *
 * The `ExceptionToResult` / `ExceptionToOption` handlers do not auto-wrap: the body
 * itself produces `Ok {...}` / `Some {...}` on the success path, and on `throw` the
 * handler aborts the perform, yielding `Err {error}` / `None`. Migrating a test by
 * moving the effectful call into the perform body preserves its runtime result.
 *
 * A few tests keep "suspended"/"lazy" in their names for historical continuity; the
 * behavior they now exercise is the eager model described above.
 */
class LazyEffectsTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)
    private fun evalWithOutput(source: String): MezzeTestHelper.RunResult = MezzeTestHelper.runWithOutput(source)

    // ========================================================================
    // Section 1: Basic Lazy Effect Semantics
    // ========================================================================

    @Test
    fun `test effectful call outside handler creates suspended value`() {
        // Eager model: the effectful call must sit inside the perform body so the
        // body performs the Exception effect the handler catches.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            # Effectful call INSIDE the handler — the throw is caught here.
            let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 4, b = 0}}
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
        // The effectful call is performed inside the handler; success path yields Ok.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 10, b = 2}}
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

            let compute = {} -> do
                let h1 = perform ExceptionToResult in Ok {val = safe_div {a = 10, b = 2}}  # succeeds
                let h2 = perform ExceptionToResult in Ok {val = safe_div {a = 10, b = 0}}  # fails
                let h3 = perform ExceptionToResult in Ok {val = safe_div {a = 20, b = 4}}  # succeeds
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

            # Inner handler should catch the exception performed in its body
            let handle = {} -> do
                let inner_result = perform ExceptionToOption in do
                    will_throw {}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = use_value {x = safe_div {a = 6, b = 2}}}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = {computed = safe_div {a = 10, b = 0}}}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = {
                outer = {
                    inner = {
                        value = will_throw {}
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

            let handle = {} -> perform ExceptionToOption in Some {val = safe_div {a = 4, b = 0}}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = validate {x = 5}}
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

            let use_condition = {flag} -> perform ExceptionToResult in
                if flag then Ok {val = safe_div {a = 4, b = 0}} else Ok {val = safe_div {a = 10, b = 2}}

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

            let process = {opt} -> perform ExceptionToResult in match opt is
                Some {val} -> Ok {val = val + may_fail {x = 0}}
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

            let compute = {} -> perform ExceptionToResult in do
                let a = step1 {x = 5}
                let b = step2 {x = 50}
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

            let handle = {} -> perform ExceptionToResult in do
                let a = Exception.throw {error = "first"}
                let b = Exception.throw {error = "second"}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = use_value {x = will_fail {}}}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = get_suspended {}}
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

            let make_closure = {} -> {unused} -> will_fail {}

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

            let compute = {} -> do
                let with_result = perform ExceptionToResult in Ok {val = will_fail {}}
                let with_option = perform ExceptionToOption in Some {val = will_fail {}}
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
    fun `test a deferred computation (thunk) forced under multiple handlers`() {
        // New model: to share ONE deferred computation across handlers, make it a
        // THUNK (a function). Each handler forces it with `ok_div {}` — the eager
        // analogue of "the same suspended value forced multiple times".
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let ok_div = {} -> safe_div {a = 10, b = 2}

            let handle1 = {} -> perform ExceptionToResult in Ok {val = ok_div {}}
            let handle2 = {} -> perform ExceptionToResult in Ok {val = ok_div {}}

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

            let main = {} -> perform Console in do
                info {msg = "before"}
                Log.log {level = Info, msg = "suspended log"}
                info {msg = "after"}
                {}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        // The log performed inside the Console handler is printed, in order.
        assertTrue(result.output().contains("[INFO] before"))
        assertTrue(result.output().contains("[INFO] suspended log"))
        assertTrue(result.output().contains("[INFO] after"))
    }

    @Test
    fun `test an unused thunk's effect is not executed`() {
        // New model: a deferred effect is a THUNK (a function). A thunk that is never
        // called never runs its body — the eager analogue of "a suspended value that's
        // never forced isn't executed". `unused_log` is defined but never called, so its
        // Log never fires.
        val source = """
            use std::effects::log::{Log, Console, info}

            let unused_log = {} -> Log.log {level = Info, msg = "should not appear"}

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

            let sum_to = {n, acc} ->
                if n <= 0 then acc
                else sum_to {n = n - 1, acc = acc + n}

            let handle = {} -> perform ExceptionToResult in do
                let sum = sum_to {n = 5, acc = 0}
                Ok {val = sum + will_fail {}}
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

            let compute = {} -> do
                let h1 = perform ExceptionToResult in Ok {val = validate_name {name = ""}}
                let h2 = perform ExceptionToResult in Ok {val = validate_name {name = "Alice"}}
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
    fun `test Inspect to_dbg on result of lazy effect`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::log::{Log, Console, info}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let handle = {} -> perform ExceptionToResult in
                Ok {val = safe_div {a = 4, b = 0}}

            let main = {} -> perform Console in
                info {msg = "Result: $((handle {}).to_dbg {})"}
            main {}
        """.trimIndent()

        val result = evalWithOutput(source)
        assertTrue(result.output().contains("Err"))
        assertTrue(result.output().contains("Div by zero"))
    }

    @Test
    @Disabled("#392 — residual under MEZZE_EAGER=1; passes in isolation, fails after prior test in same class. NOT an Engine-cache leak (empirically refuted: clearing slotTable + userStubCache + stdlibCallTargetCache + importedCallTargetCache in disposeContext does NOT fix). Real root cause likely: (a) a stale value baked as @ConstantOperand in MezzeBytecodeRootNode bytecode (per-Engine, unclearable from disposeContext) OR (b) test-order-dependent poisoning of MezzeVariant.TagRegistry / HandlerChainEntry ThreadLocal / HandlerChainRegistry state. Next diagnostic pass: instrument thread-local + TagRegistry state at test teardown, not Engine caches.")
    fun `test Inspect to_dbg on success path of lazy effect`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::log::{Log, Console, info}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 10, b = 2}}

            let main = {} -> perform Console in
                info {msg = "Result: $((handle {}).to_dbg {})"}
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

            let handle = {} -> perform ExceptionToResult in do
                may_fail {x = 5}   # returns unit, performs Exception (does not throw here)
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

            let handle = {} -> perform ExceptionToResult in Ok {val = [make_item {x = 1}, make_item {x = 2}, make_item {x = 3}]}
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

            let handle1 = {} -> perform ExceptionToResult in Ok {val = step1 {}}
            let handle2 = {} -> perform ExceptionToResult in Ok {val = step2 {}}

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

            let handle1 = {} -> perform ExceptionToResult in Ok {val = step1 {}}
            let handle2 = {} -> perform ExceptionToResult in Ok {val = step2 {}}

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

            let inner_fn = {} -> will_fail {}

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

            let level3 = {} -> will_fail {}
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

            let main = {} -> perform Console in do
                info {msg = "before handle"}
                let result = perform ExceptionToResult in Ok {val = will_fail {}}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = create_person {name = "Alice", age = 30}}
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

            let handle = {} -> perform ExceptionToResult in Ok {val = get_status {code = 2}}
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
    fun `test a deferred computation (thunk) aliased across variables`() {
        // New model: a deferred computation is a THUNK. Aliasing it across variables
        // and forcing each (`alias1 {}` / `alias2 {}`) under a handler is the eager
        // analogue of "one suspended value assigned to multiple variables".
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let will_fail = {} -> Exception.throw {error = "shared"}

            let deferred = {} -> will_fail {}
            let alias1 = deferred
            let alias2 = deferred

            let h1 = {} -> perform ExceptionToResult in Ok {val = alias1 {}}
            let h2 = {} -> perform ExceptionToResult in Ok {val = alias2 {}}

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

            let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 10, b = 2} + safe_div {a = 20, b = 4}}
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
                if id == 0 then Exception.throw {error = "invalid id"} else "User$(id.to_dbg {})"

            let handle = {} -> perform ExceptionToResult in Ok {val = "Hello, $(get_name {id = 42})!"}

            let main = {} -> perform Console in
                info {msg = (handle {}).to_dbg {}}
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

            let handle = {} -> perform ExceptionToResult in do
                let s1 = step1 {x = 3}  # 4
                let s2 = step2 {x = s1}  # 8
                Ok {val = s2}
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

            let handle = {} -> perform ExceptionToResult in do
                let s1 = step1 {x = 5}  # 6 - will fail in step2
                let s2 = step2 {x = s1}  # Should throw "big"
                Ok {val = s2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("big", payload.getField("error"))
    }
}
