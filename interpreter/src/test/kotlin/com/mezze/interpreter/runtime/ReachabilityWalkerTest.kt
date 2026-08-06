package com.mezze.interpreter.runtime

import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.CaseIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.HandleIR
import com.mezze.language.truffleir.HandlerBindIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.MatchIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.PatternIR
import com.mezze.language.truffleir.ReadGlobalFnByHashIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReachabilityWalkerTest {

    private val src = SourceInfo(0, 1, 1, 1, 1)

    private fun h(hex: String): Hash = Hash(hex.padStart(64, '0'))

    private fun fn(hash: Hash, body: NodeIR): FunctionIR = FunctionIR(
        hash = hash,
        name = "fn_${hash.hex.take(4)}",
        qualifiedName = "test::fn_${hash.hex.take(4)}",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = body,
        sourceFile = "test.mz",
        sourceSection = src,
    )

    private fun tableLookup(vararg fns: FunctionIR): (Hash) -> FunctionIR? {
        val m = fns.associateBy { it.hash }
        return { m[it] }
    }

    @Test
    fun `single function no calls returns just root`() {
        val root = h("a1")
        val result = ReachabilityWalker.walk(setOf(root), tableLookup(fn(root, LongLitIR(1L, src))))
        assertEquals(setOf(root), result)
    }

    @Test
    fun `chain A calls B calls C returns all three`() {
        val a = h("a1"); val b = h("b2"); val c = h("c3")
        val fnA = fn(a, CallDirectIR(b, emptyList(), isTailCall = false, src = src))
        val fnB = fn(b, CallDirectIR(c, emptyList(), isTailCall = false, src = src))
        val fnC = fn(c, LongLitIR(42L, src))
        val result = ReachabilityWalker.walk(setOf(a), tableLookup(fnA, fnB, fnC))
        assertEquals(setOf(a, b, c), result)
    }

    @Test
    fun `HOF map calls f-lambda enqueues lambda hash`() {
        val top = h("a1"); val map = h("b2"); val lambda = h("c3")
        val fnTop = fn(
            top,
            CallDirectIR(
                map,
                listOf(MakeLambdaIR(lambda, captures = emptyList(), src = src)),
                isTailCall = false,
                src = src,
            ),
        )
        val fnMap = fn(map, LongLitIR(0L, src))
        val fnLambda = fn(lambda, LongLitIR(1L, src))
        val result = ReachabilityWalker.walk(setOf(top), tableLookup(fnTop, fnMap, fnLambda))
        assertEquals(setOf(top, map, lambda), result)
    }

    @Test
    fun `cyclic A calls B B calls A does not loop`() {
        val a = h("a1"); val b = h("b2")
        val fnA = fn(a, CallDirectIR(b, emptyList(), isTailCall = false, src = src))
        val fnB = fn(b, CallDirectIR(a, emptyList(), isTailCall = false, src = src))
        val result = ReachabilityWalker.walk(setOf(a), tableLookup(fnA, fnB))
        assertEquals(setOf(a, b), result)
    }

    @Test
    fun `HandleIR enqueues handler impl hashes`() {
        val root = h("a1"); val effect = h("e0"); val handler = h("b2")
        val fnRoot = fn(
            root,
            HandleIR(
                body = LongLitIR(1L, src),
                handlers = listOf(HandlerBindIR(handlerHash = handler, effectHash = effect, performerName = "H", params = null)),
                src = src,
            ),
        )
        val fnHandler = fn(handler, LongLitIR(2L, src))
        val result = ReachabilityWalker.walk(setOf(root), tableLookup(fnRoot, fnHandler))
        assertTrue(result.contains(root))
        assertTrue(result.contains(handler))
        // effectHash is enqueued too (per canonical walker); lookup returns null; walker
        // logs via logger callback and includes it in the visited set for downstream diagnostics.
        assertTrue(result.contains(effect))
    }

    @Test
    fun `unknown hash logs and stops without crashing`() {
        val root = h("a1"); val missing = h("ff")
        val fnRoot = fn(root, CallDirectIR(missing, emptyList(), isTailCall = false, src = src))
        val logged = mutableListOf<Hash>()
        val result = ReachabilityWalker.walk(
            roots = setOf(root),
            lookup = tableLookup(fnRoot),
            logger = { hash -> logged.add(hash) },
        )
        assertEquals(setOf(root, missing), result)
        assertEquals(listOf(missing), logged)
    }

    @Test
    fun `deeply nested lambda inside match arm inside block is reachable`() {
        val top = h("a1"); val lambda = h("b2"); val calleeInsideLambda = h("c3")
        // top's body: block { let _ = match 0 { _ -> MakeLambda(lambda) }; 0 }
        // lambda's body: CallDirect(calleeInsideLambda)
        val match = MatchIR(
            scrutinee = LongLitIR(0L, src),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Literal(LongLitIR(0L, src)),
                    bindings = emptyList(),
                    guard = null,
                    body = MakeLambdaIR(lambda, emptyList(), src),
                    src = src,
                ),
            ),
            src = src,
        )
        val block = BlockIR(
            stmts = listOf(StmtIR.Let(slot = 0, kind = SlotKind.OBJECT, name = "_", contentHash = Hash.ZERO, value = match, src = src)),
            result = LongLitIR(0L, src),
            src = src,
        )
        val fnTop = fn(top, block)
        val fnLambda = fn(lambda, CallDirectIR(calleeInsideLambda, emptyList(), isTailCall = false, src = src))
        val fnCallee = fn(calleeInsideLambda, LongLitIR(99L, src))
        val result = ReachabilityWalker.walk(setOf(top), tableLookup(fnTop, fnLambda, fnCallee))
        assertEquals(setOf(top, lambda, calleeInsideLambda), result)
    }

    @Test
    fun `ReadGlobalFnByHash enqueues its hash (ability dispatch dict path)`() {
        val top = h("a1"); val implFn = h("b2")
        // Simulates ability impl reaching walker via MakeRecordIR field carrying ReadGlobalFnByHashIR.
        val fnTop = fn(
            top,
            MakeRecordIR(
                fields = listOf(FieldIR(name = "add", value = ReadGlobalFnByHashIR(implFn, src))),
                src = src,
            ),
        )
        val fnImpl = fn(implFn, LongLitIR(0L, src))
        val result = ReachabilityWalker.walk(setOf(top), tableLookup(fnTop, fnImpl))
        assertEquals(setOf(top, implFn), result)
    }

    @Test
    fun `roots that don't exist are logged and included`() {
        val root = h("a1")
        val logged = mutableListOf<Hash>()
        val result = ReachabilityWalker.walk(
            roots = setOf(root),
            lookup = { null },
            logger = { logged.add(it) },
        )
        assertEquals(setOf(root), result)
        assertEquals(listOf(root), logged)
    }

    @Test
    fun `walker does not enqueue hashes for pure-literal function bodies`() {
        val root = h("a1")
        val fnRoot = fn(root, LongLitIR(5L, src))
        val result = ReachabilityWalker.walk(setOf(root), tableLookup(fnRoot))
        assertEquals(setOf(root), result)
        assertFalse(result.contains(h("ff")))
    }
}
