package com.mezze.language.cli

import java.io.File

/**
 * Finds a Java executable and spawns JVM subprocesses for `mez dev`.
 *
 * The native `mez` binary handles compilation; execution requires a JVM
 * for Truffle JIT, polyglot interop, and virtual threads.
 */
object JvmLauncher {

    private val JVM_ARGS = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        // JDK 24+: Truffle uses Unsafe::objectFieldOffset internally; silence the
        // deprecation warning rather than letting it flood `mez dev` startup.
        "--sun-misc-unsafe-memory-access=allow",
        "-Dpolyglot.engine.WarnInterpreterOnly=false"
    )

    private val CDS_ARCHIVE = File(System.getProperty("user.home"), ".mezze/cache/mezze.jsa")

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
        // 1. MEZZE_JAVA_HOME
        System.getenv("MEZZE_JAVA_HOME")?.let { home ->
            val java = File(home, "bin/java")
            if (java.canExecute()) return java
        }

        // 2. JAVA_HOME
        System.getenv("JAVA_HOME")?.let { home ->
            val java = File(home, "bin/java")
            if (java.canExecute()) return java
        }

        // 3. java on PATH
        val pathJava = findOnPath("java")
        if (pathJava != null) return pathJava

        // 4. Known locations
        val knownPaths = listOf(
            File(System.getProperty("user.home"), ".sdkman/candidates/java/current/bin/java"),
            File("/usr/lib/jvm/default/bin/java"),
            File("/usr/local/opt/openjdk/bin/java")
        )
        for (path in knownPaths) {
            if (path.canExecute()) return path
        }

        return null
    }

    /**
     * Build the classpath for the JVM subprocess.
     *
     * Always includes mezze-lang.jar. Adds polyglot JARs if declared.
     * Falls back to development build output if ~/.mez/lib/ doesn't exist.
     */
    fun buildClasspath(polyglotDeps: Map<String, String> = emptyMap()): String {
        val jars = mutableListOf<String>()

        // Find mezze-lang.jar
        val mezzeLangJar = findMezzeLangJar()
        if (mezzeLangJar != null) {
            jars.add(mezzeLangJar.absolutePath)
        } else {
            System.err.println("Warning: mezze-lang.jar not found, using current classpath")
            return System.getProperty("java.class.path") ?: ""
        }

        // Add polyglot JARs from ~/.mez/lib/
        val mezLib = File(System.getProperty("user.home"), ".mez/lib")
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

    /**
     * Spawn a JVM subprocess. Forwards stdin/stdout/stderr.
     * Returns the exit code.
     */
    fun spawn(
        mainClass: String,
        classpath: String,
        programArgs: List<String> = emptyList(),
        extraJvmArgs: List<String> = emptyList()
    ): Int {
        val java = findJava() ?: throw RuntimeException("Java not found")

        val command = mutableListOf(java.absolutePath)
        command.addAll(JVM_ARGS)
        command.addAll(extraJvmArgs)
        command.add("-cp")
        command.add(classpath)
        command.add(mainClass)
        command.addAll(programArgs)

        val process = ProcessBuilder(command)
            .inheritIO()
            .start()

        // Forward SIGINT to child process
        Runtime.getRuntime().addShutdownHook(Thread {
            if (process.isAlive) {
                process.destroy()
            }
        })

        return process.waitFor()
    }

    private fun findOnPath(executable: String): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(File.pathSeparator)) {
            val file = File(dir, executable)
            if (file.canExecute()) return file
        }
        return null
    }

    private fun findMezzeLangJar(): File? {
        // 1. ~/.mez/lib/mezze-lang.jar (installed)
        val installed = File(System.getProperty("user.home"), ".mez/lib/mezze-lang.jar")
        if (installed.exists()) return installed

        // 2. Development: cli/build/libs/mezze.jar (shadow JAR output)
        val projectRoot = System.getProperty("mezze.project.root")
        if (projectRoot != null) {
            val devJar = File(projectRoot, "cli/build/libs/mezze.jar")
            if (devJar.exists()) return devJar
        }

        // 3. Sibling to native binary: same directory as mez binary
        val classSource = JvmLauncher::class.java.protectionDomain?.codeSource?.location?.toURI()
        if (classSource != null) {
            val binDir = File(classSource).parentFile
            val sibling = File(binDir, "mezze-lang.jar")
            if (sibling.exists()) return sibling
            // Also check ../lib/ (standard install layout)
            val libJar = File(binDir?.parentFile, "lib/mezze-lang.jar")
            if (libJar.exists()) return libJar
        }

        return null
    }
}
