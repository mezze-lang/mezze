package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeForeign
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Java callback bridge: a Mezze function crosses to Java BY REFERENCE as whatever
 * functional interface the Java parameter asks for (`from_fn0` → Supplier/Runnable,
 * `from_fn` → Consumer/Function, `from_fn2` → BiFunction…), Java calls it from ITS
 * thread, its arguments arrive as `JvmForeign`, its result crosses back as a value.
 *
 * This is the surface a reactor package (Netty, io_uring, any CompletableFuture-based
 * library) needs to complete a Mezze promise from the library's own callback thread
 * instead of parking a Mezze-side thread on `future.get()`.
 */
class JvmCallbackBridgeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeCheckError::class.java // keep the import honest
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun ok(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "expected a Result, got $result (${result.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "expected Ok, got tag=${v.tag} ${v}")
        return v
    }

    /** `supplyAsync(Supplier)`: Java runs the Mezze function on a ForkJoin worker thread. */
    @Test
    fun `a zero-argument Mezze function runs as a Java Supplier on Java's thread`() {
        val r = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost, from_fn0 }
            perform JvmHost in do
              let cf = Jvm.class { name = "java.util.concurrent.CompletableFuture" }
                .method { name = "supplyAsync", args = [ from_fn0 { f = {} -> 41 + 1 } ] }
              cf.method { name = "join", args = [] }.int{}
            """,
        )
        assertEquals(42L, ok(r).getPayloadFieldByName("val"))
    }

    /** `thenApply(Function)`: the argument arrives as a JvmForeign, decoded by the callee. */
    @Test
    fun `a one-argument Mezze function receives Java's argument as a foreign`() {
        val r = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost, from_int, from_fn }
            use std::result
            perform JvmHost in do
              let cf = Jvm.class { name = "java.util.concurrent.CompletableFuture" }
                .method { name = "completedFuture", args = [ from_int { val = 20 } ] }
              let doubled = cf.method { name = "thenApply", args = [ from_fn { f = { x } -> result::get_or { result = x.int{}, def = 0 - 1 } * 2 } ] }
              doubled.method { name = "join", args = [] }.int{}
            """,
        )
        assertEquals(40L, ok(r).getPayloadFieldByName("val"))
    }

    /** `thenCombine(other, BiFunction)`: two arguments, two foreigns. */
    @Test
    fun `a two-argument Mezze function is a Java BiFunction`() {
        val r = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost, from_int, from_fn2 }
            use std::result
            perform JvmHost in do
              let cls = Jvm.class { name = "java.util.concurrent.CompletableFuture" }
              let a = cls.method { name = "completedFuture", args = [ from_int { val = 30 } ] }
              let b = cls.method { name = "completedFuture", args = [ from_int { val = 12 } ] }
              let sum = a.method { name = "thenCombine", args = [ b, from_fn2 { f = { a, b } ->
                result::get_or { result = a.int{}, def = 0 } + result::get_or { result = b.int{}, def = 0 } } ] }
              sum.method { name = "join", args = [] }.int{}
            """,
        )
        assertEquals(42L, ok(r).getPayloadFieldByName("val"))
    }

    /** The reactor shape: Java's own callback thread completes a Mezze promise that a
     *  cooperative task is awaiting. No Mezze-side thread, no poll — the completion
     *  reaches the idle machine through the promise's settlement. */
    @Test
    fun `Java's callback thread completes a promise awaited under CoopAsync`() = assertEquals(
        7L,
        eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost, from_fn0, from_fn }
            use std::effects::async::{ Async, CoopAsync, await, promise_from }
            use std::result
            perform JvmHost, CoopAsync in do
              let cf = Jvm.class { name = "java.util.concurrent.CompletableFuture" }
                .method { name = "supplyAsync", args = [ from_fn0 { f = {} -> 7 } ] }
              let p = promise_from { setup = { complete, fail, on_cancel } -> do
                cf.method { name = "thenAccept", args = [ from_fn { f = { x } -> complete { val = result::get_or { result = x.int{}, def = 0 - 1 } } } ] }
                {} }
              result::get_or { result = await { p = p }, def = 0 - 2 }
            """,
        ),
    )

    /** Arity is the GUEST's contract: Java calling a one-argument callable with two
     *  arguments is Java's error, surfaced on the Java side (the future fails), never a
     *  Mezze runtime error. `BiFunction` given a `from_fn` → thenCombine fails. */
    @Test
    fun `a wrong-arity call is the guest's error, not a Mezze failure`() {
        val r = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost, from_int, from_fn }
            perform JvmHost in do
              let cls = Jvm.class { name = "java.util.concurrent.CompletableFuture" }
              let a = cls.method { name = "completedFuture", args = [ from_int { val = 1 } ] }
              let b = cls.method { name = "completedFuture", args = [ from_int { val = 2 } ] }
              a.method { name = "thenCombine", args = [ b, from_fn { f = { x } -> 0 } ] }
                .method { name = "isCompletedExceptionally", args = [] }.bool{}
            """,
        )
        assertEquals(true, ok(r).getPayloadFieldByName("val"))
    }

    /** The effect rule: the callback runs on Java's thread outside every `perform`, so its
     *  row must be EMPTY. A callback that performs Console is rejected at TYPE time. */
    @Test
    fun `a callback with effects is rejected by the typechecker`() {
        assertThrows(TypeCheckError::class.java) {
            MezzeTestHelper.typeCheck(
                """
                use std::polyglot::jvm::{ Jvm, JvmHost, from_fn }
                use std::effects::log::{ Console, println }
                let main = {} -> perform Console, JvmHost in do
                  let cls = Jvm.class { name = "java.util.ArrayList" }
                  cls.new { args = [] }.method { name = "forEach", args = [ from_fn { f = { x } -> println { msg = "seen" } } ] }
                  {}
                """.trimIndent(),
            )
        }
    }

    /** A Mezze value returned to Java crosses as a value Java can use: here Java sorts a
     *  list with a Mezze comparator (Comparator.compare is a BiFunction-shaped interface). */
    @Test
    fun `a Mezze comparator sorts a Java list`() {
        val r = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost, from_int, from_fn2 }
            use std::result
            perform JvmHost in do
              let list = Jvm.class { name = "java.util.ArrayList" }.new { args = [] }
              list.method { name = "add", args = [ from_int { val = 3 } ] }
              list.method { name = "add", args = [ from_int { val = 1 } ] }
              list.method { name = "add", args = [ from_int { val = 2 } ] }
              list.method { name = "sort", args = [ from_fn2 { f = { a, b } ->
                result::get_or { result = b.int{}, def = 0 } - result::get_or { result = a.int{}, def = 0 } } ] }
              list.method { name = "get", args = [ from_int { val = 0 } ] }.int{}
            """,
        )
        assertEquals(3L, ok(r).getPayloadFieldByName("val"))
    }
}
