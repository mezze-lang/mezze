package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BdslEntryReproTest {

    @Test
    fun minimalReproSharedPerformCallGlobalLambda() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            let f = {} -> Log.info {msg = "hi"}

            perform Console in f {}
        """.trimIndent()
        val result = MezzeTestHelper.run(source)
        // Should not stack overflow — expected to complete with unit
        println("[REPRO] result=$result")
    }

    @Test
    fun minimalReproInlinePerform() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            perform Console in Log.info {msg = "hi"}
        """.trimIndent()
        val result = MezzeTestHelper.run(source)
        println("[REPRO-INLINE] result=$result")
    }

    @Test
    fun minimalReproTopLevelLambdaNoEffect() {
        // Does top-level lambda + call work at all under BDSL entry? (no effects)
        val source = """
            let f = {} -> 42
            f {}
        """.trimIndent()
        val result = MezzeTestHelper.run(source)
        println("[REPRO-NOEFFECT] result=$result")
        assertEquals(42L, result)
    }

    @Test
    fun minimalReproTopLevelLambdaEffect() {
        // Same shape but with an effect op inside the lambda
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            let f = {} -> Log.info {msg = "hi"}
            perform Console in f {}
        """.trimIndent()
        val result = MezzeTestHelper.run(source)
        println("[REPRO-EFFECT-LAMBDA] result=$result")
    }
}
