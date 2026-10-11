package com.mezze.language.typechecker

import com.mezze.language.ast.ModulePath
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Two views of a codebase must not see each other's modules.
 *
 * The caches these tests exercise were process-global and keyed by module PATH
 * alone. They were synchronised, so thread-SAFE — but not SEPARABLE, which is a
 * different property and the one that was missing. Two consequences blocked real
 * work:
 *
 *  - two variants of a project that both contain `app::handler` shared one cache
 *    entry, so a speculative or dry-run change had nowhere to live; and
 *  - the cycle-detection set conflated "this PROCESS is checking X" with "this
 *    REQUEST is checking X", so two concurrent checks reaching the same module
 *    reported each other as an import cycle — a reported error, not a slow path.
 *
 * Every test here is about the SEPARATION, not about typechecking: the unit under
 * test is the cache key, so the cheapest possible module values are used.
 */
class WorldIsolationTest {

    private val worldsUsed = mutableListOf<World>()

    private fun world(label: String): World =
        World.fresh(label).also { worldsUsed.add(it) }

    @AfterEach
    fun dropWorlds() {
        // A world holds caches; leaving them behind would leak across tests and
        // make a later assertion depend on which test ran first.
        for (w in worldsUsed) {
            ModuleTypeChecker.forgetWorld(w)
            ModuleResolver.forgetWorld(w)
        }
        worldsUsed.clear()
    }

    private fun typedModule(path: String): TypedModule = TypedModule(
        path = path,
        hash = Hash("00".repeat(32)),
        imports = emptyList(),
        exports = emptyMap(),
        decls = emptyList(),
        abilityDecls = emptyList(),
        abilityImpls = emptyList(),
        effectDecls = emptyList(),
        performerDecls = emptyList(),
        dependencies = emptyList(),
    )

    private fun module(path: String, hash: String? = null): Module = Module(
        path = ModulePath.parse(path),
        filePath = "$path.mz",
        exports = emptyMap(),
        dependencies = emptyList(),
        sourceHash = hash?.let { Hash(it) },
    )

    // ---- ModuleTypeChecker: the typed-module cache --------------------------

    @Test
    fun `a module hydrated into one world is invisible in another`() {
        val a = world("a")
        val b = world("b")
        ModuleTypeChecker.cacheTypedModule("app::handler", typedModule("app::handler"), a)

        assertNotNull(ModuleTypeChecker.getAllCachedModules(a)["app::handler"])
        assertNull(
            ModuleTypeChecker.getAllCachedModules(b)["app::handler"],
            "world b sees world a's module, so the caches are not separable",
        )
    }

    @Test
    fun `the same path can hold DIFFERENT modules in two worlds`() {
        // The case the refactor exists for: a speculative variant of a module the
        // project already has. Keyed by path alone, the second write destroyed the
        // first and there was nowhere to put a hypothetical version.
        val a = world("a")
        val b = world("b")
        val path = "app::handler"

        ModuleTypeChecker.cacheTypedModule(path, typedModule("$path (as a sees it)"), a)
        ModuleTypeChecker.cacheTypedModule(path, typedModule("$path (as b sees it)"), b)

        assertEquals(
            "app::handler (as a sees it)",
            ModuleTypeChecker.getAllCachedModules(a)[path]?.typedModule?.path,
        )
        assertEquals(
            "app::handler (as b sees it)",
            ModuleTypeChecker.getAllCachedModules(b)[path]?.typedModule?.path,
        )
    }

    @Test
    fun `clearing one world leaves another intact`() {
        val a = world("a")
        val b = world("b")
        ModuleTypeChecker.cacheTypedModule("app::x", typedModule("app::x"), a)
        ModuleTypeChecker.cacheTypedModule("app::x", typedModule("app::x"), b)

        ModuleTypeChecker.clearCache(a)

        assertNull(ModuleTypeChecker.getAllCachedModules(a)["app::x"])
        assertNotNull(
            ModuleTypeChecker.getAllCachedModules(b)["app::x"],
            "clearing world a emptied world b as well",
        )
    }

    @Test
    fun `invalidating a path in one world leaves another intact`() {
        // A file-watcher event belongs to the world watching those files.
        val a = world("a")
        val b = world("b")
        ModuleTypeChecker.cacheTypedModule("app::x", typedModule("app::x"), a)
        ModuleTypeChecker.cacheTypedModule("app::x", typedModule("app::x"), b)

        ModuleTypeChecker.invalidateModulePaths(listOf("app::x"), a)

        assertNull(ModuleTypeChecker.getAllCachedModules(a)["app::x"])
        assertNotNull(ModuleTypeChecker.getAllCachedModules(b)["app::x"])
    }

    // ---- the shared base ---------------------------------------------------

    @Test
    fun `a fresh world already sees the standard library`() {
        // The stdlib is immutable and identical in every world, and there is a lot
        // of it. If each world owned a copy, creating one would multiply the largest
        // thing in memory — so it lives in a shared base that worlds read through.
        // This is what makes a world cheap enough to actually use.
        ModuleTypeChecker.clearCache()   // populates the shared base from PrecompiledStdlib
        val fresh = world("fresh")

        val visible = ModuleTypeChecker.getAllCachedModules(fresh)
        assertTrue(
            visible.keys.any { it.startsWith("std::") },
            "a fresh world sees no stdlib, so it would re-typecheck all of it: " +
                "${visible.keys.take(5)}",
        )
    }

    @Test
    fun `a world's own module shadows the shared base`() {
        // Overlay semantics: a world that has its own version of a path sees that,
        // and one that has not falls through. Without this a world could not model a
        // change to anything already in the base.
        ModuleTypeChecker.clearCache()
        val shared = ModuleTypeChecker.getAllCachedModules()
            .keys.firstOrNull { it.startsWith("std::") }
            ?: return  // no precompiled stdlib in this environment; nothing to shadow

        val a = world("a")
        ModuleTypeChecker.cacheTypedModule(shared, typedModule("shadowed"), a)

        assertEquals("shadowed", ModuleTypeChecker.getAllCachedModules(a)[shared]?.typedModule?.path)
        assertTrue(
            ModuleTypeChecker.getAllCachedModules()[shared]?.typedModule?.path != "shadowed",
            "shadowing in world a leaked into the shared base",
        )
    }

    @Test
    fun `invalidating a path does not evict the shared base`() {
        // Behaviour change, and a deliberate one: the stdlib used to live in the same
        // map as project code, so invalidating a path could evict a precompiled
        // stdlib module and force a re-typecheck of it. Nothing a world does can
        // change the stdlib, so nothing a world does should drop it.
        ModuleTypeChecker.clearCache()
        val shared = ModuleTypeChecker.getAllCachedModules()
            .keys.firstOrNull { it.startsWith("std::") } ?: return

        ModuleTypeChecker.invalidateModulePaths(listOf(shared))

        assertNotNull(
            ModuleTypeChecker.getAllCachedModules()[shared],
            "invalidating '$shared' dropped it from the shared base",
        )
    }

    // ---- ModuleResolver: the parsed-module cache ---------------------------

    @Test
    fun `a parsed module cached in one world is invisible in another`() {
        val a = world("a")
        val b = world("b")
        ModuleResolver.putInCache(a, "app::x", module("app::x"))

        assertNotNull(ModuleResolver.getFromCache(a, "app::x"))
        assertNull(
            ModuleResolver.getFromCache(b, "app::x"),
            "ModuleResolver's cache is still shared across worlds",
        )
    }

    @Test
    fun `resolver snapshots are per world`() {
        val a = world("a")
        val b = world("b")
        ModuleResolver.putInCache(a, "app::only_in_a", module("app::only_in_a"))

        assertTrue("app::only_in_a" in ModuleResolver.snapshot(a))
        assertTrue("app::only_in_a" !in ModuleResolver.snapshot(b))
    }

    @Test
    fun `clearing one resolver world leaves another intact`() {
        val a = world("a")
        val b = world("b")
        ModuleResolver.putInCache(a, "app::x", module("app::x"))
        ModuleResolver.putInCache(b, "app::x", module("app::x"))

        ModuleResolver.clearCache(a)

        assertNull(ModuleResolver.getFromCache(a, "app::x"))
        assertNotNull(ModuleResolver.getFromCache(b, "app::x"))
    }

    @Test
    fun `invalidating a resolver path leaves another world intact`() {
        val a = world("a")
        val b = world("b")
        ModuleResolver.putInCache(a, "app::x", module("app::x"))
        ModuleResolver.putInCache(b, "app::x", module("app::x"))

        ModuleResolver.invalidateModulePaths(listOf("app::x"), a)

        assertNull(ModuleResolver.getFromCache(a, "app::x"))
        assertNotNull(ModuleResolver.getFromCache(b, "app::x"))
    }

    // ---- the default world -------------------------------------------------

    @Test
    fun `every entry point still defaults to one shared world`() {
        // The compatibility property. ~30 call sites across the CLI, LSP, the
        // interpreter's test helper and the typechecker's own tests name no world;
        // they must all keep addressing the same one.
        ModuleTypeChecker.cacheTypedModule("app::default_world", typedModule("app::default_world"))
        assertNotNull(ModuleTypeChecker.getAllCachedModules()["app::default_world"])
        assertNotNull(
            ModuleTypeChecker.getAllCachedModules(World.DEFAULT)["app::default_world"],
            "the no-argument overload and World.DEFAULT disagree",
        )
        ModuleTypeChecker.invalidateModulePaths(listOf("app::default_world"))
    }

    @Test
    fun `the default world cannot be forgotten`() {
        // Dropping it would empty the caches every existing caller shares, which is
        // a hard-to-diagnose way to lose the stdlib rather than a useful operation.
        assertThrows<IllegalArgumentException> { ModuleTypeChecker.forgetWorld(World.DEFAULT) }
        assertThrows<IllegalArgumentException> { ModuleResolver.forgetWorld(World.DEFAULT) }
    }

    @Test
    fun `two fresh worlds are distinct however they are labelled`() {
        val a = world("same")
        val b = world("same")
        assertTrue(a != b, "fresh() returned the same world twice: $a")
    }
}
