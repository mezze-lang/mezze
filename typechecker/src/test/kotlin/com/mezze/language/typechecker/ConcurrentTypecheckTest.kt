package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.module.ModuleTypeChecker
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Several typechecks of the same codebase at once, each in its own world.
 *
 * This was impossible for one reason, and it was a CORRECTNESS limit rather than a
 * locking one: the caches were already synchronised, but two checks shared one
 * cycle-detection set, so each made the other look like an import cycle. A false
 * cycle is a reported error about the user's program, not a slow path.
 *
 * ## Why every module here IMPORTS something
 *
 * `TypeChecker` skips import resolution entirely when a module has no imports, so
 * `ModuleTypeChecker.typeCheckModule` — and therefore the cycle-detection set this
 * whole task is about — is never reached for a standalone module. An earlier version
 * of this file checked import-free sources and proved NOTHING: two separate
 * mutations that broke the guard outright still passed it. Every test below resolves
 * a real cross-module import from a temp project so the path actually runs, and
 * asserts the import resolved rather than assuming it.
 *
 * A stdlib import would not do either: those are served from the shared base as a
 * cache hit, which also skips the path.
 */
class ConcurrentTypecheckTest {

    @TempDir
    lateinit var projectDir: Path

    /** `src/helper.mz`, imported by every main module below. */
    private fun writeProject() {
        val src = projectDir.resolve("src")
        Files.createDirectories(src)
        Files.writeString(
            src.resolve("helper.mz"),
            """
            pub let bump = { x } -> do
                let y = x
                y
            """.trimIndent(),
        )
    }

    private fun mainSource(n: Int) = """
        use helper::{bump}

        let use$n = { v } -> bump { x = v }
    """.trimIndent()

    private fun check(world: World, n: Int): TypeChecker.TypeCheckResult {
        val parsed = MezzeParser().parseParsedModule(mainSource(n), "main$n.mz")
        return TypeChecker(world = world)
            .checkParsedModule(parsed, projectDir, currentModulePath = "main$n")
    }

    private fun assertSucceeded(result: TypeChecker.TypeCheckResult, label: String) {
        val failure = result as? TypeChecker.TypeCheckResult.Failure ?: return
        throw AssertionError(
            "$label failed to typecheck: " + failure.errors.joinToString("; ") { it.message }
        )
    }

    /** Proof the import really resolved — otherwise these tests would be hollow. */
    private fun assertResolvedHelper(world: World) {
        val seen = ModuleResolver.snapshot(world).keys
        assertTrue(
            seen.any { "helper" in it },
            "world $world never resolved `helper`, so the code path under test did not " +
                "run: $seen",
        )
    }

    private fun drop(worlds: List<World>) = worlds.forEach {
        ModuleTypeChecker.forgetWorld(it)
        ModuleResolver.forgetWorld(it)
    }

    private fun drop(world: World) = drop(listOf(world))

    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @Test
    fun `eight worlds typecheck the same codebase concurrently`() {
        writeProject()
        val n = 8
        val worlds = List(n) { World.fresh("concurrent-$it") }
        val pool = Executors.newFixedThreadPool(n)
        // One latch so they genuinely overlap; staggered starts would let each finish
        // before the next began and prove nothing.
        val start = CountDownLatch(1)
        try {
            val futures = worlds.mapIndexed { i, w ->
                pool.submit(Callable { start.await(); check(w, i) })
            }
            start.countDown()
            futures.forEachIndexed { i, f ->
                assertSucceeded(f.get(150, TimeUnit.SECONDS), "world $i")
            }
            // Each world resolved `helper` for itself — the same module path living in
            // eight caches at once, which is the collision the key change fixes.
            worlds.forEach { assertResolvedHelper(it) }
        } finally {
            pool.shutdownNow()
            drop(worlds)
        }
    }

    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @Test
    fun `two checks in the SAME world complain about the world, not the code`() {
        // The mistake a caller reaches for first. Sharing a world means sharing the
        // cycle-detection set, so each check would make the other look like an import
        // cycle — an error about the USER's program caused by a bug in the caller's
        // threading.
        //
        // Deliberately not asserted: that one of the two fails. Whether they overlap
        // is a scheduling accident, so demanding a failure would be flaky. What must
        // hold is that IF one fails, it names the world rather than the program.
        writeProject()
        val shared = World.fresh("shared")
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { i ->
                pool.submit(Callable { start.await(); runCatching { check(shared, i) } })
            }
            start.countDown()
            val outcomes = futures.map { it.get(150, TimeUnit.SECONDS) }

            for (outcome in outcomes) {
                val failure = outcome.exceptionOrNull() ?: continue
                assertTrue(
                    failure is IllegalArgumentException &&
                        "already being type-checked" in (failure.message ?: ""),
                    "a same-world clash must name the world, not the program: $failure",
                )
            }
            outcomes.mapNotNull { it.getOrNull() }.forEach { assertSucceeded(it, "the winner") }
        } finally {
            pool.shutdownNow()
            drop(shared)
        }
    }

    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @Test
    fun `a world is usable by another thread after a check finishes`() {
        // The guard must RELEASE. An ownership flag that leaked would make a world
        // single-shot — which a server reusing one would hit on its second request, as
        // a refusal that looks like a threading bug in the caller.
        writeProject()
        val w = World.fresh("released")
        try {
            assertSucceeded(check(w, 1), "first check")
            assertResolvedHelper(w)

            // Invalidate `helper` so the second check must RE-ENTER the module path.
            // Without this the second check hits the cache and returns before ever
            // claiming the world, so it would pass even with the release deleted —
            // which is exactly what an earlier version of this test did.
            ModuleTypeChecker.invalidateModulePaths(listOf("helper"), w)
            ModuleResolver.invalidateModulePaths(listOf("helper"), w)

            // A DIFFERENT thread, which is exactly what a leaked owner rejects.
            val pool = Executors.newSingleThreadExecutor()
            try {
                assertSucceeded(
                    pool.submit(Callable { check(w, 2) }).get(150, TimeUnit.SECONDS),
                    "second check from another thread",
                )
            } finally {
                pool.shutdownNow()
            }
        } finally {
            drop(w)
        }
    }

    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @Test
    fun `a world sees only its own modules`() {
        writeProject()
        val a = World.fresh("only-a")
        val b = World.fresh("only-b")
        try {
            assertSucceeded(check(a, 1), "a")
            assertSucceeded(check(b, 2), "b")
            assertResolvedHelper(a)
            assertResolvedHelper(b)
            assertTrue(
                ModuleResolver.snapshot(a).keys.none { "main2" in it },
                "world a picked up world b's module: ${ModuleResolver.snapshot(a).keys}",
            )
        } finally {
            drop(listOf(a, b))
        }
    }

    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @Test
    fun `the default world still typechecks, unchanged`() {
        // The compatibility property: every existing caller names no world.
        writeProject()
        val parsed = MezzeParser().parseParsedModule(mainSource(0), "main0.mz")
        assertSucceeded(
            TypeChecker().checkParsedModule(parsed, projectDir, currentModulePath = "main0"),
            "default world",
        )
    }

    @Test
    fun `a checker and a resolver in different worlds is refused`() {
        // The pairing that would split a check's caches in half: typed modules in one
        // world, the parsed modules they were built from in another. Nothing would
        // fail — the halves would just disagree — so it is rejected up front.
        val a = World.fresh("pair-a")
        val b = World.fresh("pair-b")
        try {
            val loaderInB = com.mezze.language.typechecker.module.ModuleLoader.fromPath(".", b)
            val e = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                ModuleTypeChecker.withContextAndResolver(
                    com.mezze.language.typechecker.context.TypingContext.empty,
                    loaderInB.getResolver(),
                    a,
                )
            }
            assertTrue("world" in (e.message ?: ""), "unhelpful message: ${e.message}")
        } finally {
            drop(listOf(a, b))
        }
    }
}
