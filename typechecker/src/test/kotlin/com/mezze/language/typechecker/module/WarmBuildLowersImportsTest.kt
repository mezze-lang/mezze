package com.mezze.language.typechecker.module

import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.typechecker.Lowering
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A warm build compiles the same program a cold one does.
 *
 * A module read back from the checked-module cache does not resolve its own
 * imports — checking is what does that — so the modules under it never reached
 * the run, and lowering left them out. `app` → `demo` (cached) → `perf`: on every
 * warm `mez dev` the `Hello` performer in `perf` was "Handler not found", and a
 * dependency's performer (`mezze_js`'s `GraalJs`) died with
 * `CallDirectIR:target-not-compiled`. Cold runs were fine.
 */
class WarmBuildLowersImportsTest {

    @TempDir
    lateinit var project: Path

    private val store = InMemoryMezzeStore()

    @BeforeEach
    fun setUp() {
        TypeChecker.clearCache()
        ModuleResolver.cacheStore = lazyOf(store)
        Lowering.enabled = true
        project.resolve("perf.mz").writeText(
            """
            effect Greet where { greet: {} -> Str <Greet> }
            pub let helper = {} -> "hello"
            impl Greet for Hello where { greet = {} -> cont.resume { val = helper {} } }
            """.trimIndent(),
        )
        project.resolve("demo.mz").writeText(
            """
            use perf::{ Greet, Hello }
            pub let run_it = {} -> perform Hello in Greet.greet {}
            """.trimIndent(),
        )
    }

    @AfterEach
    fun tearDown() {
        ModuleResolver.cacheStore = null
        TypeChecker.clearCache()
    }

    private fun build(): TypeChecker.TypeCheckResult.Success {
        val parsed = MezzeParser().parseParsedModule("use demo::{ run_it }\nlet main = {} -> run_it {}", "app.mz")
        val result = TypeChecker().checkParsedModule(parsed, project, enablePrelude = true)
        CheckedModuleCache.flush()
        return result as? TypeChecker.TypeCheckResult.Success ?: error("did not type-check: $result")
    }

    /** `Hello`'s lowered record (it implements `greet`), and `perf::helper`, it calls. */
    private fun TypeChecker.TypeCheckResult.Success.lowersHello() = loweredPerformers.any { "greet" in it }
    private fun TypeChecker.TypeCheckResult.Success.lowersHelper() =
        loweredFunctions.orEmpty().any { it.qualifiedName.endsWith("helper") }

    @Test
    fun `a warm build lowers the performers and functions of modules under a cached one`() {
        val cold = build()
        assertTrue(cold.lowersHello() && cold.lowersHelper(), "the cold build lowers perf's Hello and helper")
        TypeChecker.clearCache()                  // a new run: only the store is left
        val warm = build()
        assertTrue(warm.lowersHello(), "the warm build must lower perf's Hello: demo was read back from the cache")
        assertTrue(warm.lowersHelper(), "the warm build must lower perf::helper, which Hello calls")
    }
}
