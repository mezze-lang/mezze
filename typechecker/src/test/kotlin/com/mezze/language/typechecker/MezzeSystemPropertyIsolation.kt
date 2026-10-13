package com.mezze.language.typechecker

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Every test gets the `mezze.*` system properties the BUILD set, not the ones a
 * previous test left behind.
 *
 * A system property is process-global, and 33 test files set one — almost all in
 * a `@BeforeEach`, almost none restoring it. That is invisible while the values
 * agree (two dozen of them set `mezze.typecheck.strict=true`, so the leak is a
 * no-op) and a landmine where they do not:
 *
 *     TestMinimalResult: System.setProperty("mezze.project.root", "/tmp/test_std")
 *
 * which pointed the project root at a directory that does not exist, for every
 * test that ran after it in the same JVM. `EmitFileSizeRatchetTest` and
 * `ConstraintResultPropagationTest` both read that property and both failed.
 *
 * WHY THIS IS AN EXTENSION AND NOT 33 EDITS. The suite pins its test ORDER with
 * a seed (see junit-platform.properties), so order is not the variable — but
 * `maxParallelForks = 4` means class-to-FORK assignment is Gradle's scheduling,
 * which is timing-dependent. Whether a polluter shares a JVM with a reader, and
 * precedes it, varies run to run. The failure is therefore FLAKY: the same commit
 * passes and fails. Fixing the one bad value leaves the shape intact for the next
 * one; restoring centrally makes the whole class of leak impossible, and makes a
 * new `setProperty` in a `@BeforeEach` harmless rather than a future flake.
 *
 * Only `mezze.*` is touched, because that is the namespace this repo owns — the
 * build's task-level `systemProperty(...)` values are the baseline restored to.
 * JVM and JUnit properties are left alone.
 *
 * `SoundnessSweepTest` already saves and restores `mezze.invariants` by hand; it
 * is the pattern this generalises, and the duplication is harmless.
 */
class MezzeSystemPropertyIsolation : BeforeEachCallback, AfterEachCallback {

    private val store = mutableMapOf<String, String?>()

    override fun beforeEach(context: ExtensionContext) = snapshot()

    override fun afterEach(context: ExtensionContext) = restore()

    /** Split out from the callbacks so the behaviour is testable without
     *  fabricating an [ExtensionContext] — neither half reads one. */
    internal fun snapshot() {
        store.clear()
        // Snapshot by VALUE, and record absence as null — a property the build
        // never set must be cleared afterwards, not set to "".
        for (key in System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX)) store[key] = System.getProperty(key)
        }
    }

    internal fun restore() {
        // Clear anything the test INTRODUCED, then put back what was there.
        // Both halves are needed: a test can add a key as well as change one.
        for (key in System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX) && !store.containsKey(key)) System.clearProperty(key)
        }
        for ((key, value) in store) {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    private companion object {
        const val PREFIX = "mezze."
    }
}
