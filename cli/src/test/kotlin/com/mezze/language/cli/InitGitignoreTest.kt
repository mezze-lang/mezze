package com.mezze.language.cli

import com.mezze.language.cli.commands.InitCommand
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `mez init` appends its ignore patterns to an EXISTING .gitignore instead of
 * skipping the file — otherwise a pre-existing repo never ignores `.deps/` /
 * `.mez/` and commits generated symlinks by accident (B1). The merge logic is a
 * pure function so we test it without touching disk.
 */
class InitGitignoreTest {

    @Test fun `empty gitignore gets all mez patterns`() {
        val add = InitCommand.gitignoreAdditions("")!!
        for (p in listOf(".build/", "*.jar", "Mezze.lock", ".deps/", ".mez/")) {
            assertTrue(add.contains(p), "should add $p")
        }
    }

    @Test fun `fully-covered gitignore needs no additions`() {
        val existing = """
            node_modules/
            .build/
            *.jar
            Mezze.lock
            .deps/
            .mez/
        """.trimIndent()
        assertNull(InitCommand.gitignoreAdditions(existing), "nothing to add when all present")
    }

    @Test fun `partially-covered gitignore adds only the missing patterns`() {
        // Already ignores .mez/ and .build/; missing the rest.
        val existing = ".build/\n.mez/\n"
        val add = InitCommand.gitignoreAdditions(existing)!!
        assertFalse(add.contains("\n.mez/\n"), "must not re-add an already-present pattern")
        assertTrue(add.contains(".deps/"), "adds the missing .deps/")
        assertTrue(add.contains("*.jar"), "adds the missing *.jar")
        assertTrue(add.contains("Mezze.lock"), "adds the missing Mezze.lock")
    }

    @Test fun `a pattern inside a comment does not count as present`() {
        // `.deps/` mentioned only in prose — should still be added as a real rule.
        val existing = "# we used to ignore .deps/ here\nnode_modules/\n"
        val add = InitCommand.gitignoreAdditions(existing)!!
        assertTrue(add.lineSequence().any { it.trim() == ".deps/" }, "adds a real .deps/ rule")
    }

    @Test fun `missing trailing newline is handled before appending`() {
        val add = InitCommand.gitignoreAdditions("node_modules/")!!
        assertTrue(add.startsWith("\n"), "should insert a separating newline when the file lacks one")
    }
}
