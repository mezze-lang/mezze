package com.mezze.language.typechecker.module

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.typechecker.Lowering
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A module read back from the checked-module cache is imported through its
 * interface alone: checking an importer must not read its body, and must give
 * the importer the types a fresh check gives it.
 */
class CheckedModuleCacheTest {

    @TempDir
    lateinit var project: Path

    private val store = InMemoryMezzeStore()

    @BeforeEach
    fun setUp() {
        TypeChecker.clearCache()
        ModuleResolver.cacheStore = lazyOf(store)
        // As `mez check` runs: nothing is lowered.
        Lowering.enabled = false
        project.resolve("lib.mz").writeText(
            """
            use std::effects::log::{ println }

            pub type Shape = | Circle { r: Int }, Square { s: Int } |

            pub let circle: { r: Int } -> Shape = { r } -> Circle { r = r }

            impl Shape where {
              area = { it } ->
                match it is
                  Circle { r } -> r * r
                  Square { s } -> s * s
            }

            effect Tick where {
              tick: {} -> {} <Tick>
            }

            ## Performing it raises `Log`: its operation prints.
            impl Tick for LoudTick where {
              tick = {} -> println { msg = "tick" }
            }
            """.trimIndent(),
        )
    }

    @AfterEach
    fun tearDown() {
        ModuleResolver.cacheStore = null
        Lowering.enabled = true
        TypeChecker.clearCache()
    }

    private val app = """
        use std::effects::log::{ Console }
        use lib::{ Shape, circle, Tick, LoudTick }

        let area_of = {} -> (circle { r = 2 }).area {}
        let ticked = {} -> perform LoudTick in Tick.tick {}
        let printed = {} -> perform Console in ticked {}
    """.trimIndent()

    /** The app's declarations' types, checked with whatever the caches hold. */
    private fun check(): Map<String, String> {
        val parsed = MezzeParser().parseParsedModule(app, "app.mz")
        val result = TypeChecker().checkParsedModule(parsed, project, enablePrelude = true)
        CheckedModuleCache.flush()
        val ok = result as? TypeChecker.TypeCheckResult.Success ?: error("did not type-check: $result")
        return ok.typedModule!!.decls.filterIsInstance<TypedLetDecl>()
            .associate { it.name to prettyPrintType(it.scheme.type) }
    }

    /** The key lib is kept under: what it was last checked from. */
    private fun key(): String {
        val lib = ModuleResolver(project).resolveModule("lib")
        return "${lib.identity!!.hex}:${lib.sourceHash!!.hex}"
    }

    private fun lib(): TypedModuleResult =
        ModuleTypeChecker.getAllCachedModules().getValue("lib")

    @Test
    fun `an importer of a cached module is checked without reading its body`() {
        check()
        assertTrue(store.getCheckedInterface("lib", key()) != null, "the first check keeps lib in the store")

        TypeChecker.clearCache()
        check()
        assertFalse(lib().bodyRead, "lib came from the cache, and nothing read its body")
    }

    @Test
    fun `an importer of a cached module gets the types a fresh check gives it`() {
        val fresh = check()
        assertTrue("Log" in fresh.getValue("ticked"), "the performer's operation raises Log: ${fresh["ticked"]}")
        assertFalse("Log" in fresh.getValue("printed"), "and Console handles it: ${fresh["printed"]}")

        TypeChecker.clearCache()
        assertEquals(fresh, check())
        assertFalse(lib().bodyRead)
    }

    @Test
    fun `a changed module is checked again, not read back`() {
        check()
        project.resolve("lib.mz").writeText(Files.readString(project.resolve("lib.mz")) +
            "\npub let extra: {} -> Int = {} -> 1\n")
        TypeChecker.clearCache()
        ModuleResolver.clearCache()
        check()
        assertTrue(lib().bodyRead, "lib changed, so it was checked, not read from the cache")
    }
}
