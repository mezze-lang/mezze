package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 1 walking skeleton — sanity tests for [MezzeBytecodeRootNode].
 *
 * These tests exercise the BDSL-generated interpreter directly (bypass
 * the front-end pipeline). They prove:
 *   1. The annotation processor produced a compilable generated class.
 *   2. That class can construct a working `RootNode` via its builder API.
 *   3. Basic operations (`LoadLongConstant`, `AddLong`, implicit Return)
 *      execute correctly under a real Truffle runtime.
 *
 * We DON'T touch the front-end (Parser/Typechecker/Compiler) yet. That
 * wiring lands in the next commit once we know the skeleton works.
 *
 * See docs/perf/bytecode-dsl-migration-2026-07.md, task #278.
 */
class MezzeBytecodeRootNodeTest {

    /**
     * Construct a BDSL program: `return 1 + 2`. Execute it. Expect 3.
     *
     * This is the smallest possible end-to-end proof that BDSL
     * scaffolding works.
     */
    @Test
    fun `bdsl skeleton executes 1 plus 2`() {
        // BDSL requires a real Truffle Context to init the language.
        // Use an explicit polyglot Context so MezzeLanguage's TruffleLanguage
        // instance is discoverable via env.getLanguage().
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            context.enter()
            try {
                val language = MezzeLanguage.get(null)
                val nodes = MezzeBytecodeRootNodeGen.create(
                    language,
                    com.oracle.truffle.api.bytecode.BytecodeConfig.DEFAULT
                ) { b ->
                    b.beginRoot()
                    b.beginReturn()
                    b.beginAddLong()
                    b.emitLoadLongConstant(1L)
                    b.emitLoadLongConstant(2L)
                    b.endAddLong()
                    b.endReturn()
                    b.endRoot()
                }
                val root = nodes.getNode(0)
                val result = root.callTarget.call()
                assertEquals(3L, result)
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
