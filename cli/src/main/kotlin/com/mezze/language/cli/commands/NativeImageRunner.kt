package com.mezze.language.cli.commands

import java.io.File

/**
 * Wraps the `native-image` tool (GraalVM's AOT compiler) for
 * `mez build --native`. The standard tools-on-$PATH pattern: if
 * `native-image` isn't installed, we print a helpful install message
 * and exit before doing any other work.
 *
 * Why externally-installed: GraalVM is a ~1 GB tarball and binding it
 * to `~/.mezze/` would balloon mezze's install footprint for users
 * who never need native binaries. `mez build` (JVM jar) works on any
 * JDK; `mez build --native` requires the user to have set up GraalVM.
 */
object NativeImageRunner {

    /**
     * Locate `native-image` on the user's `$PATH`. Returns the resolved
     * absolute path or null if not found. Honors GRAALVM_HOME as a
     * fallback when set.
     */
    fun locate(): File? {
        // GRAALVM_HOME/bin/native-image is the canonical install layout.
        System.getenv("GRAALVM_HOME")?.let { home ->
            val candidate = File(home, "bin/native-image")
            if (candidate.canExecute()) return candidate
        }
        // Walk $PATH for `native-image`.
        val pathEntries = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        for (entry in pathEntries) {
            val candidate = File(entry, "native-image")
            if (candidate.canExecute()) return candidate
        }
        return null
    }

    /**
     * The error message shown when `--native` is requested but the tool
     * isn't installed. Designed to be actionable: link to install docs
     * and the env var users need to set.
     */
    fun missingMessage(): String = """
        Error: `native-image` not found on PATH.

        `mez build --native` requires GraalVM (with native-image) installed.

        Install:
          - https://www.graalvm.org/downloads/
          - macOS:  brew install --cask graalvm-jdk
          - Linux:  use sdkman: sdk install java 24-graal
          - Then:   gu install native-image  (older releases only — newer
                    GraalVM distributions ship native-image by default)

        After install, either:
          - put `<graalvm>/bin` on your PATH, or
          - set GRAALVM_HOME=<graalvm> in your shell.

        Verify:  native-image --version
    """.trimIndent()
}
