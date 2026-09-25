package com.mezze.language.cli

import java.io.File

/**
 * Locates a Java executable and resolves the `mezze-runtime.jar`
 * classpath used by the wire-protocol RuntimeServer subprocess.
 *
 * The native `mez` binary handles parsing / typechecking / lowering;
 * `mez dev` and `mez exec` spawn a JVM child running `mez-runtime.jar`'s
 * RuntimeServer for actual execution (Truffle, Loom virtual threads,
 * polyglot interop). See `docs/wire-protocol/00-design.md`.
 */
object JvmLauncher {

    /**
     * Find a Java 21+ executable. Returns null if not found.
     *
     * Search order:
     * 1. MEZZE_JAVA_HOME env var
     * 2. JAVA_HOME env var
     * 3. `java` on PATH
     * 4. Known GraalVM install locations
     */
    fun findJava(): File? {
        System.getenv("MEZZE_JAVA_HOME")?.let { home ->
            val java = File(home, "bin/java")
            if (java.canExecute()) return java
        }
        System.getenv("JAVA_HOME")?.let { home ->
            val java = File(home, "bin/java")
            if (java.canExecute()) return java
        }
        findOnPath("java")?.let { return it }
        for (path in KNOWN_JAVA_LOCATIONS) {
            if (path.canExecute()) return path
        }
        return null
    }

    /**
     * Build the classpath for the RuntimeServer JVM subprocess.
     *
     * Always includes the slim `mezze-runtime.jar`. Adds polyglot JARs
     * if the project declares them. Falls back to the dev-build output
     * if no user install exists.
     */
    fun buildClasspath(polyglotDeps: Map<String, String> = emptyMap()): String {
        val jars = mutableListOf<String>()

        val runtimeJar = findRuntimeJar()
            ?: error("mezze-runtime.jar not found in ~/.mezze/lib/ or sibling locations. Reinstall mez.")
        jars.add(runtimeJar.absolutePath)

        val mezLib = MezzeHome.libDir
        for ((language, version) in polyglotDeps) {
            val jarName = when (language) {
                "python" -> "graalpy-$version.jar"
                "javascript", "js" -> "graaljs-$version.jar"
                else -> "$language-$version.jar"
            }
            val jar = File(mezLib, jarName)
            if (jar.exists()) {
                jars.add(jar.absolutePath)
            } else {
                System.err.println("Warning: Polyglot JAR not found: $jar")
                System.err.println("  Install with: mez polyglot add $language $version")
            }
        }
        return jars.joinToString(File.pathSeparator)
    }

    private fun findOnPath(executable: String): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(File.pathSeparator)) {
            val file = File(dir, executable)
            if (file.canExecute()) return file
        }
        return null
    }

    private val KNOWN_JAVA_LOCATIONS = listOf(
        File(System.getProperty("user.home"), ".sdkman/candidates/java/current/bin/java"),
        File("/usr/lib/jvm/default/bin/java"),
        File("/usr/local/opt/openjdk/bin/java"),
    )

    /**
     * Locate `mezze-runtime.jar`. Search order:
     *   1. `~/.mezze/lib/mezze-runtime.jar` (standard install).
     *   2. Project dev build (`interpreter/build/libs/mezze-runtime.jar`).
     *   3. Sibling to the `mez` binary or `../lib/` (relocatable installs).
     */
    internal fun findRuntimeJar(): File? {
        val installed = MezzeHome.runtimeJar
        if (installed.exists()) return installed

        val projectRoot = System.getProperty("mezze.project.root")
        if (projectRoot != null) {
            val devJar = File(projectRoot, "interpreter/build/libs/mezze-runtime.jar")
            if (devJar.exists()) return devJar
        }

        val classSource = JvmLauncher::class.java.protectionDomain?.codeSource?.location?.toURI()
        if (classSource != null) {
            val binDir = File(classSource).parentFile
            val sibling = File(binDir, "mezze-runtime.jar")
            if (sibling.exists()) return sibling
            val libJar = File(binDir?.parentFile, "lib/mezze-runtime.jar")
            if (libJar.exists()) return libJar
        }
        return null
    }
}
