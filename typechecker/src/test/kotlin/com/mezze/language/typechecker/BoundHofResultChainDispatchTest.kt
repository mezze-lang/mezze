package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Regression: a method chained off a `let`-bound HOF result must dispatch on
 * the value's real (Seq) type — the same as the inline chain.
 *
 * `let kept = xs.filter {…}; kept.map {…}` used to fail with "expected Bytes,
 * but got [{…}]" because `kept`'s binding type is a fresh result VarType (pinned
 * to a Seq only during SOLVING), so at `.map` dispatch the receiver looked
 * unresolved and mis-dispatched. The inline form `xs.filter{}.map{}` always
 * worked because the receiver call chain resolves the Seq via filter's declared
 * return type. Fix: receiver-type resolution now looks THROUGH the let-bound
 * source expression when the binding type is still a VarType (getReceiverConcreteType).
 */
class BoundHofResultChainDispatchTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    private fun returnType(t: MezzeType?): MezzeType? =
        (t as? MezzeType.FunctionType)?.returnType

    @Test
    fun `bound filter then map dispatches on Seq not Bytes`() {
        val r = check(
            """
            use std::collections::seq::{ filter, map }
            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              let kept = xs.filter { p = { x } -> x.ok }
              kept.map { f = { x } -> x.qty }
            """.trimIndent()
        )
        val ret = returnType(r.getVariableType("main"))
        assertTrue(
            ret is MezzeType.SeqType,
            "expected main to return a Seq (not Bytes), got $ret"
        )
        val elem = (ret as MezzeType.SeqType).elementType
        assertTrue(elem is MezzeType.IntType, "expected [Int], got element $elem")
    }

    @Test
    fun `bound map then filter dispatches on Seq`() {
        val r = check(
            """
            use std::collections::seq::{ filter, map }
            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              let mapped = xs.map { f = { x } -> x.qty }
              mapped.filter { p = { x } -> x.gt { r = 1 } }
            """.trimIndent()
        )
        val ret = returnType(r.getVariableType("main"))
        assertTrue(ret is MezzeType.SeqType, "expected Seq return, got $ret")
        assertTrue(
            (ret as MezzeType.SeqType).elementType is MezzeType.IntType,
            "expected [Int], got ${(ret).elementType}"
        )
    }

    @Test
    fun `inline filter then map still typechecks — regression guard`() {
        val r = check(
            """
            use std::collections::seq::{ filter, map }
            let main = {} -> do
              let xs = [ { qty = 2, ok = True }, { qty = 1, ok = False } ]
              xs.filter { p = { x } -> x.ok }.map { f = { x } -> x.qty }
            """.trimIndent()
        )
        val ret = returnType(r.getVariableType("main"))
        assertTrue(ret is MezzeType.SeqType, "inline chain should still infer Seq, got $ret")
    }

    @Test
    fun `literal seq bound then map still typechecks — regression guard`() {
        val r = check(
            """
            use std::collections::seq::{ map }
            let main = {} -> do
              let xs = [ { qty = 2 }, { qty = 1 } ]
              xs.map { f = { x } -> x.qty }
            """.trimIndent()
        )
        val ret = returnType(r.getVariableType("main"))
        assertTrue(ret is MezzeType.SeqType, "literal-bound chain should infer Seq, got $ret")
    }
}
