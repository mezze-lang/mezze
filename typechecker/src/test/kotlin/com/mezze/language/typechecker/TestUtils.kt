package com.mezze.language.typechecker

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test utilities for Mezze typechecker tests.
 */
object TestUtils {
    /**
     * Computes the project root directory dynamically by walking up from the current
     * working directory until we find a directory containing settings.gradle.kts.
     *
     * This avoids hardcoding absolute paths.
     */
    fun findProjectRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (current != null) {
            if (current.resolve("settings.gradle.kts").toFile().exists()) {
                return current
            }
            current = current.parent
        }
        // Fallback to current directory
        return Paths.get("").toAbsolutePath()
    }

    /**
     * Sets up the mezze.project.root system property for tests.
     * Call this in @BeforeEach methods.
     */
    fun setupProjectRoot() {
        val projectRoot = findProjectRoot()
        System.setProperty("mezze.project.root", projectRoot.toString())
    }
}
