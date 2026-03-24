package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

/**
 * Tests for ability impls and inherent impls on newtypes in the stdlib.
 */
class NewtypeImplTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
    }

    @Nested
    inner class PromiseImpls {

        @Test
        fun `Promise has Eq via reference equality`() {
            val source = """
                use std::effects::async::{Async, LoomAsync, Promise}

                let main = {} -> perform LoomAsync in do
                    let p1 = Async.spawn {f = {} -> 42}
                    let p2 = p1
                    let same = p1 == p2
                    Async.await {p = p1}
            """.trimIndent()
            val result = typeChecker.checkProgram(parser.parse(source))
            assertTrue(result is TypeChecker.TypeCheckResult.Success,
                "Promise should have Eq: $result")
        }

        @Test
        fun `Promise has await inherent method`() {
            val source = """
                use std::effects::async::{Async, LoomAsync, Promise}

                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    p.await {}
            """.trimIndent()
            val result = typeChecker.checkProgram(parser.parse(source))
            assertTrue(result is TypeChecker.TypeCheckResult.Success,
                "Promise should have .await {} method: $result")
        }

        @Test
        fun `Promise has is_done inherent method`() {
            val source = """
                use std::effects::async::{Async, LoomAsync, Promise}

                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    let done = p.is_done {}
                    Async.await {p = p}
            """.trimIndent()
            val result = typeChecker.checkProgram(parser.parse(source))
            assertTrue(result is TypeChecker.TypeCheckResult.Success,
                "Promise should have .is_done {} method: $result")
        }
    }

    @Nested
    inner class WaitGroupImpls {

        @Test
        fun `WaitGroup has Eq`() {
            val source = """
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group}

                let wg1 = wait_group {}
                let wg2 = wg1
                let same = wg1 == wg2
            """.trimIndent()
            val result = typeChecker.checkProgram(parser.parse(source))
            assertTrue(result is TypeChecker.TypeCheckResult.Success,
                "WaitGroup should have Eq: $result")
        }
    }

    @Nested
    inner class ChannelImpls {

        // TODO: Parametric newtype Eq (impl Eq 'a for Chan 'a) doesn't resolve yet.
        // Non-parametric newtypes (WaitGroup, Promise with concrete type) work.
        // The structural matching in ConstraintSolver doesn't handle parametric newtype impls.
        // @Test
        // fun `Chan has Eq`() { ... }
    }
}
