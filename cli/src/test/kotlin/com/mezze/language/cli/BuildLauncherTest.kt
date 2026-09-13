package com.mezze.language.cli

import com.mezze.language.cli.commands.BuildCommand
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The generated launcher's `java` invocation (T8). With no polyglot/[jars], it
 * must keep the exact `-jar` form (unchanged common case). With extra language
 * jars it must switch to `-cp` (java forbids `-jar` + `-cp`) with the jar first
 * and the app main class named — otherwise polyglot jars never load in a built
 * app.
 */
class BuildLauncherTest {

    @Test fun `unix no extra jars uses -jar`() {
        val line = BuildCommand.unixLaunchLine(emptyList())
        assertTrue(line.contains("-jar \"\$JAR\""), "plain build keeps -jar: $line")
        assertFalse(line.contains("-cp"), "plain build must not use -cp")
    }

    @Test fun `unix with extra jars uses -cp jar-first and main class`() {
        val line = BuildCommand.unixLaunchLine(listOf("lib/graalpy.jar", "lib/graaljs.jar"))
        assertFalse(line.contains("-jar"), "polyglot build must not use -jar (incompatible with -cp)")
        assertTrue(line.contains("-cp \"\$JAR:\$DIR/lib/graalpy.jar:\$DIR/lib/graaljs.jar\""),
            "jar first, then each extra jar under \$DIR: $line")
        assertTrue(line.contains(BuildCommand.APP_MAIN_CLASS), "must name the app main class")
    }

    @Test fun `windows no extra jars uses -jar`() {
        val line = BuildCommand.windowsLaunchLine(emptyList())
        assertTrue(line.contains("-jar \"%JAR%\""), "plain build keeps -jar: $line")
        assertFalse(line.contains("-cp"))
    }

    @Test fun `windows with extra jars uses -cp with backslashes and semicolons`() {
        val line = BuildCommand.windowsLaunchLine(listOf("lib/graalpy.jar"))
        assertFalse(line.contains("-jar"))
        assertTrue(line.contains("-cp \"%JAR%;%DIR%lib\\graalpy.jar\""),
            "windows uses ; separator and \\ path sep: $line")
        assertTrue(line.contains(BuildCommand.APP_MAIN_CLASS))
    }
}
