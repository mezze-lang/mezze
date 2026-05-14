package com.mezze.language.cli.commands

import java.io.File

/**
 * Native-image flag groups shared between the `mez` CLI's own native
 * build (cli/build.gradle.kts) and the user-app native build path
 * ([NativeBuildPath]).
 *
 * The canonical source is `META-INF/mezze/native-image-flags.txt` (a
 * sectioned plain-text file). Both consumers parse it identically:
 *   - Gradle reads it off disk during config (see `readNativeImageFlags`
 *     in cli/build.gradle.kts).
 *   - Runtime reads it off the classpath via this object.
 *
 * Keep new flags in the text file, not here. This file is just the
 * runtime accessor.
 */
object NativeImageFlags {

    /** Common to both CLI and user-app builds. */
    val common: List<String> by lazy { parsed["common"].orEmpty() }

    /** Packages to initialize at image build time (common). */
    val initAtBuildTime: List<String> by lazy { parsed["init-at-build-time"].orEmpty() }

    /** Extra init-at-build-time packages for the user-app build only. */
    val userOnlyInitAtBuildTime: List<String> by lazy {
        parsed["user-only-init-at-build-time"].orEmpty()
    }

    /**
     * `--initialize-at-build-time=<pkg>` arguments expanded from
     * [initAtBuildTime] + [userOnlyInitAtBuildTime], ready to splice
     * into a native-image command line.
     */
    fun userInitAtBuildTimeArgs(): List<String> =
        (initAtBuildTime + userOnlyInitAtBuildTime)
            .map { "--initialize-at-build-time=$it" }

    private val parsed: Map<String, List<String>> by lazy {
        val stream = NativeImageFlags::class.java.classLoader
            .getResourceAsStream(RESOURCE_PATH)
            ?: error("$RESOURCE_PATH not found on classpath — broken `mez` install")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        parse(text)
    }

    private const val RESOURCE_PATH = "META-INF/mezze/native-image-flags.txt"

    /** Parse the sectioned plain-text format. Public for build-script reuse. */
    fun parse(text: String): Map<String, List<String>> {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                val name = line.substring(1, line.length - 1)
                current = sections.getOrPut(name) { mutableListOf() }
                continue
            }
            current?.add(line)
        }
        return sections
    }

    /** Filesystem-based variant for the gradle build (no classpath yet). */
    fun parseFile(file: File): Map<String, List<String>> = parse(file.readText())
}
